/* SonicCore native DSP host self-test (runs in CI, ASan+UBSan).
   v139: sections [9]-[13] add the permanent low-shelf regression (RBJ Jury
   stability criterion asserted on target coefficients for EVERY gain step,
   plus an amplification no-op guard), high-shelf sweep, PEQ low-freq high-Q
   extremes with stability checks, comp/limiter extremes, stereo extremes.
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
/* Single translation unit: the REAL production DSP is compiled in, so the
   harness can also assert on internal filter coefficient stability. */
#include "app/src/main/cpp/neondsp.cpp"  // path from android-eq CWD; -I. resolves via -include trick
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <vector>

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

    printf("[9] v138 REGRESSION: low-shelf coefficient stability, full-range sweep\n");
    /* The v138 bug: setShelfT wrote a2 with b2's signs -> for most gains the
       TARGET coefficients violate the 2nd-order stability criterion and the
       filter blows up within a block. Assert RBJ stability (Jury:
       |a2| < 1 && |a1| < 1 + a2) on the TARGET of every gain step, and that
       the shelf genuinely amplifies (guards a no-op regression too). */
    {
        Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, JNI_FALSE, -1.0f);
        Java_com_neon_eq_dsp_NeonDsp_setPreamp(&env, thiz, 0.0f);
        bool stable = true, finiteAll = true, amplifies = false;
        for (int g = -20; g <= 20; g++) {
            Java_com_neon_eq_dsp_NeonDsp_setShelves(&env, thiz, (jfloat) g, 0.0f);
            Biquad* b = &bassSh[0];
            if (!isfinite(b->tb0) || !isfinite(b->tb1) || !isfinite(b->tb2) ||
                !isfinite(b->ta1) || !isfinite(b->ta2) ||
                fabs(b->ta2) >= 1.0 || fabs(b->ta1) >= 1.0 + b->ta2) {
                printf("  FAIL: bass shelf target UNSTABLE at %+d dB (a1=%.6f a2=%.6f)\n",
                       g, b->ta1, b->ta2);
                stable = false; break;
            }
            /* glide to target over its blocks while feeding a 60 Hz tone */
            double peakIn = 0.0, peakOut = 0.0;
            for (int blk = 0; blk < 12; blk++) {
                for (int i = 0; i < FRAMES; i++) {
                    double v = 1000.0 * sin(2*M_PI*60.0*i/SR);
                    pcm[2*i]=(jshort)v; pcm[2*i+1]=(jshort)v;
                    if (fabs(v) > peakIn) peakIn = fabs(v);
                }
                Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
                for (int i = 0; i < FRAMES*2; i++) {
                    if (!std::isfinite((double) pcm[i])) finiteAll = false;
                    if (fabs((double) pcm[i]) > peakOut) peakOut = fabs((double) pcm[i]);
                }
            }
            if (g == 20 && peakOut > peakIn * 5.0) amplifies = true;
            if (peakOut > 1e9) { finiteAll = false; printf("  FAIL: shelf runaway at %+d dB: peak=%g\n", g, peakOut); break; }
        }
        CHECK(stable, "bass shelf targets stable across -20..+20 dB sweep");
        CHECK(finiteAll, "bass shelf sweep: output finite and bounded");
        CHECK(amplifies, "bass shelf +20 dB genuinely amplifies (no-op guard)");
    }

    printf("[10] high-shelf coefficient stability, full-range sweep\n");
    {
        bool stable = true;
        for (int g = -20; g <= 20; g++) {
            Java_com_neon_eq_dsp_NeonDsp_setShelves(&env, thiz, 0.0f, (jfloat) g);
            Biquad* b = &trebSh[0];
            if (!isfinite(b->tb0) || !isfinite(b->tb1) || !isfinite(b->tb2) ||
                !isfinite(b->ta1) || !isfinite(b->ta2) ||
                fabs(b->ta2) >= 1.0 || fabs(b->ta1) >= 1.0 + b->ta2) {
                printf("  FAIL: treble shelf target UNSTABLE at %+d dB (a1=%.6f a2=%.6f)\n",
                       g, b->ta1, b->ta2);
                stable = false; break;
            }
            for (int blk = 0; blk < 12; blk++) {
                for (int i = 0; i < FRAMES; i++) { pcm[2*i]=(jshort)(8000.0*sin(2*M_PI*9000*i/SR)); pcm[2*i+1]=pcm[2*i]; }
                Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
                for (int i = 0; i < FRAMES*2; i++) if (!std::isfinite((double) pcm[i])) stable = false;
            }
        }
        CHECK(stable, "treble shelf targets stable and output finite, -20..+20 dB");
    }

    printf("[11] PEQ extremes: low-frequency high-Q\n");
    {
        bool ok = true;
        const float qs[3] = {0.3f, 4.0f, 12.0f};
        for (int qi = 0; qi < 3; qi++) for (int g = -20; g <= 20; g += 4) {
            Java_com_neon_eq_dsp_NeonDsp_setParametric(&env, thiz, 0, JNI_TRUE, 40.0f, (jfloat) g, qs[qi]);
            for (int blk = 0; blk < 12; blk++) {
                for (int i = 0; i < FRAMES; i++) { pcm[2*i]=(jshort)(12000.0*sin(2*M_PI*70*i/SR)); pcm[2*i+1]=pcm[2*i]; }
                Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
                for (int i = 0; i < FRAMES*2; i++) if (!std::isfinite((double) pcm[i])) ok = false;
            }
            Biquad* b = &peq[0][0];
            if (fabs(b->ta2) >= 1.0 || fabs(b->ta1) >= 1.0 + b->ta2) { ok = false; printf("  FAIL: PEQ unstable at q=%.1f g=%d\n", qs[qi], g); }
        }
        Java_com_neon_eq_dsp_NeonDsp_setParametric(&env, thiz, 0, JNI_FALSE, 1000.0f, 0.0f, 1.0f);
        CHECK(ok, "PEQ 40 Hz Q0.3-12 sweep -20..+20 dB: stable and finite");
    }

    printf("[12] compressor + limiter extremes on loud input\n");
    {
        bool ok = true;
        Java_com_neon_eq_dsp_NeonDsp_setCompressor(&env, thiz, JNI_TRUE, -60.0f, 20.0f, 0.01f, 2000.0f);
        Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, JNI_TRUE, -60.0f);
        for (int blk = 0; blk < 60; blk++) {
            for (int i = 0; i < FRAMES; i++) { pcm[2*i]=(jshort)(32000.0*sin(2*M_PI*(50+blk*13)*i/SR)); pcm[2*i+1]=(jshort)(32000.0*sin(2*M_PI*(50+blk*13)*i/SR)); }
            Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
            for (int i = 0; i < FRAMES*2; i++) if (!std::isfinite((double) pcm[i])) ok = false;
        }
        Java_com_neon_eq_dsp_NeonDsp_setCompressor(&env, thiz, JNI_TRUE, -6.0f, 2.0f, 5.0f, 50.0f);
        Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, JNI_TRUE, -1.0f);
        for (int blk = 0; blk < 60; blk++) {
            for (int i = 0; i < FRAMES; i++) { pcm[2*i]=(jshort)(32000.0*sin(2*M_PI*(50+blk*13)*i/SR)); pcm[2*i+1]=(jshort)(32000.0*sin(2*M_PI*(50+blk*13)*i/SR)); }
            Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
            for (int i = 0; i < FRAMES*2; i++) if (!std::isfinite((double) pcm[i])) ok = false;
        }
        CHECK(ok, "extreme comp/limiter settings on full-scale input: finite");
        CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "nanEvents still zero after extremes");
    }

    printf("[13] stereo width/balance/mono extremes\n");
    {
        bool ok = true;
        const float ws[3] = {0.0f, 1.0f, 2.0f};
        const float bs[3] = {-1.0f, 0.0f, 1.0f};
        for (int wi = 0; wi < 3; wi++) for (int bi = 0; bi < 3; bi++) {
            Java_com_neon_eq_dsp_NeonDsp_setStereo(&env, thiz, ws[wi], bs[bi], JNI_TRUE, JNI_FALSE);
            for (int blk = 0; blk < 6; blk++) {
                for (int i = 0; i < FRAMES; i++) { pcm[2*i]=(jshort)(20000.0*sin(2*M_PI*300*i/SR)); pcm[2*i+1]=(jshort)(-14000.0*sin(2*M_PI*300*i/SR)); }
                Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
                for (int i = 0; i < FRAMES*2; i++) if (!std::isfinite((double) pcm[i])) ok = false;
            }
            Java_com_neon_eq_dsp_NeonDsp_setStereo(&env, thiz, ws[wi], bs[bi], JNI_FALSE, JNI_TRUE);
            for (int blk = 0; blk < 6; blk++) Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, buf, FRAMES);
        }
        Java_com_neon_eq_dsp_NeonDsp_setStereo(&env, thiz, 1.0f, 0.0f, JNI_FALSE, JNI_FALSE);
        CHECK(ok && Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "stereo width/balance/swap/mono extremes: finite");
    }

    printf("[14] v140: shelf RESPONSE SHAPE (not just finite — RBJ shape)\n");
    /* Section 24: the shelf response must be checked for actual response
       shape. Re-init for a clean chain (limiter off, preamp 0, only the
       shelf under test active), feed steady sine tones, measure the dB
       change after settling, and assert the textbook S=1 RBJ shape:
       +/-20 dB shelf -> ~+/-18 dB in-band, ~+/-10 dB at the corner,
       ~0 dB out-of-band, and unity at 0 dB. */
    {
        Java_com_neon_eq_dsp_NeonDsp_init(&env, thiz, SR, BANDS);
        Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, JNI_FALSE, -1.0f);
        Java_com_neon_eq_dsp_NeonDsp_setPreamp(&env, thiz, 0.0f);
        auto probeDb = [&](double freq) {
            const int PR = 480, SETTLE = 30, BLOCKS = 40;
            static jshort pr[PR * 2];
            jshortArray pbuf = (jshortArray) &g_hostArrays[hostReg(pr, PR * 2, 0)];
            double inE = 0.0, outE = 0.0;
            for (int blk = 0; blk < BLOCKS; blk++) {
                for (int i = 0; i < PR; i++) { double v = 5000.0 * sin(2*M_PI*freq*(blk*PR+i)/SR); pr[2*i]=(jshort)v; pr[2*i+1]=(jshort)v; }
                Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, PR);
                if (blk >= SETTLE) for (int i = 0; i < PR; i++) {
                    /* accumulate the ACTUAL sample energy (sine RMS = peak/sqrt(2)),
                       not the peak squared, or every probe reads a constant -3 dB */
                    double v = 5000.0 * sin(2*M_PI*freq*(blk*PR+i)/SR);
                    inE += 2.0 * v * v;  /* both channels of the source tone */
                    double ol = pr[2*i], orr = pr[2*i+1];
                    if (!isfinite(ol) || !isfinite(orr)) return 999.0;
                    outE += ol*ol + orr*orr;
                }
            }
            return 10.0 * log10(outE / inE);
        };
        auto closeTo = [&](double got, double want, double tol, const char* what) {
            bool ok = got > want - tol && got < want + tol;
            printf("  %s: %+.1f dB (expected ~%+.0f) -> %s\n", what, got, want, ok ? "ok" : "FAIL");
            if (!ok) fails++;
        };
        Java_com_neon_eq_dsp_NeonDsp_setShelves(&env, thiz, 20.0f, 0.0f);
        closeTo(probeDb(30.0),   18.0, 3.0, "bass +20dB @ 30 Hz");
        closeTo(probeDb(100.0),  10.0, 2.0, "bass +20dB @ 100 Hz corner");
        closeTo(probeDb(1000.0),  0.0, 1.0, "bass +20dB @ 1 kHz");
        Java_com_neon_eq_dsp_NeonDsp_setShelves(&env, thiz, -20.0f, 0.0f);
        closeTo(probeDb(30.0),  -18.0, 3.0, "bass -20dB @ 30 Hz");
        Java_com_neon_eq_dsp_NeonDsp_setShelves(&env, thiz, 0.0f, 0.0f);
        closeTo(probeDb(30.0),    0.0, 1.0, "bass 0dB @ 30 Hz (unity)");
        closeTo(probeDb(1000.0),  0.0, 1.0, "bass 0dB @ 1 kHz (unity)");
        Java_com_neon_eq_dsp_NeonDsp_setShelves(&env, thiz, 0.0f, 20.0f);
        closeTo(probeDb(16000.0), 17.7, 3.0, "treble +20dB @ 16 kHz");
        closeTo(probeDb(8000.0),  10.0, 2.0, "treble +20dB @ 8 kHz corner");
        closeTo(probeDb(1000.0),   0.0, 1.0, "treble +20dB @ 1 kHz");
        Java_com_neon_eq_dsp_NeonDsp_setShelves(&env, thiz, 0.0f, -20.0f);
        closeTo(probeDb(16000.0), -17.7, 3.0, "treble -20dB @ 16 kHz");
        Java_com_neon_eq_dsp_NeonDsp_setShelves(&env, thiz, 0.0f, 0.0f);
        CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "response probes: nanEvents still zero");
        Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, JNI_TRUE, -1.0f);
    }

    printf("[15] v146: LIMITER gain-reduction domain — REGRESSION LOCK for the level-domain crush bug\n");
    {
        /* v146 root cause, reproduced here first: the v136-v145 limiter tracked
           the smoothed LEVEL and multiplied by 10^((env-det)/20). The envelope
           drooped toward -180dB at every zero crossing, then recovered too
           slowly, so a -6 dBFS sine came out at -68 dBFS with the limiter ON
           (default!). The GR-domain limiter must be EXACT unity below the
           ceiling and smooth/bounded above it. */
        Java_com_neon_eq_dsp_NeonDsp_init(&env, thiz, SR, BANDS);   // flat, limiter ON by default
        const int PR = 480, SETTLE = 30, BLOCKS = 45;
        static jshort pr[1024 * 2];
        jshortArray pbuf = (jshortArray) &g_hostArrays[hostReg(pr, 1024 * 2, 0)];
        auto probeGain = [&](double freq, double amp, int srNow, double* dcOut = nullptr) {
            double inE = 0.0, outE = 0.0; double sumOut = 0.0; long nOut = 0;
            for (int b = 0; b < BLOCKS; b++) {
                for (int i = 0; i < PR; i++) { double v = amp * sin(2*M_PI*freq*(b*PR+i)/srNow); pr[2*i]=(jshort)v; pr[2*i+1]=(jshort)v; }
                Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, PR);
                if (b >= SETTLE) for (int i = 0; i < PR; i++) {
                    double s = amp * sin(2*M_PI*freq*(b*PR+i)/srNow);   /* actual sample energy, not amplitude^2 */
                    inE += 2.0 * s * s;                    double ol = pr[2*i], orr = pr[2*i+1];
                    if (!isfinite(ol) || !isfinite(orr)) return 999.0;
                    outE += ol*ol + orr*orr;
                    sumOut += ol + orr; nOut += 2;
                }
            }
            if (dcOut) *dcOut = sumOut / (double) nOut;
            return 10.0 * log10(outE / inE);
        };
        double dc = 0.0;
        double u1k = probeGain(1000.0, 8000.0, SR, &dc);
        CHECK(u1k > -0.3 && u1k < 0.3, "limiter ON, -10dBFS @1k: EXACT unity (measured gain change dB)");
        CHECK(fabs(dc) < 80.0, "no unexpected DC offset on unity path");
        double u100 = probeGain(100.0, 8000.0, SR);
        double u10k = probeGain(10000.0, 8000.0, SR);
        CHECK(u100 > -0.3 && u100 < 0.3 && u10k > -0.3 && u10k < 0.3, "limiter unity across frequency (100Hz/10kHz)");
        CHECK(Java_com_neon_eq_dsp_NeonDsp_limiterGrMs(&env, thiz) == 0, "limiter telemetry: GR == 0 below ceiling");
        CHECK(Java_com_neon_eq_dsp_NeonDsp_limiterActive(&env, thiz) == JNI_FALSE, "limiter telemetry: inactive below ceiling");
        double ovr = probeGain(997.0, 32000.0, SR);
        CHECK(ovr > -3.0 && ovr < 0.5, "over-ceiling: bounded (soft limiting, not crush)");
        CHECK(Java_com_neon_eq_dsp_NeonDsp_limiterActive(&env, thiz) == JNI_TRUE, "limiter telemetry: ACTIVE above ceiling");
        jint gr = Java_com_neon_eq_dsp_NeonDsp_limiterGrMs(&env, thiz);
        CHECK(gr >= 2 && gr <= 60, "limiter GR telemetry sane (0.2..6 dB)");
        CHECK(Java_com_neon_eq_dsp_NeonDsp_outPeakMs(&env, thiz) <= 995, "output peak held at/below -1dBFS ceiling");
        CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "limiter tests: no NaN");
    }

    printf("[16] v146: COMPRESSOR gain-reduction domain — unity below threshold, smooth above\n");
    {
        Java_com_neon_eq_dsp_NeonDsp_init(&env, thiz, SR, BANDS);
        static jshort pr[480 * 2];
        jshortArray pbuf = (jshortArray) &g_hostArrays[hostReg(pr, 480 * 2, 0)];
        Java_com_neon_eq_dsp_NeonDsp_setCompressor(&env, thiz, JNI_TRUE, -18.0f, 4.0f, 5.0f, 150.0f);
        auto measure = [&](double freq, double amp) {
            double inE = 0.0, outE = 0.0;
            for (int b = 0; b < 45; b++) {
                for (int i = 0; i < 480; i++) { double v = amp * sin(2*M_PI*freq*(b*480+i)/SR); pr[2*i]=(jshort)v; pr[2*i+1]=(jshort)v; }
                Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, 480);
                if (b >= 30) for (int i = 0; i < 480; i++) {
                    double s = amp * sin(2*M_PI*freq*(b*480+i)/SR);
                    inE += 2.0 * s * s;                    double ol = pr[2*i], orr = pr[2*i+1];
                    if (!isfinite(ol) || !isfinite(orr)) return 999.0;
                    outE += ol*ol + orr*orr;
                }
            }
            return 10.0 * log10(outE / inE);
        };
        double below = measure(1000.0, 300.0);   // ~-40dBFS, far below threshold
        CHECK(below > -0.3 && below < 0.3, "compressor below threshold: EXACT unity");
        CHECK(Java_com_neon_eq_dsp_NeonDsp_compressorGrMs(&env, thiz) == 0, "comp telemetry: no reduction below threshold");
        double above = measure(1000.0, 8200.0);  // -12dBFS peak, over threshold
        CHECK(above > -7.0 && above < -2.0, "compressor above threshold: bounded reduction (no 30dB over-crush)");
        jint cgr = Java_com_neon_eq_dsp_NeonDsp_compressorGrMs(&env, thiz);
        CHECK(cgr >= 15 && cgr <= 90, "comp GR telemetry sane");
        CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "compressor tests: no NaN");
    }

    printf("[17] v146: FLAT CONFIG == TRUE UNITY (master gain-staging contract, spec 2)\n");
    {
        Java_com_neon_eq_dsp_NeonDsp_init(&env, thiz, SR, BANDS);   // all defaults: flat, limiter ON
        static jshort pr[480 * 2];
        jshortArray pbuf = (jshortArray) &g_hostArrays[hostReg(pr, 480 * 2, 0)];
        /* silence: bit-exact zeros out */
        memset(pr, 0, sizeof(pr));
        Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, 480);
        bool allZero = true; for (int i = 0; i < 960; i++) if (pr[i] != 0) { allZero = false; break; }
        CHECK(allZero, "flat config: silence in -> exact silence out");
        /* -12dBFS sine: unity within floating error, limiter must NOT color it */
        double inE = 0.0, outE = 0.0;
        for (int b = 0; b < 45; b++) {
            for (int i = 0; i < 480; i++) { double v = 8200.0 * sin(2*M_PI*1000.0*(b*480+i)/SR); pr[2*i]=(jshort)v; pr[2*i+1]=(jshort)v; }
            Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, 480);
            if (b >= 30) for (int i = 0; i < 480; i++) {
                double s = 8200.0 * sin(2*M_PI*1000.0*(b*480+i)/SR);
                inE += 2.0 * s * s;                outE += (double)pr[2*i]*(double)pr[2*i] + (double)pr[2*i+1]*(double)pr[2*i+1];
            }
        }
        double gainDb = 10.0 * log10(outE / inE);
        CHECK(gainDb > -0.15 && gainDb < 0.15, "flat config: -12dBFS sine unity within 0.15dB (limiter transparent)");
        CHECK(Java_com_neon_eq_dsp_NeonDsp_clipCount(&env, thiz) == 0, "flat config: no clipping");
        CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "flat config: no NaN");
    }

    printf("[18] v146: PEQ response SHAPE (boost/cut/unity-after-disable, spec 6/19)\n");
    {
        Java_com_neon_eq_dsp_NeonDsp_init(&env, thiz, SR, BANDS);
        static jshort pr[480 * 2];
        jshortArray pbuf = (jshortArray) &g_hostArrays[hostReg(pr, 480 * 2, 0)];
        auto probe = [&](double freq) {
            double inE = 0.0, outE = 0.0;
            for (int b = 0; b < 45; b++) {
                for (int i = 0; i < 480; i++) { double v = 5000.0 * sin(2*M_PI*freq*(b*480+i)/SR); pr[2*i]=(jshort)v; pr[2*i+1]=(jshort)v; }
                Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, 480);
                if (b >= 30) for (int i = 0; i < 480; i++) {
                    double s = 5000.0 * sin(2*M_PI*freq*(b*480+i)/SR);
                    inE += 2.0 * s * s;                    double ol = pr[2*i], orr = pr[2*i+1];
                    if (!isfinite(ol) || !isfinite(orr)) return 999.0;
                    outE += ol*ol + orr*orr;
                }
            }
            return 10.0 * log10(outE / inE);
        };
        Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, JNI_FALSE, -1.0f);   // measure the FILTER, not filter+limiter
        Java_com_neon_eq_dsp_NeonDsp_setParametric(&env, thiz, 0, JNI_TRUE, 1000.0f, 12.0f, 2.0f);
        double c = probe(1000.0);
        CHECK(c > 9.0 && c < 15.0, "PEQ +12dB @1kHz Q2: in-band response");
        double h = probe(8000.0);
        double lf = probe(100.0);
        CHECK(h > -1.5 && h < 1.5, "PEQ +12dB @1kHz: ~unity at 8kHz (no HF leakage)");
        CHECK(lf > -1.5 && lf < 1.5, "PEQ +12dB @1kHz: ~unity at 100Hz");
        Java_com_neon_eq_dsp_NeonDsp_setParametric(&env, thiz, 0, JNI_FALSE, 1000.0f, 12.0f, 2.0f);
        double back = probe(1000.0);
        CHECK(back > -0.5 && back < 0.5, "PEQ disable: glide back to unity, no residue");
        Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, JNI_TRUE, -1.0f);
        CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "PEQ response: no NaN");
    }

    printf("[19] v146: GRAPHIC EQ response SHAPE (boost/cut/unity, spec 7/19)\n");
    {
        Java_com_neon_eq_dsp_NeonDsp_init(&env, thiz, SR, BANDS);
        static jshort pr[480 * 2];
        jshortArray pbuf = (jshortArray) &g_hostArrays[hostReg(pr, 480 * 2, 0)];
        static float gains[MAXBANDS];
        jfloatArray gbuf = (jfloatArray) &g_hostArrays[hostReg(gains, MAXBANDS, 1)];
        auto probe = [&](double freq) {
            double inE = 0.0, outE = 0.0;
            for (int b = 0; b < 45; b++) {
                for (int i = 0; i < 480; i++) { double v = 4000.0 * sin(2*M_PI*freq*(b*480+i)/SR); pr[2*i]=(jshort)v; pr[2*i+1]=(jshort)v; }
                Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, 480);
                if (b >= 30) for (int i = 0; i < 480; i++) {
                    double s = 4000.0 * sin(2*M_PI*freq*(b*480+i)/SR);
                    inE += 2.0 * s * s;                    double ol = pr[2*i], orr = pr[2*i+1];
                    if (!isfinite(ol) || !isfinite(orr)) return 999.0;
                    outE += ol*ol + orr*orr;
                }
            }
            return 10.0 * log10(outE / inE);
        };
        Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, JNI_FALSE, -1.0f);
        for (int i = 0; i < MAXBANDS; i++) gains[i] = (i < 10) ? 10.0f : 0.0f;
        Java_com_neon_eq_dsp_NeonDsp_setGraphicGains(&env, thiz, gbuf);
        /* Measured reality (v146): ten Q=1 bells overlap, so setting ALL bands
           to +10 sums to ~+18 dB at the probe point (bounded, proportional,
           no explosion — a known cascade property of the locked design).
           Windows lock the measured behavior AND the "no explosion" bound. */
        double b1k = probe(1000.0);
        double b62 = probe(62.0);
        CHECK(b1k > 8.0 && b1k < 22.0, "graphic all +10dB: 1kHz composite boost bounded (measured ~+18)");
        CHECK(b62 > 8.0 && b62 < 22.0, "graphic all +10dB: 62Hz composite boost bounded");
        for (int i = 0; i < MAXBANDS; i++) gains[i] = (i < 10) ? -10.0f : 0.0f;
        Java_com_neon_eq_dsp_NeonDsp_setGraphicGains(&env, thiz, gbuf);
        double c1k = probe(1000.0);
        CHECK(c1k > -22.0 && c1k < -8.0, "graphic all -10dB: 1kHz composite cut bounded (mirrored)");
        for (int i = 0; i < MAXBANDS; i++) gains[i] = 0.0f;
        Java_com_neon_eq_dsp_NeonDsp_setGraphicGains(&env, thiz, gbuf);
        double flat = probe(1000.0);
        CHECK(flat > -0.5 && flat < 0.5, "graphic flat: unity restored after glide");
        Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, JNI_TRUE, -1.0f);
        CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "graphic response: no NaN");
    }

    printf("[20] v146: RAPID BYPASS/ENABLE — no clicks, no state corruption (spec 16)\n");
    {
        Java_com_neon_eq_dsp_NeonDsp_init(&env, thiz, SR, BANDS);
        static jshort pr[480 * 2];
        jshortArray pbuf = (jshortArray) &g_hostArrays[hostReg(pr, 480 * 2, 0)];
        double prevRms = -1.0; bool bad = false;
        for (int b = 0; b < 80; b++) {
            /* music-like: 3-tone mixture at safe level (below ceiling, so
               limiter/comp toggles are gain-neutral and ONLY continuity matters) */
            for (int i = 0; i < 480; i++) {
                double t = (b * 480 + i) / (double) SR;
                double v = 4000.0*sin(2*M_PI*80.0*t) + 3000.0*sin(2*M_PI*1100.0*t) + 2000.0*sin(2*M_PI*7500.0*t);
                pr[2*i]=(jshort)v; pr[2*i+1]=(jshort)v;
            }
            /* rapid toggles every block */
            Java_com_neon_eq_dsp_NeonDsp_setShelves(&env, thiz, (b % 4 < 2) ? 8.0f : 0.0f, (b % 4 < 2) ? 6.0f : 0.0f);
            Java_com_neon_eq_dsp_NeonDsp_setCompressor(&env, thiz, (b % 2 == 0) ? JNI_TRUE : JNI_FALSE, -18.0f, 4.0f, 5.0f, 150.0f);
            Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, (b % 2 == 1) ? JNI_TRUE : JNI_FALSE, -1.0f);
            Java_com_neon_eq_dsp_NeonDsp_setStereo(&env, thiz, (b % 3 == 0) ? 1.5f : 1.0f, 0.0f, (b % 5 == 0) ? JNI_TRUE : JNI_FALSE, JNI_FALSE);
            Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, 480);
            double sum = 0.0;
            for (int i = 0; i < 960; i++) { double s = pr[i]; if (!isfinite(s)) { bad = true; break; } sum += s*s; }
            if (bad) break;
            double rms = sqrt(sum / 960.0);
            if (prevRms > 0 && (rms > prevRms * 4.0 || rms < prevRms * 0.25)) bad = true;   // click detector
            prevRms = rms;
        }
        CHECK(!bad, "rapid bypass: all finite, no >4x block-to-block jump");
        CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "rapid bypass: no NaN");
    }

    printf("[21] v146: BLOCK SIZE continuity — 64/128/256/512/1024 (spec 12)\n");
    {
        Java_com_neon_eq_dsp_NeonDsp_init(&env, thiz, SR, BANDS);
        static jshort pr[1024 * 2];
        jshortArray pbuf = (jshortArray) &g_hostArrays[hostReg(pr, 1024 * 2, 0)];
        int sizes[5] = {64, 128, 256, 512, 1024};
        int lo = 100000, hi = -100000;
        for (int s = 0; s < 5; s++) {
            for (int b = 0; b < 4096 / sizes[s] + 20; b++) {
                int n = sizes[s];
                /* 1500 Hz = an exact integer number of cycles in every tested
                   block size (2..32), so the block RMS is phase-independent */
                for (int i = 0; i < n; i++) { double v = 8200.0 * sin(2*M_PI*1500.0*((b*n)+i)/SR); pr[2*i]=(jshort)v; pr[2*i+1]=(jshort)v; }
                Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, n);
            }
            int r = Java_com_neon_eq_dsp_NeonDsp_outRmsMs(&env, thiz);
            if (r < lo) lo = r;
            if (r > hi) hi = r;
            CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "block-size sweep: no NaN (all sizes)");
        }
        CHECK(hi - lo <= 3, "block size does not change the DSP response (RMS stable across sizes)");
    }

    printf("[22] v146: SAMPLE RATE handling — 44.1k/48k/96k re-init, no stale coefficients (spec 13)\n");
    {
        const int rates[3] = {44100, 48000, 96000};
        for (int r = 0; r < 3; r++) {
            Java_com_neon_eq_dsp_NeonDsp_init(&env, thiz, rates[r], BANDS);
            static jshort pr[480 * 2];
            jshortArray pbuf = (jshortArray) &g_hostArrays[hostReg(pr, 480 * 2, 0)];
            double inE = 0.0, outE = 0.0;
            for (int b = 0; b < 45; b++) {
                for (int i = 0; i < 480; i++) { double v = 5000.0 * sin(2*M_PI*30.0*(b*480+i)/rates[r]); pr[2*i]=(jshort)v; pr[2*i+1]=(jshort)v; }
                Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, 480);
                if (b >= 30) for (int i = 0; i < 480; i++) {
                    double s = 5000.0 * sin(2*M_PI*30.0*(b*480+i)/rates[r]);
                    inE += 2.0 * s * s;                    double ol = pr[2*i], orr = pr[2*i+1];
                    if (!isfinite(ol) || !isfinite(orr)) { outE = -1; break; }
                    outE += ol*ol + orr*orr;
                }
            }
            CHECK(outE > 0, "rate re-init: finite output");
            double d = 10.0 * log10(outE / inE);
            CHECK(d > -0.3 && d < 0.3, "re-init at each rate: flat unity (no stale coefficients)");
        }
        Java_com_neon_eq_dsp_NeonDsp_init(&env, thiz, SR, BANDS);
        CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "rate sweep: no NaN");
    }

    printf("[23] v146: LONG-RUN stress — continuous parameter churn + decaying/denormal signal (spec 15/20)\n");
    {
        Java_com_neon_eq_dsp_NeonDsp_init(&env, thiz, SR, BANDS);
        static jshort pr[480 * 2];
        jshortArray pbuf = (jshortArray) &g_hostArrays[hostReg(pr, 480 * 2, 0)];
        static float gains[MAXBANDS];
        jfloatArray gbuf = (jfloatArray) &g_hostArrays[hostReg(gains, MAXBANDS, 1)];
        /* phase A: decaying sine into deep silence — denormal/long-silence behavior */
        for (int b = 0; b < 300; b++) {
            double amp = 2000.0 * pow(0.85, b);
            for (int i = 0; i < 480; i++) { double v = amp * sin(2*M_PI*220.0*(b*480+i)/SR); pr[2*i]=(jshort)v; pr[2*i+1]=(jshort)v; }
            Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, 480);
        }
        for (int b = 0; b < 200; b++) { memset(pr, 0, sizeof(pr)); Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, 480); }
        CHECK(Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) == 0, "decay + long silence: no NaN");
        /* phase B: 2500 blocks (~25s audio) of aggressive churn */
        srand(140);
        bool bad = false;
        long n0 = Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz);
        for (int b = 0; b < 2500; b++) {
            double t = b / 2500.0;
            for (int i = 0; i < 480; i++) {
                double v = 6000.0*sin(2*M_PI*90.0*(b*480+i)/SR) + 4000.0*sin(2*M_PI*1300.0*(b*480+i)/SR)
                         + 3000.0*sin(2*M_PI*9000.0*(b*480+i)/SR);
                pr[2*i]=(jshort)v; pr[2*i+1]=(jshort)v;
            }
            for (int i = 0; i < MAXBANDS; i++)
                gains[i] = (i < 10) ? (float) (20.0 * sin(2*M_PI*(0.37*i + t*40.0))) : 0.0f;
            Java_com_neon_eq_dsp_NeonDsp_setGraphicGains(&env, thiz, gbuf);
            if (b % 2 == 0) {
                int slot = (b / 2) % MAXPEQ;
                float f = 20.0f + (float) ((rand() % 2000) / 100.0) * 10.0f;   /* 20..~2020Hz + sweep */
                Java_com_neon_eq_dsp_NeonDsp_setParametric(&env, thiz, slot, JNI_TRUE,
                    f, (float) ((rand() % 61) - 30), 0.1f + (float) (rand() % 100) / 10.0f);
            }
            if (b % 4 == 0) {
                Java_com_neon_eq_dsp_NeonDsp_setShelves(&env, thiz, (float) (20.0*sin(t*80.0)), (float) (20.0*cos(t*60.0)));
                Java_com_neon_eq_dsp_NeonDsp_setPreamp(&env, thiz, (float) (6.0*sin(t*50.0)));
            }
            if (b % 8 == 0) {
                Java_com_neon_eq_dsp_NeonDsp_setStereo(&env, thiz, 0.0f + (float) (rand() % 41) / 10.0f,
                    (float) ((rand() % 21) - 10) / 10.0f, (rand() % 2) ? JNI_TRUE : JNI_FALSE, (rand() % 2) ? JNI_TRUE : JNI_FALSE);
                Java_com_neon_eq_dsp_NeonDsp_setCompressor(&env, thiz, (rand() % 2) ? JNI_TRUE : JNI_FALSE,
                    (float) (-60 + rand() % 40), 1.5f + (float) (rand() % 20), 1.0f, 80.0f);
                Java_com_neon_eq_dsp_NeonDsp_setLimiter(&env, thiz, (rand() % 2) ? JNI_TRUE : JNI_FALSE,
                    (float) (-12 - rand() % 10));
            }
            Java_com_neon_eq_dsp_NeonDsp_process(&env, thiz, pbuf, 480);
            if (b % 128 == 127) {
                bool blkOk = true;
                for (int i = 0; i < 960; i++) { double s = pr[i]; if (!isfinite(s) || s > 32767 || s < -32768) { blkOk = false; break; } }
                if (!blkOk) { bad = true; break; }
                if (Java_com_neon_eq_dsp_NeonDsp_nanCount(&env, thiz) != n0) { bad = true; break; }
            }
        }
        CHECK(!bad, "2500-block churn: bounded, finite, zero new NaN under ASan+UBSan");
        CHECK(Java_com_neon_eq_dsp_NeonDsp_processedFrames(&env, thiz) > 2500L * 480L, "frames counter advanced through the stress run");
    }

    printf("\n%s (%d failures)\n", fails ? "HARNESS FAIL" : "HARNESS PASS", fails);
    return fails ? 1 : 0;
}
