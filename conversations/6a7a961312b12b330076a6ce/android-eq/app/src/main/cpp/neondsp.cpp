/*
 * NeonDsp — native real-time DSP chain (Build #116)
 *
 * The processing order per stereo frame:
 *   mono/balance/swap -> stereo width -> preamp -> parametric EQ (8 slots)
 *   -> graphic EQ (10/15/31 bands) -> bass shelf -> treble shelf
 *   -> compressor (stereo-linked) -> convolver (small IR, optional)
 *   -> output limiter (stereo-linked) -> soft clip counter
 *
 * Straight C, no allocations in process(), fixed point not needed on ARM.
 */
#include <jni.h>
#include <math.h>
#include <string.h>
#include <atomic>

#define MAXBANDS 31
#define MAXPEQ 8
#define MAXIR 2048
static const double TAU = 6.28318530717958647692;

struct Biquad {
    double b0, b1, b2, a1, a2;      /* working coefficients (audio thread) */
    double x1, x2, y1, y2;          /* filter history — NEVER cleared on param change */
    /* Build #136 hardening: target coefficients + bounded ramp. Parameter
       changes write the tb/ta targets and start a short ramp; the working coefficients
       glide to target at block boundaries. No history reset, no click, no
       zipper noise — and the audio thread stays lock-free and allocation-free. */
    double tb0, tb1, tb2, ta1, ta2;
    int ramp;                        /* remaining ramp blocks; 0 = at target */
};
#define COEF_RAMP_BLOCKS 10

/* Glide working coefficients one step toward target. Called once per block
   (only while ramp > 0). Direct-form-1 history is preserved. */
static inline void rampB(Biquad* f) {
    if (f->ramp <= 0) return;
    double t = 1.0 / (double) f->ramp;
    f->b0 += (f->tb0 - f->b0) * t; f->b1 += (f->tb1 - f->b1) * t; f->b2 += (f->tb2 - f->b2) * t;
    f->a1 += (f->ta1 - f->a1) * t; f->a2 += (f->ta2 - f->a2) * t;
    if (--f->ramp == 0) { f->b0 = f->tb0; f->b1 = f->tb1; f->b2 = f->tb2; f->a1 = f->ta1; f->a2 = f->ta2; }
}

static double sr = 48000.0;
static int nbands = 10;

static Biquad graphic[MAXBANDS][2];
static Biquad peq[MAXPEQ][2];
static Biquad bassSh[2], trebSh[2];
static int peqOn[MAXPEQ];

static double preampDb = 0.0, bassDb = 0.0, trebleDb = 0.0;

static bool compOn = false;
static double compThreshDb = -18.0, compRatio = 4.0;
static double compAtkCoef = 0.05, compRelCoef = 0.005, compEnvDb = 0.0;

static double stWidth = 1.0, stBalance = 0.0;
static bool stSwap = false, stMono = false;

static bool convOn = false;
static std::atomic<int> irTaps{0};   /* Build #136: released-store after IR arrays are committed */
static float irL[MAXIR], irR[MAXIR];
static float histL[MAXIR], histR[MAXIR];
static int histIdx = 0;

static bool limOn = true;
static double limThreshDb = -1.0, limEnvDb = 0.0;

static long clipCount = 0, procFrames = 0;
static long nanEvents = 0;
static long jniFramesIn = 0;   /* frames handed to the JNI process() entry */
static int inRmsMs = 0, inPeakMs = 0;    /* pre-DSP meters, 0..1000 units */
static int outRmsMs = 0, outPeakMs = 0; /* post-DSP meters, 0..1000 units */
/* Build #121: per-channel meters (L/R) for the dashboard */
static int inLRmsMs = 0, inRRmsMs = 0, inLPkMs = 0, inRPkMs = 0;
static int outLRmsMs = 0, outRRmsMs = 0, outLPkMs = 0, outRPkMs = 0;
/* Build #121/#123: spectrum snapshot rings, pre-DSP and post-DSP, per
   channel. The audio thread does only bounded O(n) stores; ALL analysis
   (FFT, band mapping) runs on the caller (UI) thread inside spectrum().
   Never on the audio thread. */
static short specPreL[2048], specPreR[2048], specPostL[2048], specPostR[2048];
static std::atomic<int> specPreIdx{0}, specPostIdx{0};   /* Build #136: audio-thread writes, UI-thread reads */

static const double F10[10] = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
static const double F15[15] = {25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000};
static const double F31[31] = {20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000};

/* ── Build #120: parameter atomicity (spec 6) ─────────────────────────────
   The UI thread writes parameter TARGETS under a seqlock; the audio thread
   applies them at BLOCK BOUNDARIES (process() entry), so the audio thread
   never observes a half-updated configuration (e.g. new gain with old Q).
   Single UI-thread writer, bounded-retry reader, fully lock-free. */
typedef struct { int on; double f, g, q; } PeqParam;

/* Build #136 hardening: coherent snapshot for ALL scalar DSP parameters
   (preamp, shelves, compressor, stereo, limiter, convolver enable). The
   JNI setters write cfgTarget under a seqlock; applyPendingParams() commits
   the whole snapshot at the block boundary. The audio thread can therefore
   never observe new-threshold+old-ratio or width-without-balance. */
typedef struct {
    double preampDb, bassDb, trebleDb;
    int compOn; double compThreshDb, compRatio, compAtkCoef, compRelCoef;
    double stWidth, stBalance; int stSwap, stMono;
    int limOn; double limThreshDb;
    int convOn;
} CfgScalar;
static CfgScalar cfgTarget = { 0.0, 0.0, 0.0, 0, -18.0, 4.0, 0.05, 0.005,
                               1.0, 0.0, 0, 0, 1, -1.0, 0 };
