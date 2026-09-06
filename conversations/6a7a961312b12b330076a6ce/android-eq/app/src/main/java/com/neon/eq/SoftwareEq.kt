package com.neon.eq

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Build #105: in-app software EQ — the one audio path no OEM can block.
 *
 * The Vivo Y21 (V2553) proved some firmware blocks EVERY engine-level
 * route: Equalizer refused (Error -3), BassBoost refused, impl-UUID refused,
 * even the attached LoudnessEnhancer is audibly bypassed. The only EQ that
 * can ever work on such a device is one applied to the PCM inside our own
 * process before the platform ever sees it. These RBJ peaking biquads run
 * as an ExoPlayer audio processor: when music plays through NeonEQ's
 * PLAYER, the 10-band curve becomes real, audible EQ on EVERY device — the
 * firmware audio policy is never consulted.
 */

/** One RBJ cookbook peaking-EQ biquad, direct form 1. gainDb 0 = identity. */
class BiquadBand {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f

    fun setPeaking(sr: Int, f0: Float, gainDb: Float, q: Float) {
        if (gainDb == 0f || sr <= 0) {
            b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
            return
        }
        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val w0 = (2.0 * PI * f0 / sr).toFloat()
        val alpha = sin(w0) / (2f * q)
        val cw = cos(w0)
        val a0 = 1f + alpha / a
        b0 = (1f + alpha * a) / a0
        b1 = (-2f * cw) / a0
        b2 = (1f - alpha * a) / a0
        a1 = (-2f * cw) / a0
        a2 = (1f - alpha / a) / a0
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

/** A track from MediaStore. */
data class Track(val uri: Uri, val title: String, val artist: String)

/**
 * The software EQ injected into ExoPlayer's audio sink. Gains mirror the
 * locked 10-band UI (31Hz-16kHz); preamp mirrors the loudness dial so the
 * hero control also works inside the player.
 */
class PlayerEqProcessor : BaseAudioProcessor() {
    companion object {
        val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    }

    @Volatile private var gainsDb = FloatArray(10)
    @Volatile private var preampDb = 0f
    private var sampleRate = 48000
    private var encoding = C.ENCODING_PCM_16BIT
    private var channels = 2
    // 2 channel chains x 10 bands — matches the locked 10-band UI exactly
    private val chains = Array(2) { Array(10) { BiquadBand() } }

    fun setGains(g: FloatArray) {
        val copy = FloatArray(10)
        for (i in 0 until 10) copy[i] = g.getOrElse(i) { 0f }
        gainsDb = copy
        reconfigure()
    }

    fun setPreamp(db: Float) { preampDb = db }

    private fun reconfigure() {
        val g = gainsDb
        for (ch in 0 until 2) for (i in 0 until 10)
            chains[ch][i].setPeaking(sampleRate, FREQS[i], g[i], 1.1f)
    }

    override fun onConfigureInput(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        sampleRate = inputAudioFormat.sampleRate
        reconfigure()
        return inputAudioFormat
    }

    private fun anyGain(): Boolean {
        val g = gainsDb
        return g.any { it != 0f } || preampDb != 0f
    }

    override fun process(input: ByteBuffer, buffered: Boolean): Boolean {
        // Unsupported encodings (e.g. 24-bit): pass through untouched.
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            if (input.hasRemaining()) {
                val out = replaceOutputBuffer(input.remaining())
                out.put(input)
            }
            return true
        }
        // Flat curve: copy through, zero math.
        if (!anyGain()) {
            if (input.hasRemaining()) {
                val out = replaceOutputBuffer(input.remaining())
                out.put(input)
            }
            return true
        }
        val out = replaceOutputBuffer(input.remaining())
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = channels * bytesPerSample
        val pre = 10f.pow(preampDb / 20f)
        while (input.remaining() >= frameBytes) {
            for (ch in 0 until channels) {
                val chain = chains[if (ch < 2) ch else 0]
                if (encoding == C.ENCODING_PCM_FLOAT) {
                    var s = input.float * pre
                    for (i in 0 until 10) s = chain[i].process(s)
                    out.putFloat(s.coerceIn(-1f, 1f))
                } else {
                    var s = (input.short.toInt() / 32768f) * pre
                    for (i in 0 until 10) s = chain[i].process(s)
                    val v = (s * 32767f).toInt().coerceIn(-32768, 32767)
                    out.putShort(v.toShort())
                }
            }
        }
        // Trailing odd bytes: copy through untouched.
        while (input.hasRemaining()) out.put(input.get())
        return true
    }
}
