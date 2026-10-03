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

#define MAXBANDS 31
#define MAXPEQ 8
#define MAXIR 2048
static const double TAU = 6.28318530717958647692;

struct Biquad {
    double b0, b1, b2, a1, a2;
    double x1, x2, y1, y2;
};

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
static int irTaps = 0;
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
static int specPreIdx = 0, specPostIdx = 0;

static const double F10[10] = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
static const double F15[15] = {25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000};
static const double F31[31] = {20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000};

/* ── Build #120: parameter atomicity (spec 6) ─────────────────────────────
   The UI thread writes parameter TARGETS under a seqlock; the audio thread
   applies them at BLOCK BOUNDARIES (process() entry), so the audio thread
   never observes a half-updated configuration (e.g. new gain with old Q).
   Single UI-thread writer, bounded-retry reader, fully lock-free. */
typedef struct { int on; double f, g, q; } PeqParam;
static PeqParam peqTarget[MAXPEQ];
static double grafTarget[MAXBANDS];
static double grafApplied[MAXBANDS];
static volatile int peqSeq = 0;   /* seqlock: even = stable */
static volatile int grafSeq = 0;

static double bandFreq(int i) {
    if (nbands >= 31) return F31[i];
    if (nbands >= 15) return F15[i];
    return F10[i];
}

static void clearB(Biquad* f) { f->x1 = f->x2 = f->y1 = f->y2 = 0.0; }

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
            if (snap[i].on) {
                setPeak(&peq[i][0], snap[i].f, snap[i].g, snap[i].q);
                setPeak(&peq[i][1], snap[i].f, snap[i].g, snap[i].q);
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
                setPeak(&graphic[i][0], bandFreq(i), snap[i], 1.0);
                setPeak(&graphic[i][1], bandFreq(i), snap[i], 1.0);
                grafApplied[i] = snap[i];
            }
        }
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
    /* parametric EQ */
    for (int i = 0; i < MAXPEQ; i++) {
        if (peqOn[i]) { l = runB(&peq[i][0], l); r = runB(&peq[i][1], r); }
    }
    /* graphic EQ */
    for (int i = 0; i < nbands; i++) {
        l = runB(&graphic[i][0], l);
        r = runB(&graphic[i][1], r);
    }
    /* shelves */
    if (bassDb != 0.0) { l = runB(&bassSh[0], l); r = runB(&bassSh[1], r); }
    if (trebleDb != 0.0) { l = runB(&trebSh[0], l); r = runB(&trebSh[1], r); }
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
    __sync_synchronize();
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setPreamp(JNIEnv* env, jobject thiz, jfloat db) {
    double v = (double) db;
    if (!isfinite(v)) return;
    preampDb = v < -30.0 ? -30.0 : (v > 30.0 ? 30.0 : v);
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
    bassDb = (double) bass; trebleDb = (double) treble;
    setShelf(&bassSh[0], 100.0, bassDb, false); setShelf(&bassSh[1], 100.0, bassDb, false);
    setShelf(&trebSh[0], 8000.0, trebleDb, true); setShelf(&trebSh[1], 8000.0, trebleDb, true);
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setCompressor(JNIEnv* env, jobject thiz, jboolean on, jfloat threshDb,
                                          jfloat ratio, jfloat atkMs, jfloat relMs) {
    compOn = on == JNI_TRUE;
    compThreshDb = (double) threshDb;
    compRatio = ratio > 1.0f ? (double) ratio : 4.0;
    double atk = atkMs > 0.1f ? atkMs : 5.0f;
    double rel = relMs > 1.0f ? relMs : 150.0f;
    compAtkCoef = 1.0 - exp(-1.0 / (atk * sr / 1000.0));
    compRelCoef = 1.0 - exp(-1.0 / (rel * sr / 1000.0));
    compEnvDb = 0.0;
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setStereo(JNIEnv* env, jobject thiz, jfloat width, jfloat balance,
                                       jboolean swap, jboolean mono) {
    double wv = (double) width;
    if (!isfinite(wv)) wv = 1.0;
    if (wv < 0.0) wv = 0.0; else if (wv > 4.0) wv = 4.0;
    stWidth = wv > 0.0 ? wv : 1.0;
    stBalance = balance < -1.0f ? -1.0 : (balance > 1.0f ? 1.0 : (double) balance);
    stSwap = swap == JNI_TRUE;
    stMono = mono == JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setLimiter(JNIEnv* env, jobject thiz, jboolean on, jfloat threshDb) {
    limOn = on == JNI_TRUE;
    double tv = (double) threshDb;
    if (!isfinite(tv)) tv = -1.0;
    if (tv < -60.0) tv = -60.0; else if (tv > 0.0) tv = 0.0;
    limThreshDb = tv;
    limEnvDb = 0.0;
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setConvolverEnabled(JNIEnv* env, jobject thiz, jboolean on) {
    convOn = on == JNI_TRUE;
    histIdx = 0;
    memset(histL, 0, sizeof(histL)); memset(histR, 0, sizeof(histR));
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_loadIr(JNIEnv* env, jobject thiz, jfloatArray left, jfloatArray right) {
    jsize n = env->GetArrayLength(left);
    if (n > MAXIR) n = MAXIR;
    jfloat* l = env->GetFloatArrayElements(left, NULL);
    jfloat* r = env->GetFloatArrayElements(right, NULL);
    for (int i = 0; i < n; i++) { irL[i] = l[i]; irR[i] = r[i]; }
    env->ReleaseFloatArrayElements(left, l, JNI_ABORT);
    env->ReleaseFloatArrayElements(right, r, JNI_ABORT);
    irTaps = (int) n;
    histIdx = 0;
    memset(histL, 0, sizeof(histL)); memset(histR, 0, sizeof(histR));
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_process(JNIEnv* env, jobject thiz, jshortArray buf, jint frames) {
    jshort* p = (jshort*) env->GetPrimitiveArrayCritical(buf, NULL);
    if (p == NULL) return;
    jniFramesIn += frames;
    applyPendingParams();   /* whole-block commit: audio never sees partial configs */
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
            specPreL[specPreIdx] = p[2 * i];
            specPreR[specPreIdx] = p[2 * i + 1];
            if (++specPreIdx >= 2048) specPreIdx = 0;
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
            specPostL[specPostIdx] = p[2 * i];
            specPostR[specPostIdx] = p[2 * i + 1];
            if (++specPostIdx >= 2048) specPostIdx = 0;
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
        case 1: ringA = specPreL;  ringB = NULL;     ringIdx = specPreIdx;  break;
        case 2: ringA = specPreR;  ringB = NULL;     ringIdx = specPreIdx;  break;
        case 4: ringA = specPostL; ringB = NULL;     ringIdx = specPostIdx; break;
        case 5: ringA = specPostR; ringB = NULL;     ringIdx = specPostIdx; break;
        default: if (mode >= 3) { ringA = specPostL; ringB = specPostR; ringIdx = specPostIdx; }
                 else { ringA = specPreL; ringB = specPreR; ringIdx = specPreIdx; }
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
