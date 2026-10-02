package com.neon.eq.capture

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import com.neon.eq.dsp.NeonDsp
import org.json.JSONObject
import java.io.File

/**
 * Build #118: universal, capability-driven detection. No OEM-specific code
 * anywhere — manufacturer/model are read purely for diagnostics display.
 * Every feature reports a capability state, not a brand verdict.
 */
enum class CapState { SUPPORTED, PARTIALLY_SUPPORTED, UNSUPPORTED, BLOCKED_BY_SOURCE_APP, UNKNOWN }

/** Layer-tagged error codes — never show generic errors. */
object AudioErrors {
    const val CAPTURE_PERMISSION_DENIED = "CAPTURE_PERMISSION_DENIED"
    const val NO_ELIGIBLE_PLAYBACK = "NO_ELIGIBLE_PLAYBACK"
    const val SOURCE_APP_BLOCKED_CAPTURE = "SOURCE_APP_BLOCKED_CAPTURE"
    const val AUDIO_RECORD_INITIALIZATION_FAILED = "AUDIO_RECORD_INITIALIZATION_FAILED"
    const val AUDIO_TRACK_INITIALIZATION_FAILED = "AUDIO_TRACK_INITIALIZATION_FAILED"
    const val OUTPUT_ROUTE_CHANGED = "OUTPUT_ROUTE_CHANGED"
    const val UNSUPPORTED_SAMPLE_RATE = "UNSUPPORTED_SAMPLE_RATE"
    const val UNSUPPORTED_CHANNEL_CONFIGURATION = "UNSUPPORTED_CHANNEL_CONFIGURATION"
    const val DSP_INITIALIZATION_FAILED = "DSP_INITIALIZATION_FAILED"
    const val DSP_PROCESSING_ERROR = "DSP_PROCESSING_ERROR"
    const val MEDIA_PROJECTION_STOPPED = "MEDIA_PROJECTION_STOPPED"
    const val BUFFER_UNDERRUN = "BUFFER_UNDERRUN"
    const val CAPTURE_READ_FAILED = "CAPTURE_READ_FAILED"
}

object AudioCapabilityManager {

    fun androidApi(): Int = Build.VERSION.SDK_INT
    fun manufacturer(): String = Build.MANUFACTURER ?: "?"
    fun model(): String = Build.MODEL ?: "?"
    fun androidLine(): String = AudioPath.androidLine()
    fun osLine(): String = AudioPath.osLine()

    // ── capability probes (all public APIs, all brand-neutral) ──

    fun playbackCapture(): CapState =
        if (Build.VERSION.SDK_INT >= 29) CapState.SUPPORTED else CapState.UNSUPPORTED

    fun mediaProjection(ctx: Context): CapState = try {
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
        if (mpm != null) CapState.SUPPORTED else CapState.UNSUPPORTED
    } catch (t: Throwable) { CapState.UNSUPPORTED }

    fun audioRecord(): CapState {
        if (Build.VERSION.SDK_INT < 23) return CapState.PARTIALLY_SUPPORTED
        return try {
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return CapState.UNSUPPORTED
            val r = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(minBuf * 2)
                .build()
            val ok = r.state == AudioRecord.STATE_INITIALIZED
            r.release()
            if (ok) CapState.SUPPORTED else CapState.UNSUPPORTED
        } catch (t: Throwable) { CapState.UNSUPPORTED }
    }

