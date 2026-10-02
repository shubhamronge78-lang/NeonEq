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

static const double F10[10] = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
static const double F15[15] = {25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000};
static const double F31[31] = {20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000};

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

static inline void processFrame(jshort* p) {
    double l = (double) p[0], r = (double) p[1];
    /* stereo stage */
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
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setPreamp(JNIEnv* env, jobject thiz, jfloat db) {
    preampDb = (double) db;
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setGraphicGains(JNIEnv* env, jobject thiz, jfloatArray gains) {
    jfloat* g = env->GetFloatArrayElements(gains, NULL);
    jsize n = env->GetArrayLength(gains);
    for (int i = 0; i < nbands && i < n; i++) {
        setPeak(&graphic[i][0], bandFreq(i), (double) g[i], 1.0);
        setPeak(&graphic[i][1], bandFreq(i), (double) g[i], 1.0);
    }
    env->ReleaseFloatArrayElements(gains, g, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setParametric(JNIEnv* env, jobject thiz, jint slot, jboolean on,
                                           jfloat freq, jfloat gainDb, jfloat q) {
    if (slot < 0 || slot >= MAXPEQ) return;
    peqOn[slot] = on ? 1 : 0;
    if (on) {
        setPeak(&peq[slot][0], (double) freq, (double) gainDb, (double) q);
        setPeak(&peq[slot][1], (double) freq, (double) gainDb, (double) q);
    }
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
    stWidth = width > 0.0f ? (double) width : 1.0;
    stBalance = balance < -1.0f ? -1.0 : (balance > 1.0f ? 1.0 : (double) balance);
    stSwap = swap == JNI_TRUE;
    stMono = mono == JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_setLimiter(JNIEnv* env, jobject thiz, jboolean on, jfloat threshDb) {
    limOn = on == JNI_TRUE;
    limThreshDb = (double) threshDb;
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
    jshort* p = env->GetShortArrayElements(buf, NULL);
    for (int i = 0; i < frames; i++) processFrame(p + 2 * i);
    env->ReleaseShortArrayElements(buf, p, 0);
}

JNIEXPORT jlong JNICALL
Java_com_neon_eq_dsp_NeonDsp_clipCount(JNIEnv* env, jobject thiz) { return (jlong) clipCount; }

JNIEXPORT jlong JNICALL
Java_com_neon_eq_dsp_NeonDsp_processedFrames(JNIEnv* env, jobject thiz) { return (jlong) procFrames; }

JNIEXPORT void JNICALL
Java_com_neon_eq_dsp_NeonDsp_resetStats(JNIEnv* env, jobject thiz) { clipCount = 0; procFrames = 0; }

} /* extern "C" */
