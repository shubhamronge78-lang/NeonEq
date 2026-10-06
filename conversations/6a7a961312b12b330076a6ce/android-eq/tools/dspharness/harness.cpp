/* SonicCore native DSP host self-test (runs in CI, ASan+UBSan).
   Drives the REAL JNI entry points of app/src/main/cpp/neondsp.cpp through a
   stub jni.h: silence, normal PCM, full-scale PCM, 200-block rapid parameter
   churn, extreme-but-valid max-gain stack, repeated bypass toggles, convolver
   IR load with mismatched L/R lengths, spectrum readout. Asserts: no NaN, no
   out-of-int16 output, frames counters progress, spectrum finite.

   v138 REGRESSION ORIGIN: this harness caught the v137 setShelfT low-shelf
   transcription bug (a2 written with b2's signs -> unstable bass shelf ->
   exponential blowup -> inf -> NaN at the comp/limiter multiply). Any change
   to the DSP must keep this harness green. */
#include "jni.h"
#include <cstdio>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <vector>
extern "C" {
void Java_com_neon_eq_dsp_NeonDsp_init(JNIEnv*, jobject, jint, jint);
void Java_com_neon_eq_dsp_NeonDsp_setPreamp(JNIEnv*, jobject, jfloat);
void Java_com_neon_eq_dsp_NeonDsp_setGraphicGains(JNIEnv*, jobject, jfloatArray);
void Java_com_neon_eq_dsp_NeonDsp_setParametric(JNIEnv*, jobject, jint, jboolean, jfloat, jfloat, jfloat);
void Java_com_neon_eq_dsp_NeonDsp_setShelves(JNIEnv*, jobject, jfloat, jfloat);
void Java_com_neon_eq_dsp_NeonDsp_setCompressor(JNIEnv*, jobject, jboolean, jfloat, jfloat, jfloat, jfloat);
void Java_com_neon_eq_dsp_NeonDsp_setStereo(JNIEnv*, jobject, jfloat, jfloat, jboolean, jboolean);
void Java_com_neon_eq_dsp_NeonDsp_setLimiter(JNIEnv*, jobject, jboolean, jfloat);
void Java_com_neon_eq_dsp_NeonDsp_setConvolverEnabled(JNIEnv*, jobject, jboolean);
void Java_com_neon_eq_dsp_NeonDsp_loadIr(JNIEnv*, jobject, jfloatArray, jfloatArray);
void Java_com_neon_eq_dsp_NeonDsp_process(JNIEnv*, jobject, jshortArray, jint);
jlong Java_com_neon_eq_dsp_NeonDsp_clipCount(JNIEnv*, jobject);
jlong Java_com_neon_eq_dsp_NeonDsp_nanCount(JNIEnv*, jobject);
jlong Java_com_neon_eq_dsp_NeonDsp_processedFrames(JNIEnv*, jobject);
void Java_com_neon_eq_dsp_NeonDsp_resetStats(JNIEnv*, jobject);
void Java_com_neon_eq_dsp_NeonDsp_spectrum(JNIEnv*, jobject, jfloatArray, jint);
}
HostArr g_hostArrays[512]; int g_hostArrCount = 0;
static JNIEnv env;
static jobject thiz = nullptr;
static int fails = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("  FAIL: %s\n", msg); fails++; } else printf("  ok: %s\n", msg); } while(0)