static CfgScalar cfgApplied = cfgTarget;
static volatile int cfgSeq = 0;   /* seqlock: even = stable */
static PeqParam peqTarget[MAXPEQ];
static double grafTarget[MAXBANDS];
static double grafApplied[MAXBANDS];
/* Build #136: last-applied PEQ snapshot — setPeak() also clears biquad
   history (clearB), so it must run ONLY on real parameter changes. The
   graphic loop already had this check; the PEQ loop called setPeak every
   audio block, wiping filter memory every ~5ms (clicks, broken LF). */
static PeqParam peqApplied[MAXPEQ];
static volatile int peqSeq = 0;   /* seqlock: even = stable */
static volatile int grafSeq = 0;

static double bandFreq(int i) {
    if (nbands >= 31) return F31[i];
    if (nbands >= 15) return F15[i];
    return F10[i];
}

static void clearB(Biquad* f) { f->x1 = f->x2 = f->y1 = f->y2 = 0.0; }

/* Write TARGET coefficients and start the ramp — history untouched. */
static void setPeakT(Biquad* f, double freq, double gdb, double q) {
    double A = pow(10.0, gdb / 40.0);
    double w0 = TAU * freq / sr;
    double cw = cos(w0), sw = sin(w0);
    double alpha = sw / (2.0 * q);
    double a0 = 1.0 + alpha / A;
    f->tb0 = (1.0 + alpha * A) / a0;
    f->tb1 = (-2.0 * cw) / a0;
    f->tb2 = (1.0 - alpha * A) / a0;
    f->ta1 = (-2.0 * cw) / a0;
    f->ta2 = (1.0 - alpha / A) / a0;
    f->ramp = COEF_RAMP_BLOCKS;
}

static void setPeak(Biquad* f, double freq, double gdb, double q) {
    double A = pow(10.0, gdb / 40.0);
    double w0 = TAU * freq / sr;
    double cw = cos(w0), sw = sin(w0);
    double alpha = sw / (2.0 * q);
    double a0 = 1.0 + alpha / A;
    f->b0 = (1.0 + alpha * A) / a0;
    f->b1 = (-2.0 * cw) / a0;
    f->b2 = (1.0 - alpha * A) / a0;
    f->a1 = (-2.0 * cw) / a0;
    f->a2 = (1.0 - alpha / A) / a0;
    clearB(f);
}

static void setShelf(Biquad* f, double freq, double gdb, bool high) {
    double A = pow(10.0, gdb / 40.0);
    double w0 = TAU * freq / sr;
    double cw = cos(w0), sw = sin(w0);
    double alpha = sw / 1.4;  /* Q ~ 0.7 */
    double sqA = sqrt(A);
    double b0, b1, b2, a0, a1, a2;
    if (!high) {
        b0 =    A * ((A + 1) - (A - 1) * cw + 2 * sqA * alpha);
        b1 =  2 * A * ((A - 1) - (A + 1) * cw);
        b2 =    A * ((A + 1) - (A - 1) * cw - 2 * sqA * alpha);
        a0 =        (A + 1) + (A - 1) * cw + 2 * sqA * alpha;
        a1 =   -2 * ((A - 1) + (A + 1) * cw);
        a2 =        (A + 1) + (A - 1) * cw - 2 * sqA * alpha;
    } else {
        b0 =    A * ((A + 1) + (A - 1) * cw + 2 * sqA * alpha);
        b1 = -2 * A * ((A - 1) + (A + 1) * cw);
        b2 =    A * ((A + 1) + (A - 1) * cw - 2 * sqA * alpha);
        a0 =        (A + 1) - (A - 1) * cw + 2 * sqA * alpha;
        a1 =    2 * ((A - 1) - (A + 1) * cw);
        a2 =        (A + 1) - (A - 1) * cw - 2 * sqA * alpha;
    }
    f->b0 = b0 / a0; f->b1 = b1 / a0; f->b2 = b2 / a0;
    f->a1 = a1 / a0; f->a2 = a2 / a0;
    clearB(f);
}

/* Target-mode shelf — history untouched, ramped at block boundaries. */
static void setShelfT(Biquad* f, double freq, double gdb, bool high) {
    double A = pow(10.0, gdb / 40.0);
    double w0 = TAU * freq / sr;
    double cw = cos(w0), sw = sin(w0);
    double alpha = sw / 1.4;
    double sqA = sqrt(A);
    double b0, b1, b2, a0, a1, a2;
    if (!high) {
        b0 =    A * ((A + 1) - (A - 1) * cw + 2 * sqA * alpha);
        b1 =  2 * A * ((A - 1) - (A + 1) * cw);
        b2 =    A * ((A + 1) - (A - 1) * cw - 2 * sqA * alpha);
        a0 =        (A + 1) + (A - 1) * cw + 2 * sqA * alpha;
        a1 =   -2 * ((A - 1) + (A + 1) * cw);
        a2 =        (A + 1) - (A - 1) * cw - 2 * sqA * alpha;
    } else {
        b0 =    A * ((A + 1) + (A - 1) * cw + 2 * sqA * alpha);
        b1 = -2 * A * ((A - 1) + (A + 1) * cw);
        b2 =    A * ((A + 1) + (A - 1) * cw - 2 * sqA * alpha);
        a0 =        (A + 1) - (A - 1) * cw + 2 * sqA * alpha;
        a1 =   2 * ((A - 1) - (A + 1) * cw);
        a2 =        (A + 1) - (A - 1) * cw - 2 * sqA * alpha;
    }
    f->tb0 = b0 / a0; f->tb1 = b1 / a0; f->tb2 = b2 / a0;
    f->ta1 = a1 / a0; f->ta2 = a2 / a0;
    f->ramp = COEF_RAMP_BLOCKS;
}

