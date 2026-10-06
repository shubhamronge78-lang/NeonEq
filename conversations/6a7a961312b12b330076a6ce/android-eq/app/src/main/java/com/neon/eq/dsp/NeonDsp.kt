package com.neon.eq.dsp

/**
 * Build #116: JNI bridge to the native NeonDsp engine (cpp/neondsp.cpp).
 *
 * The native chain: preamp → parametric EQ (8 slots) → graphic EQ (10/15/31
 * bands) → bass/treble shelves → compressor → stereo width/balance/swap/mono
 * → convolver (optional small IR) → output limiter → clip counter.
 *
 * Used by the playback-capture pipeline; the in-app player stays on the
 * proven Kotlin chain until it migrates onto this core.
 */
object NeonDsp {
    @Volatile var loadError: String? = null
        private set
    val available: Boolean get() = loadError == null

    init {
        loadError = try {
            System.loadLibrary("neondsp")
            null
        } catch (t: Throwable) { t.message ?: t.toString() }
    }

    external fun init(sampleRate: Int, bands: Int)
    external fun setPreamp(db: Float)
    external fun setGraphicGains(gains: FloatArray)
    external fun setParametric(slot: Int, on: Boolean, freq: Float, gainDb: Float, q: Float)
    external fun setShelves(bassDb: Float, trebleDb: Float)
    external fun setCompressor(on: Boolean, threshDb: Float, ratio: Float, atkMs: Float, relMs: Float)
    external fun setStereo(width: Float, balance: Float, swap: Boolean, mono: Boolean)
    external fun setLimiter(on: Boolean, threshDb: Float)
    external fun setConvolverEnabled(on: Boolean)
    external fun loadIr(left: FloatArray, right: FloatArray)
    external fun process(buffer: ShortArray, frames: Int)
    external fun clipCount(): Long
    external fun nanCount(): Long
    // Build #119: signal-path counters + measured DSP meters (0..1000 milli-units)
    external fun jniFrames(): Long
    external fun inRmsMs(): Int
    external fun inPeakMs(): Int
    external fun outRmsMs(): Int
    external fun outPeakMs(): Int
    // Build #121: per-channel meters + spectrum analyzer (analysis runs on caller thread)
    external fun inLRmsMs(): Int
    external fun inRRmsMs(): Int
    external fun inLPkMs(): Int
    external fun inRPkMs(): Int
    external fun outLRmsMs(): Int
    external fun outRRmsMs(): Int
    external fun outLPkMs(): Int
    external fun outRPkMs(): Int
    /** Fills [bins] with normalized log-frequency magnitudes (0..1). UI thread only.
     *  mode: 0 pre L+R, 1 pre L, 2 pre R, 3 post L+R, 4 post L, 5 post R. */
    external fun spectrum(bins: FloatArray, mode: Int)
    external fun processedFrames(): Long
    external fun resetStats()
    /** v146 limiter telemetry: current gain reduction, 0..1000 units (0..-100 dB). */
    external fun limiterGrMs(): Int
    /** v146: true while the limiter is actively reducing gain. */
    external fun limiterActive(): Boolean
    /** v146 compressor gain reduction, 0..1000 units (0..-100 dB). */
    external fun compressorGrMs(): Int
    /** v146: last process() body duration in microseconds (pure DSP time, no I/O). */
    external fun dspProcUs(): Long
    /** v146: monotonic committed-configuration version (diagnostic). */
    external fun paramVersion(): Long
}
