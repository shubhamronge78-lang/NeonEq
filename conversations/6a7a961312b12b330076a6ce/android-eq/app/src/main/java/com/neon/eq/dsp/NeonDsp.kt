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
    external fun processedFrames(): Long
    external fun resetStats()
}
