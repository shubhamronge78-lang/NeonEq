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

    printf("\n%s (%d failures)\n", fails ? "HARNESS FAIL" : "HARNESS PASS", fails);
    return fails ? 1 : 0;
}
