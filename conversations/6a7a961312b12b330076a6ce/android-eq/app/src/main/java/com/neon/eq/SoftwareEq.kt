package com.neon.eq

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Build #106: in-app software EQ — the one audio path no OEM can block.
 *
 * The Vivo Y21 (V2553) proved some firmware blocks EVERY engine-level
 * route: Equalizer refused (Error -3), BassBoost refused, impl-UUID refused,
 * even the attached LoudnessEnhancer is audibly bypassed. The only EQ that can
 * ever work on such a device is one applied to the PCM inside our own process
 * before the platform ever sees it.
 *
 * Zero external dependencies: MediaCodec decodes, these RBJ peaking biquads
 * process the PCM, AudioTrack plays it. The firmware audio policy is never
 * consulted — no library can drift out from under this pipeline.
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
 * The software EQ core. Gains mirror the locked 10-band UI (31Hz-16kHz);
 * preamp mirrors the loudness dial. All setters are safe to call from any
 * thread at any time — the decode thread always sees a consistent curve.
 */
class SoftwareEq {
    companion object {
        val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    }

    @Volatile private var gainsDb = FloatArray(10)
    @Volatile private var preampDb = 0f
    @Volatile private var sampleRate = 44100
    @Volatile private var channels = 2
    // 2 channel chains x 10 bands — matches the locked 10-band UI exactly
    private val chains = Array(2) { Array(10) { BiquadBand() } }
    private val scratch = ShortArray(16384)

    fun setGains(g: FloatArray) {
        val copy = FloatArray(10)
        for (i in 0 until 10) copy[i] = g.getOrElse(i) { 0f }
        gainsDb = copy
        reconfigure()
    }

    fun setPreamp(db: Float) { preampDb = db }

    fun configure(sr: Int, ch: Int) {
        sampleRate = sr
        channels = ch.coerceIn(1, 2)
        reconfigure()
    }

    private fun reconfigure() {
        val g = gainsDb
        val sr = sampleRate
        for (c in 0 until 2) for (i in 0 until 10)
            chains[c][i].setPeaking(sr, FREQS[i], g[i], 1.1f)
    }

    val active: Boolean
        get() {
            val g = gainsDb
            return g.any { it != 0f } || preampDb != 0f
        }

    /** Applies the curve to decoded PCM and writes it out, blocking. */
    fun processAndWrite(sh: ShortBuffer, out: AudioTrack) {
        if (!active) {
            out.write(sh, sh.remaining())
            return
        }
        val ch = channels
        val pre = 10f.pow(preampDb / 20f)
        var w = 0
        while (sh.hasRemaining()) {
            val c = chains[(w % ch).coerceAtMost(1)]
            var s = (sh.get() / 32768f) * pre
            for (i in 0 until 10) s = c[i].process(s)
            scratch[w++] = (s * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            if (w == scratch.size) {
                out.write(scratch, 0, w)
                w = 0
            }
        }
        if (w > 0) out.write(scratch, 0, w)
    }
}

/**
 * Framework-only player: MediaExtractor -> MediaCodec -> software EQ ->
 * AudioTrack. One decode thread per track; every layer is wrapped in
 * Throwable catches so no codec quirk can ever crash the app.
 */
class SoftEqPlayer(private val eq: SoftwareEq) {
    private var thread: Thread? = null
    @Volatile private var requestStop = false
    @Volatile private var pauseReq = false

    val isRunning: Boolean get() = thread?.isAlive == true
    fun isPaused(): Boolean = pauseReq

    fun play(context: Context, uri: Uri) {
        stop()
        requestStop = false
        pauseReq = false
        thread = Thread { decodeLoop(context.applicationContext, uri) }.apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    fun togglePause() { pauseReq = !pauseReq }

    fun stop() {
        requestStop = true
        pauseReq = false
        thread?.let { t -> try { t.join(600) } catch (_: Throwable) {} }
        thread = null
    }

    @Suppress("DEPRECATION")
    private fun decodeLoop(context: Context, uri: Uri) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            var trackIdx = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i
                    break
                }
            }
            if (trackIdx < 0) return
            extractor.selectTrack(trackIdx)
            val fmt = extractor.getTrackFormat(trackIdx)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return
            val sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            eq.configure(sampleRate, channels)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = maxOf(minBuf * 2, 16384)
            track = AudioTrack(AudioManager.STREAM_MUSIC, chMask, AudioFormat.ENCODING_PCM_16BIT, bufBytes, AudioTrack.MODE_STREAM)
            track.play()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (!requestStop) {
                if (pauseReq) {
                    track.pause()
                    while (pauseReq && !requestStop) try { Thread.sleep(60) } catch (_: Throwable) {}
                    if (!requestStop) track.play()
                }
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(15_000)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)!!
                        ib.clear()
                        val n = extractor.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                } else {
                    // Input exhausted — drain whatever the codec still holds.
                }
                var outIdx = codec.dequeueOutputBuffer(info, if (inputDone) 15_000 else 0)
                while (outIdx >= 0 && !requestStop) {
                    val ob = codec.getOutputBuffer(outIdx)!!
                    eq.processAndWrite(ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(), track)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) requestStop = true
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
                if (inputDone && outIdx != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && outIdx < 0 && requestStop) break
            }
        } catch (_: Throwable) {
            // Any codec quirk ends the track quietly — never the app.
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }
}