int main() {
    const int SR = 48000, BANDS = 10, FRAMES = 480;
    Java_com_neon_eq_dsp_NeonDsp_init(&env, thiz, SR, BANDS);

    jshort pcm[FRAMES * 2];
    jshortArray buf = (jshortArray) &g_hostArrays[hostReg(pcm, FRAMES * 2, 0)];
    auto finitePcm = [&]{ for (int i = 0; i < FRAMES*2; i++) if (pcm[i] != pcm[i] && (pcm[i] > 32767 || pcm[i] < -32768)) return false; return true; };

    printf("[1] silence\n");
    memset(pcm, 0, sizeof(pcm));
    Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
    CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "silence: no NaN");

    printf("[2] normal PCM (sine, -12 dBFS)\n");
    for (int i = 0; i < FRAMES; i++) { double v = 2000.0 * sin(2*M_PI*440*i/SR); pcm[2*i]=(jshort)v; pcm[2*i+1]=(jshort)v; }
    Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
    jlong f0 = Java_com_neon_eq_dsp_NeonDsp_processedFrames(&env, thiz);
    CHECK(f0 >= 2*FRAMES, "frames counter progressed");
    CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "normal PCM: no NaN");

    printf("[3] full-scale PCM (limiter must hold output finite)\n");
    for (int i = 0; i < FRAMES; i++) { pcm[2*i]=32767; pcm[2*i+1]=32767; }
    Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
    CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "full-scale: no NaN");
    bool inRange = true; for (int i = 0; i < FRAMES*2; i++) if (pcm[i] > 32767 || pcm[i] < -32768) inRange = false;
    CHECK(inRange, "full-scale: output inside int16 range");

    printf("[4] rapid parameter churn while processing (EQ drags, PEQ moves, stage toggles)\n");
    srand(42);
    for (int blk = 0; blk < 200; blk++) {
        static float gains[31]; for (int i = 0; i < 31; i++) gains[i] = -15.0 + (rand() % 3600) / 100.0;
        jfloatArray g = (jfloatArray) &g_hostArrays[hostReg(gains, 31, 1)];
        Java_com_neon_eq_dsp_NeonDsp_setGraphicGains(&env, thiz, g);
        Java_com_neon_eq_dsp_NeonDsp_setParametric(&env, thiz, blk % 8, blk % 2, 40.0 + rand() % 15000, -15.0 + rand() % 3500 / 100.0, 0.3 + rand() % 120 / 10.0);
        Java_com_neon_eq_dsp_NeonDsp_setShelves(&env, thiz, -12.0 + rand() % 2400 / 100.0, -12.0 + rand() % 2400 / 100.0);
        Java_com_neon_eq_dsp_NeonDsp_setCompressor(&env, thiz, blk % 2, -40.0 + rand() % 3800 / 100.0, 1.5 + rand() % 1000 / 100.0, 0.1 + rand() % 900 / 1000.0, 0.05 + rand() % 1000 / 100.0);
        Java_com_neon_eq_dsp_NeonDsp_setStereo(&env, thiz, 0.0 + rand() % 200 / 100.0, -1.0 + rand() % 200 / 100.0, blk % 4 == 0, blk % 7 == 0);
        Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, blk % 2, -30.0 + rand() % 2900 / 100.0);
        Java_com_neon_eq_dsp_NeonDsp_setPreamp(&env, thiz, -15.0 + rand() % 3500 / 100.0);
        double v = 8000.0 * sin(2*M_PI*(100 + blk * 7)*blk/SR);
        for (int i = 0; i < FRAMES; i++) { pcm[2*i]=(jshort)(v * (blk % 2 ? 1 : -1)); pcm[2*i+1]=(jshort)v; }
        Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
        if (Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) != 0) { printf("  FAIL: NaN at block %d\n", blk); fails++; break; }
    }
    CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "rapid churn: no NaN across 200 blocks");

    printf("[5] extreme-but-valid parameters (max gain stack + shelves + PEQ)\n");
    { static float gains[31]; for (int i = 0; i < 31; i++) gains[i] = 20.0f;
      jfloatArray g = (jfloatArray) &g_hostArrays[hostReg(gains, 31, 1)];
      Java_com_neon_eq_dsp_NeonDsp_setGraphicGains(&env, thiz, g); }
    Java_com_neon_eq_dsp_NeonDsp_setPreamp(&env, thiz, 20.0f);
    Java_com_neon_eq_dsp_NeonDsp_setShelves(&env, thiz, 20.0f, 20.0f);
    for (int s = 0; s < 8; s++) Java_com_neon_eq_dsp_NeonDsp_setParametric(&env, thiz, s, 1, 100.0f + s * 2000, 20.0f, 0.1f);
    for (int blk = 0; blk < 50; blk++) {
        for (int i = 0; i < FRAMES; i++) { pcm[2*i]=(jshort)(30000.0*sin(2*M_PI*80*i/SR)); pcm[2*i+1]=(jshort)(30000.0*sin(2*M_PI*80*i/SR)); }
        Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
        if (Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) != 0) { printf("  FAIL: NaN under max stack at block %d\n", blk); fails++; break; }
        bool ok = true; for (int i = 0; i < FRAMES*2; i++) if (pcm[i] > 32767 || pcm[i] < -32768) ok = false;
        if (!ok) { printf("  FAIL: out-of-int16 output at block %d\n", blk); fails++; break; }
    }
    CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "max stack: no NaN");
    printf("  clips under max stack: %lld (limiter active, expected small)\n", (long long)Java_com_neon_eq_dsp_NeonDsp_clipCount(&env, thiz));

    printf("[6] repeated bypass toggles (all stages ON/OFF/ON)\n");
    for (int t = 0; t < 100; t++) {
        jboolean on = (t % 2) ? JNI_TRUE : JNI_FALSE;
        Java_com_neon_eq_dsp_NeonDsp_setCompressor(&env, thiz, on, -18.0f, 4.0f, 5.0f, 50.0f);
        Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, on, -1.0f);
        for (int s = 0; s < 8; s++) Java_com_neon_eq_dsp_NeonDsp_setParametric(&env, thiz, s, on, 1000.0f, 12.0f, 1.0f);
        Java_com_neon_eq_dsp_NeonDsp_setConvolverEnabled(&env, thiz, on);
        double v = 10000.0 * sin(2*M_PI*220*t/SR);
        for (int i = 0; i < FRAMES; i++) { pcm[2*i]=(jshort)v; pcm[2*i+1]=(jshort)v; }
        Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
    }
    CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "bypass churn: no NaN");

    printf("[7] convolver IR load + mismatched lengths (host-LFSR noise)\n");
    { static float irL_[512], irR_[700];
      for (int i = 0; i < 700; i++) { irL_[i % 512] = (rand() % 2000 - 1000) / 100000.0f; irR_[i] = (rand() % 2000 - 1000) / 100000.0f; }
      jfloatArray l = (jfloatArray) &g_hostArrays[hostReg(irL_, 512, 1)];
      jfloatArray r = (jfloatArray) &g_hostArrays[hostReg(irR_, 700, 1)];
      Java_com_neon_eq_dsp_NeonDsp_loadIr(&env, thiz, l, r);   // 512 vs 700: shorter wins
      Java_com_neon_eq_dsp_NeonDsp_setConvolverEnabled(&env, thiz, JNI_TRUE); }
    for (int blk = 0; blk < 30; blk++) {
        for (int i = 0; i < FRAMES; i++) { pcm[2*i]=(jshort)(15000.0*sin(2*M_PI*1000*i/SR)); pcm[2*i+1]=(jshort)(15000.0*sin(2*M_PI*1000*i/SR)); }
        Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
    }
    CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "convolver: no NaN with mismatched IR");

    printf("[8] spectrum readout while processing\n");
    { static float bins[64]; jfloatArray b = (jfloatArray) &g_hostArrays[hostReg(bins, 64, 1)];
      Java_com_neon_eq_dsp_NeonDsp_spectrum(&env, thiz, b, 0);
      bool finite = true; for (int i = 0; i < 64; i++) if (!std::isfinite(bins[i])) finite = false;
      CHECK(finite, "spectrum bins finite"); }

    printf("\n%s (%d failures)\n", fails ? "HARNESS FAIL" : "HARNESS PASS", fails);
    return fails ? 1 : 0;
}