static inline double runB(Biquad* f, double x) {
    double y = f->b0 * x + f->b1 * f->x1 + f->b2 * f->x2 - f->a1 * f->y1 - f->a2 * f->y2;
    f->x2 = f->x1; f->x1 = x; f->y2 = f->y1; f->y1 = y;
    return y;
}

/* Build #121: 1024-point radix-2 FFT for the spectrum analyzer.
   Called ONLY from the spectrum() JNI getter (UI thread), never from the
   audio callback. */
static void fft1024(float* re, float* im) {
    int n = 1024, i, j, bit;
    for (i = 1, j = 0; i < n; i++) {
        bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
    }
    for (int len = 2; len <= n; len <<= 1) {
        float ang = -6.2831853f / (float) len;
        float wr = cosf(ang), wi = sinf(ang);
        for (i = 0; i < n; i += len) {
            float cr = 1.0f, ci = 0.0f;
            for (j = 0; j < len / 2; j++) {
                float ur = re[i + j], ui = im[i + j];
                float ar = re[i + j + len / 2], ai = im[i + j + len / 2];
                float vr = ar * cr - ai * ci;
                float vi = ar * ci + ai * cr;
                re[i + j] = ur + vr; im[i + j] = ui + vi;
                re[i + j + len / 2] = ur - vr; im[i + j + len / 2] = ui - vi;
                float ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr;
            }
        }
    }
}

static void applyPendingParams() {
    int tries, i;
    for (tries = 0; tries < 4; tries++) {
        int s1 = peqSeq;
        __sync_synchronize();
        if (s1 & 1) continue;
        PeqParam snap[MAXPEQ];
        for (i = 0; i < MAXPEQ; i++) snap[i] = peqTarget[i];
        __sync_synchronize();
        if (peqSeq != s1) continue;
        for (i = 0; i < MAXPEQ; i++) {
            peqOn[i] = snap[i].on;
            if (snap[i].on && (!peqApplied[i].on || snap[i].f != peqApplied[i].f ||
                               snap[i].g != peqApplied[i].g || snap[i].q != peqApplied[i].q)) {
                /* Build #136 hardening: target-mode recompute + ramp — the
                   filter HISTORY is never cleared on a parameter change. */
                setPeakT(&peq[i][0], snap[i].f, snap[i].g, snap[i].q);
                setPeakT(&peq[i][1], snap[i].f, snap[i].g, snap[i].q);
                peqApplied[i] = snap[i];
            } else if (!snap[i].on) {
                if (peqApplied[i].on) {
                    /* Build #136 hardening: OFF transition GLIDES to identity
                       instead of snapping off mid-waveform — no click. */
                    setPeakT(&peq[i][0], snap[i].f, 0.0, snap[i].q);
                    setPeakT(&peq[i][1], snap[i].f, 0.0, snap[i].q);
                }
                peqApplied[i].on = 0;
            }
        }
        break;
    }
    for (tries = 0; tries < 4; tries++) {
        int s1 = grafSeq;
        __sync_synchronize();
        if (s1 & 1) continue;
        double snap[MAXBANDS];
        for (i = 0; i < MAXBANDS; i++) snap[i] = grafTarget[i];
        __sync_synchronize();
        if (grafSeq != s1) continue;
        for (i = 0; i < nbands; i++) {
            if (snap[i] != grafApplied[i]) {
                setPeakT(&graphic[i][0], bandFreq(i), snap[i], 1.0);
                setPeakT(&graphic[i][1], bandFreq(i), snap[i], 1.0);
                grafApplied[i] = snap[i];
            }
        }
        break;
    }
    /* Build #136 hardening: commit the scalar config snapshot coherently. */
    for (tries = 0; tries < 4; tries++) {
        int s1 = cfgSeq;
        __sync_synchronize();
        if (s1 & 1) continue;
        CfgScalar snap2 = cfgTarget;
        __sync_synchronize();
        if (cfgSeq != s1) continue;
        /* Envelope/env state resets ONLY on toggle transitions — a threshold
           move during playback must never pump the gain envelope. */
        if (snap2.compOn && !cfgApplied.compOn) compEnvDb = 0.0;
        if (snap2.limOn && !cfgApplied.limOn) limEnvDb = 0.0;
        if (snap2.convOn && !cfgApplied.convOn) {
            /* fresh enable: clear the convolver history on the AUDIO thread */
            histIdx = 0;
            memset(histL, 0, sizeof(histL)); memset(histR, 0, sizeof(histR));
        }
        if (snap2.bassDb != cfgApplied.bassDb) {
            setShelfT(&bassSh[0], 100.0, snap2.bassDb, false);
            setShelfT(&bassSh[1], 100.0, snap2.bassDb, false);
        }
        if (snap2.trebleDb != cfgApplied.trebleDb) {
            setShelfT(&trebSh[0], 8000.0, snap2.trebleDb, true);
            setShelfT(&trebSh[1], 8000.0, snap2.trebleDb, true);
        }
        preampDb = snap2.preampDb;
        bassDb = snap2.bassDb;
        trebleDb = snap2.trebleDb;
        compOn = snap2.compOn;
        compThreshDb = snap2.compThreshDb; compRatio = snap2.compRatio;
        compAtkCoef = snap2.compAtkCoef; compRelCoef = snap2.compRelCoef;
        stWidth = snap2.stWidth; stBalance = snap2.stBalance;
        stSwap = snap2.stSwap; stMono = snap2.stMono;
        limOn = snap2.limOn; limThreshDb = snap2.limThreshDb;
        convOn = snap2.convOn;
        cfgApplied = snap2;
        break;
    }
}

