package com.neon.eq.dsp

/**
 * Build #117: the common pipeline contract. Both NeonEQ audio paths
 * eventually converge on this shape:
 *
 *   AudioInput (AudioRecord / MediaCodec / TonePlayer)
 *        → DSP Engine (native NeonDsp)
 *        → AudioOutput (AudioTrack)
 *
 * The capture pipeline already runs this shape end-to-end. The PLAYER keeps
 * its proven Kotlin DSP chain in v117 and migrates onto AudioOutputSink in a
 * later build — no behavior change for the player until then.
 */
interface AudioInput {
    val sampleRate: Int
    fun start()
    fun stop()
}

interface AudioOutputSink {
    val sampleRate: Int
    fun writePcm(buffer: ShortArray, shorts: Int)
    fun close()
}