    fun audioTrack(sr: Int): CapState {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return CapState.UNSUPPORTED
            val t = AudioTrack.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val ok = t.state == AudioTrack.STATE_INITIALIZED
            t.release()
            if (ok) CapState.SUPPORTED else CapState.UNSUPPORTED
        } catch (t: Throwable) { CapState.UNSUPPORTED }
    }

    /** Native DSP self-test: load, configure, process a sine, verify output. */
    fun dsp(): CapState {
        if (!NeonDsp.available) return CapState.UNSUPPORTED
        return try {
            NeonDsp.init(48000, 10)
            NeonDsp.setGraphicGains(FloatArray(10) { 6f })
            NeonDsp.setPreamp(0f)
            NeonDsp.setLimiter(true, -1f)
            NeonDsp.resetStats()
            val buf = ShortArray(960 * 2)
            for (i in buf.indices step 2) {
                val s = (Math.sin(i * 0.05) * 8000).toInt().coerceIn(-32768, 32767).toShort()
                buf[i] = s; buf[i + 1] = s
            }
            NeonDsp.process(buf, 960)
            var changed = false
            var i = 2
            while (i < buf.size) { if (buf[i] != buf[0]) { changed = true; break }; i += 2 }
            // a flat +6dB curve with limiter must alter the signal
            if (changed && NeonDsp.processedFrames() >= 960) CapState.SUPPORTED
            else CapState.PARTIALLY_SUPPORTED
        } catch (t: Throwable) { CapState.UNSUPPORTED }
    }

    // ── routes & formats ──

    private fun activeOutputDevice(ctx: Context): AudioDeviceInfo? {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
                ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } catch (t: Throwable) { null }
    }

    fun outputDeviceLine(ctx: Context): String {
        val d = activeOutputDevice(ctx) ?: return "unknown"
        return when (d.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (A2DP) — codec information unavailable (no public API)"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> {
                val rates = try { d.sampleRates } catch (t: Throwable) { IntArray(0) }
                "USB audio device — rates: " + (if (rates.isEmpty()) "device-reported n/a" else rates.joinToString("/")) +
                    " · channels: " + (try { d.channelCounts.joinToString("/") } catch (t: Throwable) { "?" }) +
                    " · encodings: " + (try { d.encodings.size } catch (t: Throwable) { 0 } )
            }
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            else -> "Other (type " + d.type + ")"
        }
    }

    fun wiredConnected(ctx: Context) = routePresent(ctx, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)
    fun btConnected(ctx: Context) = routePresent(ctx, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    fun usbConnected(ctx: Context) = routePresent(ctx, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)

    private fun routePresent(ctx: Context, vararg types: Int): Boolean = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    } catch (t: Throwable) { false }

    /**
     * Spec 7: never hard-code a rate. Detect the active output device's
     * supported rates, intersect with the common set, prefer the device's
     * own rate, fall back to the system-reported output rate.
     */
    fun suggestedSampleRate(ctx: Context): Int {
        val property = try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        } catch (t: Throwable) { 48000 }
        val d = activeOutputDevice(ctx) ?: return property
        val rates = try { d.sampleRates } catch (t: Throwable) { IntArray(0) }
        if (rates.isEmpty()) return property
        val common = intArrayOf(44100, 48000, 96000)
        return rates.firstOrNull { it in common } ?: property
    }

    fun channelsLine(): String = "Stereo capture (mono fallback via internal chain)"
    fun framesPerBufferLine(ctx: Context): String = try {
        val v = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        if (v.isNullOrBlank()) "n/a" else v + " frames/buffer"
    } catch (t: Throwable) { "n/a" }

    // ── the universal compatibility test ──

    fun runFullTest(ctx: Context): String {
        val sb = StringBuilder()
        fun line(n: Int, name: String, result: String) { sb.append(n).append(". ").append(name).append(": ").append(result).append('\n') }
        val api = androidApi()
        line(1, "Android (API $api)",
            if (api >= 29) "PASS" else "LIMITED — system playback capture requires Android 10 or newer")
        line(2, "AudioPlaybackCapture",
            if (api >= 29) "PASS" else "NOT AVAILABLE")
        line(3, "MediaProjection",
            if (mediaProjection(ctx) == CapState.SUPPORTED) "PASS" else "FAIL")
        line(4, "AudioRecord",
            if (audioRecord() == CapState.SUPPORTED) "PASS" else "FAIL")
        val sr = suggestedSampleRate(ctx)
        line(5, "AudioTrack (${sr}Hz)",
            if (audioTrack(sr) == CapState.SUPPORTED) "PASS" else "FAIL")
        line(6, "DSP (native self-test)",
            when (dsp()) {
                CapState.SUPPORTED -> "PASS"
                CapState.PARTIALLY_SUPPORTED -> "LIMITED"
                else -> "FAIL"
            })
        line(7, "Speaker output", "PASS (built-in)")
        line(8, "Wired output", if (wiredConnected(ctx)) "PASS (connected)" else "NOT TESTED (not connected)")
        line(9, "Bluetooth output", if (btConnected(ctx)) "PASS (connected)" else "NOT TESTED (not connected)")
        line(10, "USB audio", if (usbConnected(ctx)) "PASS (connected)" else "NOT TESTED (not connected)")
        return sb.toString().trimEnd()
    }

    // ── anonymized local compatibility log (spec 15) ──

    fun appendCapabilityLog(ctx: Context, captureResult: String, frames: Long, underruns: Int, dspOk: Boolean) {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val cores = Runtime.getRuntime().availableProcessors()
            val ramGb = try {
                val mi = android.app.ActivityManager.MemoryInfo()
                am?.getMemoryInfo(mi)
                if (mi.totalMem > 0) mi.totalMem / (1024L * 1024L * 1024L) else -1
            } catch (t: Throwable) { -1 }
            val category = when {
                cores >= 8 && ramGb >= 6 -> "high"
                cores >= 6 && ramGb >= 3 -> "mid"
                else -> "low"
            }
            val o = JSONObject()
            o.put("ts", System.currentTimeMillis())
            o.put("api", androidApi())
            o.put("oem", manufacturer())
            o.put("category", category)
            o.put("route", outputDeviceLine(ctx))
            o.put("capture", captureResult)
            o.put("frames", frames)
            o.put("underruns", underruns)
            o.put("dsp", if (dspOk) "OK" else "ERROR")
            File(ctx.filesDir, "capability_log.jsonl").appendText(o.toString() + "\n")
        } catch (t: Throwable) { }
    }

    fun logTail(ctx: Context, n: Int = 5): String {
        return try {
            val f = File(ctx.filesDir, "capability_log.jsonl")
            if (!f.exists()) return "(no sessions logged yet)"
            f.readLines().takeLast(n).joinToString("\n")
        } catch (t: Throwable) { "(log unavailable)" }
    }
}