static inline void processFrame(jshort* p) {
    jshort inL = p[0], inR = p[1];
    double l = (double) inL, r = (double) inR;
    /* preamp */
    if (preampDb != 0.0) {
        double g = pow(10.0, preampDb / 20.0);
        l *= g; r *= g;
    }
    /* parametric EQ — held open while a disable-glide still lands */
    for (int i = 0; i < MAXPEQ; i++) {
        if (peqOn[i] || peq[i][0].ramp > 0) { l = runB(&peq[i][0], l); r = runB(&peq[i][1], r); }
    }
    /* graphic EQ */
    for (int i = 0; i < nbands; i++) {
        l = runB(&graphic[i][0], l);
        r = runB(&graphic[i][1], r);
    }
    /* shelves — held open while a glide back to identity still lands */
    if (bassDb != 0.0 || bassSh[0].ramp > 0) { l = runB(&bassSh[0], l); r = runB(&bassSh[1], r); }
    if (trebleDb != 0.0 || trebSh[0].ramp > 0) { l = runB(&trebSh[0], l); r = runB(&trebSh[1], r); }
    /* stereo stage: width, balance, swap, mono */
    if (stMono) { l = r = (l + r) * 0.5; }
    if (stSwap) { double tmp = l; l = r; r = tmp; }
    {
        double m = (l + r) * 0.5;
        double s = (l - r) * 0.5 * stWidth;
        l = m + s; r = m - s;
    }
    if (stBalance != 0.0) {
        if (stBalance > 0) l *= (1.0 - stBalance * 0.99);
        else r *= (1.0 + stBalance * 0.99);
    }
    /* convolver */
    if (convOn && irTaps > 0) {
        histL[histIdx] = (float) l;
        histR[histIdx] = (float) r;
        double ol = 0.0, orr = 0.0;
        int h = histIdx;
        for (int i = 0; i < irTaps; i++) {
            ol += irL[i] * histL[h];
            orr += irR[i] * histR[h];
            if (--h < 0) h = MAXIR - 1;
        }
        l = ol; r = orr;
        if (++histIdx >= MAXIR) histIdx = 0;
    }
    /* compressor, stereo-linked */
    if (compOn) {
        double det = (fabs(l) + fabs(r)) * 0.5 / 32768.0;
        double detDb = 20.0 * log10(det > 1e-9 ? det : 1e-9);
        double target = detDb > compThreshDb ? compThreshDb + (detDb - compThreshDb) / compRatio : detDb;
        double coef = target > compEnvDb ? compAtkCoef : compRelCoef;
        compEnvDb += coef * (target - compEnvDb);
        double gr = pow(10.0, (compEnvDb - detDb) / 20.0);
        l *= gr; r *= gr;
    }
    /* output limiter, stereo-linked */
    if (limOn) {
        double det = fabs(l) > fabs(r) ? fabs(l) : fabs(r);
        double detDb = 20.0 * log10((det / 32768.0) > 1e-9 ? det / 32768.0 : 1e-9);
        double target = detDb > limThreshDb ? limThreshDb : detDb;
        limEnvDb += (target < limEnvDb ? 0.30 : 0.02) * (target - limEnvDb);
        double g = pow(10.0, (limEnvDb - detDb) / 20.0);
        l *= g; r *= g;
    }
    /* NaN/Inf safety (spec 17): flush all filter states and pass the raw
       frame through — the audio service must never crash or emit garbage. */
    if (!isfinite(l) || !isfinite(r)) {
        nanEvents++;
        for (int i = 0; i < MAXBANDS; i++) { clearB(&graphic[i][0]); clearB(&graphic[i][1]); }
        for (int i = 0; i < MAXPEQ; i++) { clearB(&peq[i][0]); clearB(&peq[i][1]); }
        clearB(&bassSh[0]); clearB(&bassSh[1]); clearB(&trebSh[0]); clearB(&trebSh[1]);
        compEnvDb = 0.0; limEnvDb = 0.0;
        p[0] = inL; p[1] = inR;
        procFrames++;
        return;
    }
    /* clip detect + clamp */
    if (l > 32767.0 || l < -32768.0 || r > 32767.0 || r < -32768.0) clipCount++;
    if (l > 32767.0) l = 32767.0; else if (l < -32768.0) l = -32768.0;
    if (r > 32767.0) r = 32767.0; else if (r < -32768.0) r = -32768.0;
    p[0] = (jshort) lround(l);
    p[1] = (jshort) lround(r);
    procFrames++;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_init(JNIEnv* env, jobject thiz, jint rate, jint bands) {
    sr = rate > 0 ? (double) rate : 48000.0;
    nbands = bands >= 5 && bands <= 31 ? bands : 10;
    for (int i = 0; i < nbands; i++) {
        setPeak(&graphic[i][0], bandFreq(i), 0.0, 1.0);
        setPeak(&graphic[i][1], bandFreq(i), 0.0, 1.0);
    }
    for (int i = 0; i < MAXPEQ; i++) peqOn[i] = 0;
    setShelf(&bassSh[0], 100.0, 0.0, false); setShelf(&bassSh[1], 100.0, 0.0, false);
    setShelf(&trebSh[0], 8000.0, 0.0, true); setShelf(&trebSh[1], 8000.0, 0.0, true);
    clipCount = 0; procFrames = 0; histIdx = 0;
    compEnvDb = 0.0; limEnvDb = 0.0;
    memset(histL, 0, sizeof(histL)); memset(histR, 0, sizeof(histR));
    /* Build #120: reset parameter targets + applied snapshot so a mode
       switch starts from a clean, fully-committed configuration. */
    __sync_synchronize();
    for (int i = 0; i < MAXPEQ; i++) { peqTarget[i].on = 0; peqTarget[i].f = 1000.0; peqTarget[i].g = 0.0; peqTarget[i].q = 1.0; }
    for (int i = 0; i < MAXBANDS; i++) { grafTarget[i] = 0.0; grafApplied[i] = 0.0; }
    memset(peqApplied, 0, sizeof(peqApplied));
    /* Build #136 hardening: start every session with ramp disabled and
       targets == working identity, plus a clean scalar config. Kotlin
       reapplies the persisted DSP snapshot immediately after init. */
    for (int i = 0; i < MAXBANDS; i++) for (int ch = 0; ch < 2; ch++) {
        graphic[i][ch].tb0 = graphic[i][ch].b0; graphic[i][ch].tb1 = graphic[i][ch].b1;
        graphic[i][ch].tb2 = graphic[i][ch].b2; graphic[i][ch].ta1 = graphic[i][ch].a1;
        graphic[i][ch].ta2 = graphic[i][ch].a2; graphic[i][ch].ramp = 0;
    }
    Biquad* sh[4] = { &bassSh[0], &bassSh[1], &trebSh[0], &trebSh[1] };
    for (int i = 0; i < 4; i++) {
        sh[i]->tb0 = sh[i]->b0; sh[i]->tb1 = sh[i]->b1; sh[i]->tb2 = sh[i]->b2;
        sh[i]->ta1 = sh[i]->a1; sh[i]->ta2 = sh[i]->a2; sh[i]->ramp = 0;
    }
    cfgTarget = CfgScalar { 0.0, 0.0, 0.0, 0, -18.0, 4.0, 0.05, 0.005, 1.0, 0.0, 0, 0, 1, -1.0, 0 };
    cfgApplied = cfgTarget;
    preampDb = 0.0; bassDb = 0.0; trebleDb = 0.0;
    compOn = 0; compThreshDb = -18.0; compRatio = 4.0; compEnvDb = 0.0;
    stWidth = 1.0; stBalance = 0.0; stSwap = 0; stMono = 0;
    limOn = 1; limThreshDb = -1.0; limEnvDb = 0.0;
    convOn = 0;
    __sync_synchronize();
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setPreamp(JNIEnv* env, jobject thiz, jfloat db) {
    double v = (double) db;
    if (!isfinite(v)) return;
    v = v < -30.0 ? -30.0 : (v > 30.0 ? 30.0 : v);
    int s = cfgSeq; cfgSeq = s + 1; __sync_synchronize();
    cfgTarget.preampDb = v;
    __sync_synchronize(); cfgSeq = s + 2;
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setGraphicGains(JNIEnv* env, jobject thiz, jfloatArray gains) {
    jfloat* g = env->GetFloatArrayElements(gains, NULL);
    jsize n = env->GetArrayLength(gains);
    int s = grafSeq;
    grafSeq = s + 1;                 /* odd: write in progress */
    __sync_synchronize();
    int i;
    for (i = 0; i < MAXBANDS; i++) {
        double gd = (i < n) ? (double) g[i] : 0.0;
        if (!isfinite(gd)) gd = 0.0;
        if (gd < -30.0) gd = -30.0; else if (gd > 30.0) gd = 30.0;
        grafTarget[i] = gd;
    }
    __sync_synchronize();
    grafSeq = s + 2;                 /* even: committed, applied at block entry */
    env->ReleaseFloatArrayElements(gains, g, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setParametric(JNIEnv* env, jobject thiz, jint slot, jboolean on,
                                           jfloat freq, jfloat gainDb, jfloat q) {
    if (slot < 0 || slot >= MAXPEQ) return;
    double f = (double) freq, g = (double) gainDb, qq = (double) q;
    if (!isfinite(f) || !isfinite(g) || !isfinite(qq)) return;
    if (f < 10.0) f = 10.0; else if (f > 20000.0) f = 20000.0;
    if (g < -30.0) g = -30.0; else if (g > 30.0) g = 30.0;
    if (qq < 0.1) qq = 0.1; else if (qq > 10.0) qq = 10.0;
    int s = peqSeq;
    peqSeq = s + 1;                  /* odd: slot update in progress */
    __sync_synchronize();
    peqTarget[slot].on = on ? 1 : 0;
    peqTarget[slot].f = f;
    peqTarget[slot].g = g;
    peqTarget[slot].q = qq;
    __sync_synchronize();
    peqSeq = s + 2;                  /* even: whole slot committed atomically */
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setShelves(JNIEnv* env, jobject thiz, jfloat bass, jfloat treble) {
    double bv = (double) bass, tv = (double) treble;
    if (!isfinite(bv)) bv = 0.0;
    if (!isfinite(tv)) tv = 0.0;
    /* Build #136 hardening: v136 rewrote shelf coefficients field-by-field ON
       THE UI THREAD (torn coefficients = clicks) and cleared filter history on
       every change (audible reset transient). Now: only the target gains are
       committed under the seqlock; the audio thread recomputes coefficients
       coherently at the block boundary and ramps them — history preserved. */
    bv = bv < -30.0 ? -30.0 : (bv > 30.0 ? 30.0 : bv);
    tv = tv < -30.0 ? -30.0 : (tv > 30.0 ? 30.0 : tv);
    int s = cfgSeq; cfgSeq = s + 1; __sync_synchronize();
    cfgTarget.bassDb = bv; cfgTarget.trebleDb = tv;
    __sync_synchronize(); cfgSeq = s + 2;
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setCompressor(JNIEnv* env, jobject thiz, jboolean on, jfloat threshDb,
                                          jfloat ratio, jfloat atkMs, jfloat relMs) {
    double th = (double) threshDb;
    if (!isfinite(th)) th = -18.0;
    if (th < -60.0) th = -60.0; else if (th > 0.0) th = 0.0;
    /* Build #136 hardening: whole set committed coherently — the audio thread
       can never mix a new threshold with the old ratio/time constants. The
       gain envelope now resets ONLY on the OFF→ON transition, not on every
       threshold move (v136 pumped audibly during slider drags). */
    double ra = (double) ratio;
    if (!isfinite(ra) || ra < 1.0) ra = 4.0;
    double atk = atkMs > 0.1f ? (double) atkMs : 5.0;
    double rel = relMs > 1.0f ? (double) relMs : 150.0;
    double atkC = 1.0 - exp(-1.0 / (atk * sr / 1000.0));
    double relC = 1.0 - exp(-1.0 / (rel * sr / 1000.0));
    int s = cfgSeq; cfgSeq = s + 1; __sync_synchronize();
    cfgTarget.compOn = on == JNI_TRUE;
    cfgTarget.compThreshDb = th;
    cfgTarget.compRatio = ra;
    cfgTarget.compAtkCoef = atkC;
    cfgTarget.compRelCoef = relC;
    __sync_synchronize(); cfgSeq = s + 2;
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setStereo(JNIEnv* env, jobject thiz, jfloat width, jfloat balance,
                                       jboolean swap, jboolean mono) {
    double wv = (double) width;
    if (!isfinite(wv)) wv = 1.0;
    if (wv < 0.0) wv = 0.0; else if (wv > 4.0) wv = 4.0;
    double bal = (double) balance;
    if (!isfinite(bal)) bal = 0.0;
    /* Build #136 hardening: width/balance/swap/mono commit as ONE coherent
       snapshot — never width-applied-with-old-balance. */
    if (bal < -1.0) bal = -1.0; else if (bal > 1.0) bal = 1.0;
    int s = cfgSeq; cfgSeq = s + 1; __sync_synchronize();
    cfgTarget.stWidth = wv > 0.0 ? wv : 1.0;
    cfgTarget.stBalance = bal;
    cfgTarget.stSwap = swap == JNI_TRUE;
    cfgTarget.stMono = mono == JNI_TRUE;
    __sync_synchronize(); cfgSeq = s + 2;
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setLimiter(JNIEnv* env, jobject thiz, jboolean on, jfloat threshDb) {
    double tv = (double) threshDb;
    if (!isfinite(tv)) tv = -1.0;
    if (tv < -60.0) tv = -60.0; else if (tv > 0.0) tv = 0.0;
    /* Build #136 hardening: envelope resets only on OFF→ON, not per move. */
    int s = cfgSeq; cfgSeq = s + 1; __sync_synchronize();
    cfgTarget.limOn = on == JNI_TRUE;
    cfgTarget.limThreshDb = tv;
    __sync_synchronize(); cfgSeq = s + 2;
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setConvolverEnabled(JNIEnv* env, jobject thiz, jboolean on) {
    /* Build #136 hardening: the enable flag commits through the cfg seqlock;
       the history reset happens ON THE AUDIO THREAD at the OFF→ON transition
       (v136 reset convolver state from the UI thread mid-frame). */
    int s = cfgSeq; cfgSeq = s + 1; __sync_synchronize();
    cfgTarget.convOn = on == JNI_TRUE;
    __sync_synchronize(); cfgSeq = s + 2;
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_loadIr(JNIEnv* env, jobject thiz, jfloatArray left, jfloatArray right) {
    if (left == NULL || right == NULL) return;
    jsize nL = env->GetArrayLength(left);
    jsize nR = env->GetArrayLength(right);
    /* Build #136 hardening: mismatched L/R lengths use the SHORTER side
       (v136 indexed both with n from the LEFT array — oversized right arrays
       read out of bounds into irR). */
    jsize n = nL < nR ? nL : nR;
    if (n > MAXIR) n = MAXIR;   /* oversized IR: truncate, never overflow */
    jfloat* l = env->GetFloatArrayElements(left, NULL);
    jfloat* r = env->GetFloatArrayElements(right, NULL);
    if (l == NULL || r == NULL) {   /* Build #136: pinning can fail — never deref NULL */
        if (l != NULL) env->ReleaseFloatArrayElements(left, l, JNI_ABORT);
        if (r != NULL) env->ReleaseFloatArrayElements(right, r, JNI_ABORT);
        return;
    }
    /* Build #136 hardening: zero the previous taps FIRST so a stale long IR
       never rings under a shorter new one, sanitize NaN/Inf to 0, then
       release-store the new tap count so the audio thread can never observe
       new taps with partially-written arrays. No audio-side state (histIdx,
       histL/R) is touched from this thread anymore. */
    memset(irL, 0, sizeof(irL));
    memset(irR, 0, sizeof(irR));
    for (int i = 0; i < n; i++) {
        float lv = l[i], rv = r[i];
        if (!isfinite(lv)) lv = 0.0f;
        if (!isfinite(rv)) rv = 0.0f;
        irL[i] = lv; irR[i] = rv;
    }
    env->ReleaseFloatArrayElements(left, l, JNI_ABORT);
    env->ReleaseFloatArrayElements(right, r, JNI_ABORT);
    __atomic_store_n(&irTaps, (int) n, __ATOMIC_RELEASE);
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_process(JNIEnv* env, jobject thiz, jshortArray buf, jint frames) {
    jshort* p = (jshort*) env->GetPrimitiveArrayCritical(buf, NULL);
    if (p == NULL) return;
    jniFramesIn += frames;
    applyPendingParams();   /* whole-block commit: audio never sees partial configs */
    /* Build #136 hardening: bounded coefficient glide — one step per block,
     * only while a ramp is in flight. Lock-free, allocation-free, and the
     * filter history is never disturbed. */
    for (int i = 0; i < nbands; i++) { rampB(&graphic[i][0]); rampB(&graphic[i][1]); }
    for (int i = 0; i < MAXPEQ; i++) { rampB(&peq[i][0]); rampB(&peq[i][1]); }
    rampB(&bassSh[0]); rampB(&bassSh[1]); rampB(&trebSh[0]); rampB(&trebSh[1]);
    /* input meters — measured on raw captured PCM BEFORE any processing */
    {
        double sum = 0.0, sumL = 0.0, sumR = 0.0; int pk = 0, pkL = 0, pkR = 0;
        for (int i = 0; i < frames; i++) {
            int l = (int) p[2 * i], r = (int) p[2 * i + 1];
            int al = l < 0 ? -l : l, ar = r < 0 ? -r : r;
            if (al > pkL) pkL = al;
            if (ar > pkR) pkR = ar;
            if (al > pk) pk = al;
            if (ar > pk) pk = ar;
            sum += (double) l * (double) l + (double) r * (double) r;
            sumL += (double) l * (double) l;
            sumR += (double) r * (double) r;
        }
        double rms = sqrt(sum / (frames * 2.0));
        inRmsMs = (int) ((rms * 1000.0) / 32768.0);
        inPeakMs = (pk * 1000) / 32768;
        inLRmsMs = (int) ((sqrt(sumL / frames) * 1000.0) / 32768.0);
        inRRmsMs = (int) ((sqrt(sumR / frames) * 1000.0) / 32768.0);
        inLPkMs = (pkL * 1000) / 32768;
        inRPkMs = (pkR * 1000) / 32768;
        /* spectrum snapshot rings (captured PCM, pre-DSP): bounded O(n)
           stores, no allocation, no locks — analysis on the UI thread */
        for (int i = 0; i < frames; i++) {
            { int wi = specPreIdx.load(std::memory_order_relaxed);
              specPreL[wi] = p[2 * i]; specPreR[wi] = p[2 * i + 1];
              specPreIdx.store((wi + 1) & 2047, std::memory_order_relaxed); }
        }
    }
    for (int i = 0; i < frames; i++) processFrame(p + 2 * i);
    /* output meters — measured AFTER the full DSP chain */
    {
        double sum = 0.0, sumL = 0.0, sumR = 0.0; int pk = 0, pkL = 0, pkR = 0;
        for (int i = 0; i < frames; i++) {
            int l = (int) p[2 * i], r = (int) p[2 * i + 1];
            int al = l < 0 ? -l : l, ar = r < 0 ? -r : r;
            if (al > pkL) pkL = al;
            if (ar > pkR) pkR = ar;
            if (al > pk) pk = al;
            if (ar > pk) pk = ar;
            sum += (double) l * (double) l + (double) r * (double) r;
            sumL += (double) l * (double) l;
            sumR += (double) r * (double) r;
        }
        double rms = sqrt(sum / (frames * 2.0));
        outRmsMs = (int) ((rms * 1000.0) / 32768.0);
        outPeakMs = (pk * 1000) / 32768;
        outLRmsMs = (int) ((sqrt(sumL / frames) * 1000.0) / 32768.0);
        outRRmsMs = (int) ((sqrt(sumR / frames) * 1000.0) / 32768.0);
        outLPkMs = (pkL * 1000) / 32768;
        outRPkMs = (pkR * 1000) / 32768;
        /* processed-PCM rings (post-DSP) for the BEFORE/AFTER analyzer */
        for (int i = 0; i < frames; i++) {
            { int wi = specPostIdx.load(std::memory_order_relaxed);
              specPostL[wi] = p[2 * i]; specPostR[wi] = p[2 * i + 1];
              specPostIdx.store((wi + 1) & 2047, std::memory_order_relaxed); }
        }
    }
    env->ReleasePrimitiveArrayCritical(buf, p, 0);
}

JNIEXPORT jlong JNICALL
Java_com_neon_eq_dsp_NeonDsp_clipCount(JNIEnv* env, jobject thiz) { return (jlong) clipCount; }

JNIEXPORT jlong JNICALL
Java_com_neon_eq_dsp_NeonDsp_processedFrames(JNIEnv* env, jobject thiz) { return (jlong) procFrames; }

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_resetStats(JNIEnv* env, jobject thiz) { clipCount = 0; procFrames = 0; nanEvents = 0; }

JNIEXPORT jlong JNICALL
Java_com_neon_eq_dsp_NeonDsp_nanCount(JNIEnv* env, jobject thiz) { return (jlong) nanEvents; }

/* Build #119: signal-path counters and DSP meters (measured, never inferred) */
JNIEXPORT jlong JNICALL
Java_com_neon_eq_dsp_NeonDsp_jniFrames(JNIEnv* env, jobject thiz) { return jniFramesIn; }

JNIEXPORT jint JNICALL
Java_com_neon_eq_dsp_NeonDsp_inRmsMs(JNIEnv* env, jobject thiz) { return inRmsMs; }

JNIEXPORT jint JNICALL
Java_com_neon_eq_dsp_NeonDsp_inPeakMs(JNIEnv* env, jobject thiz) { return inPeakMs; }

JNIEXPORT jint JNICALL
Java_com_neon_eq_dsp_NeonDsp_outRmsMs(JNIEnv* env, jobject thiz) { return outRmsMs; }

JNIEXPORT jint JNICALL
Java_com_neon_eq_dsp_NeonDsp_outPeakMs(JNIEnv* env, jobject thiz) { return outPeakMs; }

/* Build #121: per-channel meters */
JNIEXPORT jint JNICALL Java_com_neon_eq_dsp_NeonDsp_inLRmsMs(JNIEnv* e, jobject t) { return inLRmsMs; }
JNIEXPORT jint JNICALL Java_com_neon_eq_dsp_NeonDsp_inRRmsMs(JNIEnv* e, jobject t) { return inRRmsMs; }
JNIEXPORT jint JNICALL Java_com_neon_eq_dsp_NeonDsp_inLPkMs(JNIEnv* e, jobject t) { return inLPkMs; }
JNIEXPORT jint JNICALL Java_com_neon_eq_dsp_NeonDsp_inRPkMs(JNIEnv* e, jobject t) { return inRPkMs; }
JNIEXPORT jint JNICALL Java_com_neon_eq_dsp_NeonDsp_outLRmsMs(JNIEnv* e, jobject t) { return outLRmsMs; }
JNIEXPORT jint JNICALL Java_com_neon_eq_dsp_NeonDsp_outRRmsMs(JNIEnv* e, jobject t) { return outRRmsMs; }
JNIEXPORT jint JNICALL Java_com_neon_eq_dsp_NeonDsp_outLPkMs(JNIEnv* e, jobject t) { return outLPkMs; }
JNIEXPORT jint JNICALL Java_com_neon_eq_dsp_NeonDsp_outRPkMs(JNIEnv* e, jobject t) { return outRPkMs; }

/* Build #123: spectrum analyzer — fills `out` (n bins) with normalized
   log-frequency magnitudes (20Hz..20kHz, 0.0..1.0, -66dBFS floor).
   mode: 0 = pre-DSP L+R, 1 = pre-DSP L, 2 = pre-DSP R,
         3 = post-DSP L+R, 4 = post-DSP L, 5 = post-DSP R.
   Runs entirely on the CALLER's thread; the audio callback only maintains
   the cheap snapshot rings. */
JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_spectrum(JNIEnv* env, jobject thiz, jfloatArray out, jint mode) {
    jsize n = env->GetArrayLength(out);
    if (n < 4) return;
    jfloat* dst = env->GetFloatArrayElements(out, NULL);
    if (dst == NULL) return;
    static float re[1024], im[1024];
    double binHz = sr / 1024.0;
    int i;
    const short* ringA; const short* ringB; int ringIdx;
    switch (mode) {
        case 1: ringA = specPreL;  ringB = NULL;     ringIdx = specPreIdx.load(std::memory_order_relaxed);  break;
        case 2: ringA = specPreR;  ringB = NULL;     ringIdx = specPreIdx.load(std::memory_order_relaxed);  break;
        case 4: ringA = specPostL; ringB = NULL;     ringIdx = specPostIdx.load(std::memory_order_relaxed); break;
        case 5: ringA = specPostR; ringB = NULL;     ringIdx = specPostIdx.load(std::memory_order_relaxed); break;
        default: if (mode >= 3) { ringA = specPostL; ringB = specPostR; ringIdx = specPostIdx.load(std::memory_order_relaxed); }
                 else { ringA = specPreL; ringB = specPreR; ringIdx = specPreIdx.load(std::memory_order_relaxed); }
                 break;
    }
    /* assemble oldest-first window and apply Hann */
    for (i = 0; i < 1024; i++) {
        int idx = (ringIdx + i) & 2047;
        float s = ringB != NULL
            ? (((float) ringA[idx] + (float) ringB[idx]) * 0.5f) / 32768.0f
            : (float) ringA[idx] / 32768.0f;
        re[i] = s * (0.5f - 0.5f * cosf(6.2831853f * (float) i / 1024.0f));
        im[i] = 0.0f;
    }
    fft1024(re, im);
    const double fMin = 20.0, fMax = 20000.0;
    int prevBin = 1;
    for (int b = 0; b < n; b++) {
        double f0 = fMin * pow(fMax / fMin, (double) b / n);
        double f1 = fMin * pow(fMax / fMin, (double) (b + 1) / n);
        int bin0 = (int) (f0 / binHz); if (bin0 < 1) bin0 = 1;
        int bin1 = (int) (f1 / binHz); if (bin1 <= bin0) bin1 = bin0 + 1;
        if (bin1 > 512) bin1 = 512;
        float mag = 0.0f;
        for (int k = bin0; k < bin1 && k <= 512; k++) {
            float m = sqrtf(re[k] * re[k] + im[k] * im[k]);
            if (m > mag) mag = m;
        }
        double db = 20.0 * log10((double) mag + 1e-9);
        double v = (db + 66.0) / 66.0;
        if (v < 0.0) v = 0.0; else if (v > 1.0) v = 1.0;
        dst[b] = (jfloat) v;
        prevBin = bin1;
    }
    (void) prevBin;
    env->ReleaseFloatArrayElements(out, dst, 0);
}

} /* extern "C" */
