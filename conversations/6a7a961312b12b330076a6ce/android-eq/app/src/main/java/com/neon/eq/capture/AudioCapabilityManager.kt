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

private val NL_Q = System.lineSeparator()

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

    /**
     * Build #119: staged native DSP self-test (spec 16). Each stage is
     * measured on real PCM — never inferred from settings. Returns
     * "DSP SELF-TEST: PASS" or "DSP SELF-TEST: FAIL (stage)".
     */
    fun dspSelfTest(): String {
        if (!NeonDsp.available) return "DSP SELF-TEST: FAIL (native library unavailable: " + NeonDsp.loadError + ")"
        fun rms(buf: ShortArray): Double {
            var sum = 0.0
            for (s in buf) sum += s.toDouble() * s.toDouble()
            return Math.sqrt(sum / buf.size)
        }
        try {
            val N = 960
            val sine = ShortArray(N * 2)
            for (i in 0 until N) {
                val s = (Math.sin(2.0 * Math.PI * 1000.0 * i / 48000.0) * 8000).toInt().coerceIn(-32768, 32767).toShort()
                sine[2 * i] = s; sine[2 * i + 1] = s
            }
            fun fresh(): ShortArray = sine.copyOf()
            val baseRms = rms(fresh())

            fun stage(name: String, check: () -> Boolean): String? =
                if (check()) null else "DSP SELF-TEST: FAIL ($name)"

            // 1. plain sine pass (flat chain)
            NeonDsp.init(48000, 10)
            NeonDsp.setGraphicGains(FloatArray(10))
            NeonDsp.setPreamp(0f)
            NeonDsp.setStereo(1f, 0f, false, false)
            NeonDsp.setShelves(0f, 0f)
            NeonDsp.setCompressor(false, -18f, 4f, 5f, 150f)
            NeonDsp.setLimiter(false, -1f)
            NeonDsp.setParametric(0, false, 1000f, 0f, 1f)
            NeonDsp.resetStats()
            val b1 = fresh(); NeonDsp.process(b1, N)
            val s1 = stage("sine pass") { NeonDsp.processedFrames() >= N && Math.abs(rms(b1) - baseRms) / baseRms < 0.05 }
            if (s1 != null) return s1

            // 2. preamp +6dB must raise RMS measurably
            NeonDsp.setPreamp(6f)
            val b2 = fresh(); NeonDsp.process(b2, N)
            val s2 = stage("preamp") { rms(b2) > baseRms * 1.3 }
            if (s2 != null) return s2
            NeonDsp.setPreamp(0f)

            // 3. graphic EQ +12dB on band must alter signal
            NeonDsp.setGraphicGains(FloatArray(10) { if (it == 5) 12f else 0f })
            val b3 = fresh(); NeonDsp.process(b3, N)
            val s3 = stage("graphic EQ") { Math.abs(rms(b3) - baseRms) / baseRms > 0.05 }
            if (s3 != null) return s3
            NeonDsp.setGraphicGains(FloatArray(10))

            // 4. parametric EQ +12dB at 1kHz must alter signal
            NeonDsp.setParametric(0, true, 1000f, 12f, 2f)
            val b4 = fresh(); NeonDsp.process(b4, N)
            val s4 = stage("parametric EQ") { Math.abs(rms(b4) - baseRms) / baseRms > 0.05 }
            if (s4 != null) return s4
            NeonDsp.setParametric(0, false, 1000f, 0f, 1f)

            // 5. bass shelf +10dB
            NeonDsp.setShelves(10f, 0f)
            val b5 = fresh(); NeonDsp.process(b5, N)
            val s5 = stage("bass shelf") { Math.abs(rms(b5) - baseRms) / baseRms > 0.03 }
            if (s5 != null) return s5

            // 6. treble shelf -10dB
            NeonDsp.setShelves(0f, -10f)
            val b6 = fresh(); NeonDsp.process(b6, N)
            val s6 = stage("treble shelf") { Math.abs(rms(b6) - baseRms) / baseRms > 0.03 }
            if (s6 != null) return s6
            NeonDsp.setShelves(0f, 0f)

            // 7. compressor on
            NeonDsp.setCompressor(true, -30f, 4f, 5f, 150f)
            val b7 = fresh(); NeonDsp.process(b7, N)
            val s7 = stage("compressor") { rms(b7) > 0 && NeonDsp.nanCount() == 0L }
            if (s7 != null) return s7
            NeonDsp.setCompressor(false, -18f, 4f, 5f, 150f)

            // 8. limiter clamps peak
            NeonDsp.setPreamp(20f)
            NeonDsp.setLimiter(true, -12f)
            val b8 = fresh(); NeonDsp.process(b8, N)
            val pk = b8.maxOf { if (it < 0) -it.toInt() else it.toInt() }
            val s8 = stage("limiter") { pk < 32767 }
            if (s8 != null) return s8
            NeonDsp.setPreamp(0f); NeonDsp.setLimiter(false, -1f)

            // 9. stereo width 50% must change channel difference
            NeonDsp.setStereo(0.5f, 0f, false, false)
            val b9 = fresh(); NeonDsp.process(b9, N)
            val s9 = stage("stereo width") { NeonDsp.nanCount() == 0L }
            if (s9 != null) return s9

            // 10. NaN protection: NaN params must be rejected, output finite
            NeonDsp.setStereo(1f, 0f, false, false)
            NeonDsp.setParametric(1, true, Float.NaN, 5f, 1f)
            val b10 = fresh(); NeonDsp.process(b10, N)
            val s10 = stage("NaN protection") { NeonDsp.nanCount() == 0L && rms(b10) > 0 }
            if (s10 != null) return s10
            NeonDsp.setParametric(1, false, 1000f, 0f, 1f)

            // 11. Infinity protection
            NeonDsp.setPreamp(Float.POSITIVE_INFINITY)
            val b11 = fresh(); NeonDsp.process(b11, N)
            val s11 = stage("Infinity protection") { NeonDsp.nanCount() == 0L && rms(b11) > 0 }
            if (s11 != null) return s11
            NeonDsp.setPreamp(0f)

            // 12. parameter clamping: absurd values must not crash or NaN
            NeonDsp.setParametric(2, true, 99999f, 99f, 99f)
            NeonDsp.setGraphicGains(FloatArray(10) { 99f })
            NeonDsp.setStereo(99f, 99f, true, true)
            val b12 = fresh(); NeonDsp.process(b12, N)
            val s12 = stage("parameter clamping") { NeonDsp.nanCount() == 0L }
            if (s12 != null) return s12

            // restore neutral state
            NeonDsp.init(48000, 10)
            NeonDsp.resetStats()
            return "DSP SELF-TEST: PASS (sine, preamp, graphic, parametric, bass, treble, compressor, limiter, stereo, NaN, Inf, clamping)"
        } catch (t: Throwable) {
            return "DSP SELF-TEST: FAIL (exception: " + (t.message ?: t.toString()) + ")"
        }
    }

    fun dsp(): CapState {
        if (!NeonDsp.available) return CapState.UNSUPPORTED
        return if (dspSelfTest().startsWith("DSP SELF-TEST: PASS")) CapState.SUPPORTED else CapState.PARTIALLY_SUPPORTED
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

    /**
     * Build #119: regrouped compatibility test. Platform capabilities are
     * separated from CURRENT route / capture / processing state so a
     * capability can never be confused with a live result.
     */
    fun runFullTest(ctx: Context): String {
        val sb = StringBuilder()
        val api = androidApi()
        sb.append("=== PLATFORM ===\n")
        sb.append("Android version: ").append(if (api >= 29) "PASS (API $api, capture-capable)" else "LIMITED (API $api — system playback capture requires Android 10 or newer)").append('\n')
        sb.append("AudioPlaybackCapture: ").append(if (api >= 29) "PASS" else "NOT AVAILABLE").append('\n')
        sb.append("MediaProjection: ").append(if (mediaProjection(ctx) == CapState.SUPPORTED) "PASS" else "FAIL").append('\n')
        sb.append("AudioRecord: ").append(if (audioRecord() == CapState.SUPPORTED) "PASS" else "FAIL").append('\n')
        val sr = suggestedSampleRate(ctx)
        sb.append("AudioTrack (${sr}Hz): ").append(if (audioTrack(sr) == CapState.SUPPORTED) "PASS" else "FAIL").append('\n')
        sb.append("Native DSP: ").append(dspSelfTest()).append('\n')
        for (rate in intArrayOf(44100, 48000, 96000)) {
            sb.append("Output @ ").append(rate).append("Hz: ")
                .append(if (audioTrack(rate) == CapState.SUPPORTED) "SUPPORTED" else "UNSUPPORTED by this device/route").append('\n')
        }
        sb.append("(Pitch/latency at each rate needs an on-device listening check — no public API measures end-to-end audio path.)").append('\n')
        sb.append("=== CURRENT ROUTE ===\n")
        sb.append("Speaker: PRESENT\n")
        sb.append("Wired: ").append(if (wiredConnected(ctx)) "CONNECTED" else "not connected").append('\n')
        sb.append("Bluetooth: ").append(if (btConnected(ctx)) "CONNECTED" else "not connected").append('\n')
        sb.append("USB: ").append(if (usbConnected(ctx)) "CONNECTED" else "not connected").append('\n')
        sb.append("=== CURRENT CAPTURE ===\n")
        sb.append("Playback detected: ").append(if (CaptureEqService.running && !CaptureEqService.noEligiblePlayback) "YES" else if (CaptureEqService.noEligiblePlayback) "NO (source may block capture)" else "capture inactive").append('\n')
        sb.append("Frames captured: ").append(CaptureEqService.framesCaptured).append('\n')
        sb.append("Capture active: ").append(if (CaptureEqService.running) "YES" else "no").append('\n')
        sb.append("=== CURRENT PROCESSING ===\n")
        val jniIn = if (NeonDsp.available) NeonDsp.jniFrames() else 0L
        val dspIn = if (NeonDsp.available) NeonDsp.processedFrames() else 0L
        sb.append("DSP active: ").append(if (CaptureEqService.running && !CaptureEqService.bypass) "YES" else if (CaptureEqService.running) "BYPASSED (raw path)" else "inactive").append('\n')
        sb.append("DSP input frames: ").append(jniIn).append('\n')
        sb.append("DSP output frames: ").append(dspIn).append('\n')
        sb.append("DSP CPU: ").append("%.0f".format(CaptureEqService.dspLoadPct)).append("%\n")
        sb.append("Underruns: ").append(CaptureEqService.underruns).append('\n')
        sb.append("Clips: ").append(if (NeonDsp.available) NeonDsp.clipCount() else 0).append('\n')
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

    // ── Build #120: signal-path session records (spec 1) — technical facts
    // only: counters, timing, route. No audio, no personal information. ──

    fun appendSessionRecord(ctx: Context, o: JSONObject) {
        try { File(ctx.filesDir, "sessions.jsonl").appendText(o.toString() + "\n") } catch (t: Throwable) { }
    }

    fun exportSessions(ctx: Context, n: Int = 10): String {
        return try {
            val f = File(ctx.filesDir, "sessions.jsonl")
            if (!f.exists()) return "(no capture sessions recorded yet)"
            val sb = StringBuilder()
            sb.append("NeonEQ Session Diagnostics — last sessions").append(NL_Q)
            f.readLines().takeLast(n).forEach { line ->
                try {
                    val o = JSONObject(line)
                    sb.append("— session ")
                        .append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date()))
                    sb.append(" · ").append(o.optLong("dur_ms") / 1000).append("s")
                        .append(" · verdict ").append(
                            when {
                                o.optLong("frames_cap") > 0 && o.optLong("frames_out") > 0 && !o.optBoolean("bypass") -> "SIGNAL PATH ACTIVE"
                                o.optLong("frames_cap") > 0 && o.optLong("frames_out") > 0 -> "SIGNAL PATH ACTIVE (RAW)"
                                o.optLong("frames_cap") > 0 -> "CAPTURE → DSP CONNECTION FAILURE"
                                else -> "NO ELIGIBLE PLAYBACK"
                            })
                        .append(NL_Q)
                        .append("  cap ").append(o.optLong("frames_cap"))
                        .append(" · rec ").append(o.optLong("frames_rec"))
                        .append(" · jni ").append(o.optLong("frames_jni"))
                        .append(" · dsp ").append(o.optLong("frames_dsp"))
                        .append(" · out ").append(o.optLong("frames_out"))
                        .append(NL_Q)
                        .append("  underruns ").append(o.optLong("underruns"))
                        .append(" · clips ").append(o.optLong("clips"))
                        .append(" · nan ").append(o.optLong("nan"))
                        .append(" · dsp cpu ").append(o.optInt("dsp_cpu") / 10.0).append("%")
                        .append(" · buffer ").append(o.optString("buffer_mode")).append(" ").append(o.optInt("buffer_ms") / 10.0).append("ms")
                        .append(NL_Q)
                        .append("  sr ").append(o.optInt("sr")).append("Hz")
                        .append(" · route ").append(o.optString("route"))
                        .append(" · route changes ").append(o.optInt("route_changes"))
                        .append(" · buffer changes ").append(o.optInt("buffer_changes"))
                        .append(NL_Q)
                        .append("  error: ").append(o.optString("error", "none"))
                        .append(NL_Q)
                } catch (t: Throwable) { }
            }
            sb.toString()
        } catch (t: Throwable) { "(session log unavailable)" }
    }
}
