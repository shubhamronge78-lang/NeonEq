package com.neon.eq.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.neon.eq.MainActivity
import com.neon.eq.R
import com.neon.eq.dsp.DspParams
import com.neon.eq.dsp.NeonDsp

/**
 * Build #117: the no-root capture pipeline, hardened.
 *
 * External app playback → AudioPlaybackCapture (public Android 10+ API)
 * → AudioRecord → native NeonDsp → AudioTrack → selected output device.
 *
 * HONEST LIMIT (shown in the UI, never hidden): Android may continue playing
 * the original signal while NeonEQ outputs the processed signal. No public
 * API lets one app silence or redirect another app's playback. DRM content,
 * calls, and apps that opt out of capture are excluded by Android itself.
 *
 * v117 additions over v116: buffer modes (low/balanced/stable), audio thread
 * priority, latency breakdown (capture/DSP/output/total), DSP CPU load,
 * no-eligible-playback detection, A/B bypass toggle, processed-output volume,
 * pause/resume, route-change handling, richer specific errors.
 */
class CaptureEqService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var paused = false
        @Volatile var bypass = false
        @Volatile var outVolume = 1f
        @Volatile var bufferMode = "balanced"
        @Volatile var framesCaptured = 0L
        @Volatile var framesDone = 0L
        @Volatile var recordFrames = 0L          // AudioRecord read counter (independent)
        @Volatile var lastDataAt = 0L            // elapsedRealtime of last PCM read
        @Volatile var captureBufferMs = 0.0
        @Volatile var captureBufferFrames = 0
        @Volatile var oldRouteLine: String? = null
        @Volatile var oldRateLine: String? = null
        @Volatile var lastBufferChange: String? = null
        @Volatile var loopMs = 0.0
        @Volatile var sessionStartedAt = 0L
        @Volatile var sessionRouteChanges = 0
        @Volatile var sessionBufferChanges = 0
        @Volatile var underruns = 0
        @Volatile var noEligiblePlayback = false
        @Volatile var routeNote: String? = null
        @Volatile var lastError: String? = null
        @Volatile var captureSampleRate = 48000

        // latency breakdown (ms)
        @Volatile var capLatencyMs = 0.0
        @Volatile var dspMs = 0.0
        @Volatile var outLatencyMs = 0.0
        @Volatile var totalLatencyMs = 0.0
        @Volatile var dspLoadPct = 0.0

        @Volatile var resultCode = 0
        @Volatile var resultData: Intent? = null
        @Volatile private var requestStop = false
        @Volatile var routeDirty = false

        const val ACTION_STOP = "com.neon.eq.capture.STOP"
        const val ACTION_PAUSE = "com.neon.eq.capture.PAUSE"
        const val CHANNEL_ID = "neoneq_capture"
        const val NOTIF_ID = 42

        fun statusReport(): String =
            "CAPTURE API: " + (if (Build.VERSION.SDK_INT >= 29) "SUPPORTED" else "UNSUPPORTED") + "\n" +
                "MEDIA PROJECTION: " + (if (running) "GRANTED" else if (lastError?.startsWith(AudioErrors.CAPTURE_PERMISSION_DENIED) == true) "DENIED" else "NOT REQUESTED") + "\n" +
                "AUDIO CAPTURE: " + (if (!running) "INACTIVE" else if (noEligiblePlayback) "NO ELIGIBLE PLAYBACK" else "ACTIVE") + "\n" +
                "SOURCE PLAYBACK: " + (if (!running) "-" else if (noEligiblePlayback) "NOT DETECTED (source app may block capture)" else "DETECTED") + "\n" +
                "DSP: " + (if (!NeonDsp.available) "ERROR (native lib unavailable)" else if (bypass) "BYPASSED (A)" else "ACTIVE") + "\n" +
                "OUTPUT: " + (if (running && !noEligiblePlayback) "ACTIVE" else if (running) "WAITING FOR PLAYBACK" else "INACTIVE")
    }

    private var thread: Thread? = null
    private var devCallback: AudioDeviceCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notif = buildRichNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            lastError = "foreground service failed: " + (t.message ?: t.toString())
        }

        when (intent?.action) {
            ACTION_STOP -> {
                requestStop = true
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                paused = !paused
                return START_NOT_STICKY
            }
        }

        if (!running && thread?.isAlive != true) {
            requestStop = false
            paused = false
            lastError = null
            routeNote = null
            framesCaptured = 0L
            framesDone = 0L
            recordFrames = 0L
            lastDataAt = 0L
            oldRouteLine = null
            oldRateLine = null
            lastBufferChange = null
            loopMs = 0.0
            sessionStartedAt = android.os.SystemClock.elapsedRealtime()
            sessionRouteChanges = 0
            sessionBufferChanges = 0
            underruns = 0
            noEligiblePlayback = false
            thread = Thread { captureLoop() }.apply { start() }
        }
        return START_NOT_STICKY
    }

    private fun captureLoop() {
        var recorder: AudioRecord? = null
        var track: AudioTrack? = null
        var projection: MediaProjection? = null
        var wl: PowerManager.WakeLock? = null
        var registerCb = false
        try {
            // Spec 6: real-time thread priority, set once before the loop.
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (t: Throwable) { }

            val data = resultData
            if (data == null || Build.VERSION.SDK_INT < 29) {
                lastError = AudioErrors.CAPTURE_PERMISSION_DENIED + ": MediaProjection consent missing or Android below 10"
                return
            }
            // The service is already foreground with type mediaProjection —
            // required on Android 14+ BEFORE requesting the projection.
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = try {
                mpm.getMediaProjection(resultCode, data)
            } catch (t: Throwable) {
                lastError = AudioErrors.CAPTURE_PERMISSION_DENIED + ": " + (t.message ?: t.toString()); null
            } ?: return

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    lastError = AudioErrors.MEDIA_PROJECTION_STOPPED + ": projection ended by user or system"
                    requestStop = true
                }
            }, null)

            val eng = com.neon.eq.engine.EqualizerEngine.getInstance(applicationContext)

            // Spec 8: detect the device's real output rate instead of assuming
            // 44.1/48. Capture and output run at the SAME rate, so no resampler
            // is ever needed and audio is never pitch-shifted.
            // Build #118: brand-neutral capability-driven rate selection —
            // never hard-code a rate. Capture and output run at the SAME rate,
            // so no resampler is needed and audio is never pitch-shifted.
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            var sr = AudioCapabilityManager.suggestedSampleRate(this)
            captureSampleRate = sr

            bufferMode = eng.getCaptureBufferMode()
            outVolume = eng.getCaptureOutVolume()
            val (chunkFrames, inMult, outMult) = when (bufferMode) {
                "low" -> Triple(240, 1, 1)
                "stable" -> Triple(1920, 4, 4)
                else -> Triple(960, 2, 2)
            }

            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val minIn = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(maxOf(minIn * inMult, 16384))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                lastError = AudioErrors.AUDIO_RECORD_INITIALIZATION_FAILED + ": capture record session could not start"
                return
            }

            val minOut = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(maxOf(minOut * outMult, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                lastError = AudioErrors.AUDIO_TRACK_INITIALIZATION_FAILED + ": output track state " + (try { track.state } catch (t: Throwable) { -1 })
                return
            }
            try { track.setVolume(outVolume) } catch (t: Throwable) { }

            // Mirror the live engine curve + persisted DSP chain into native DSP.
            try {
                if (NeonDsp.available) {
                    NeonDsp.init(sr, eng.bandCount)
                    NeonDsp.setGraphicGains(FloatArray(eng.bandCount) { i -> (eng.bandLevelsSnapshot().getOrNull(i)?.toInt() ?: 0).toFloat() })
                    DspParams.load(eng).applyTo(NeonDsp)
                    NeonDsp.resetStats()
                }
            } catch (t: Throwable) {
                lastError = AudioErrors.DSP_INITIALIZATION_FAILED + ": " + (t.message ?: t.toString())
            }

            // Route-change awareness (Bluetooth/wired connect/disconnect) —
            // informational only; AudioTrack reroutes without crashing.
            devCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    routeNote = AudioErrors.OUTPUT_ROUTE_CHANGED + ": " + AudioPath.outputDevice(this@CaptureEqService)
                    routeDirty = true
                }
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                    routeNote = AudioErrors.OUTPUT_ROUTE_CHANGED + ": " + AudioPath.outputDevice(this@CaptureEqService)
                    routeDirty = true
                }
            }
            try { am.registerAudioDeviceCallback(devCallback, null); registerCb = true } catch (t: Throwable) { }

            recorder.startRecording()
            track.play()
            running = true

            wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "neoneq:capture")
            try { wl.acquire(60 * 60 * 1000L) } catch (t: Throwable) { }

            val chunk = ShortArray(chunkFrames * 2)
            val chunkMs = chunkFrames * 1000.0 / sr
            capLatencyMs = chunkMs
            captureBufferMs = chunkMs
            captureBufferFrames = chunkFrames
            outLatencyMs = try { track.bufferSizeInFrames * 1000.0 / sr } catch (t: Throwable) { 0.0 }
            var pauseApplied = false
            var zeroReads = 0
            var mirrorCounter = 0
            var underrunMark = 0
            var underrunStreak = 0
            while (!requestStop) {
                if (paused) {
                    if (!pauseApplied) {
                        try { recorder.stop() } catch (t: Throwable) { }
                        pauseApplied = true
                    }
                    Thread.sleep(200)
                    continue
                } else if (pauseApplied) {
                    try { recorder.startRecording() } catch (t: Throwable) { }
                    pauseApplied = false
                }
                val n = recorder.read(chunk, 0, chunk.size)
                if (n < 0) {
                    lastError = AudioErrors.CAPTURE_READ_FAILED + ": system returned code $n — capture path stopped"
                    break
                }
                if (n == 0) {
                    zeroReads++
                    if (zeroReads == 400) noEligiblePlayback = true
                    Thread.sleep(10)
                    continue
                }
                zeroReads = 0
                noEligiblePlayback = false
                val tLoop0 = System.nanoTime()
                framesCaptured += n / 2
                recordFrames += n / 2
                lastDataAt = android.os.SystemClock.elapsedRealtime()
                try { loopMs = loopMs * 0.9 + (System.nanoTime() - tLoop0) / 1e6 * 0.1 } catch (_: Throwable) { }

                if (!bypass && NeonDsp.available) {
                    val tDsp0 = System.nanoTime()
                    try { NeonDsp.process(chunk, n / 2) } catch (t: Throwable) {
                        lastError = AudioErrors.DSP_PROCESSING_ERROR + ": " + (t.message ?: t.toString()) + " — bypassing safely"
                        bypass = true
                    }
                    val dMs = (System.nanoTime() - tDsp0) / 1e6
                    dspMs = dspMs * 0.9 + dMs * 0.1
                    dspLoadPct = dspMs / chunkMs * 100.0
                } else {
                    dspMs = 0.0; dspLoadPct = 0.0
                }
                track?.write(chunk, 0, n)
                framesDone += n / 2
                totalLatencyMs = capLatencyMs + dspMs + outLatencyMs
                if (framesDone % 48000L < chunkFrames * 2L) {
                    try { track?.let { underruns = it.underrunCount } } catch (t: Throwable) { }
                }
                // Re-mirror the live graphic curve + volume every ~2s so EQ
                // changes and volume pills affect the processed signal live.
                if (++mirrorCounter >= 100) {
                    mirrorCounter = 0
                    try {
                        if (!bypass && NeonDsp.available) {
                            val snap = eng.bandLevelsSnapshot()
                            NeonDsp.setGraphicGains(FloatArray(eng.bandCount) { i -> (snap.getOrNull(i)?.toInt() ?: 0).toFloat() })
                        }
                        track?.setVolume(outVolume)
                    } catch (t: Throwable) { }
                    // Build #121: refresh the foreground notification with
                    // current route + meters (mirror thread, not audio thread).
                    try {
                        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                            .notify(NOTIF_ID, buildRichNotification())
                    } catch (_: Throwable) { }
                    // Build #119: rolling-threshold escalation — a single
                    // underrun never changes the mode. Escalate only after
                    // 3 consecutive 2s windows with >5 underruns each.
                    val dU = underruns - underrunMark
                    underrunMark = underruns
                    if (dU > 5) underrunStreak++ else underrunStreak = 0
                    if (underrunStreak >= 3) {
                        underrunStreak = 0
                        if (bufferMode == "low") {
                            bufferMode = "balanced"
                            eng.setCaptureBufferMode("balanced")
                            routeNote = "Buffer automatically changed: LOW → BALANCED (sustained underruns)"
                            lastBufferChange = "LOW → BALANCED"
                            sessionBufferChanges++
                            notifyBufferAdjusted("LOW → BALANCED")
                        } else if (bufferMode == "balanced") {
                            bufferMode = "stable"
                            eng.setCaptureBufferMode("stable")
                            routeNote = "Buffer automatically changed: BALANCED → STABLE (sustained underruns)"
                            lastBufferChange = "BALANCED → STABLE"
                            sessionBufferChanges++
                            notifyBufferAdjusted("BALANCED → STABLE")
                        }
                    }
                    // Build #118: output route change -> safe reconfigure
                    // (STOP -> UPDATE FORMAT -> RESTART OUTPUT -> RESUME DSP).
                    if (routeDirty) {
                        routeDirty = false
                        val newSr = try {
                            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: sr
                        } catch (t: Throwable) { sr }
                        if (newSr != sr) {
                            sessionRouteChanges++
                            oldRouteLine = AudioPath.outputDevice(this)
                            oldRateLine = sr.toString() + "Hz"
                            try {
                                track?.pause(); track?.flush(); track?.stop(); track?.release()
                                val minOut2 = AudioTrack.getMinBufferSize(newSr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                                val rebuilt = AudioTrack.Builder()
                                    .setAudioAttributes(
                                        AudioAttributes.Builder()
                                            .setUsage(AudioAttributes.USAGE_MEDIA)
                                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                            .build())
                                    .setAudioFormat(
                                        AudioFormat.Builder()
                                            .setSampleRate(newSr)
                                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                            .build())
                                    .setBufferSizeInBytes(maxOf(minOut2 * outMult, 16384))
                                    .setTransferMode(AudioTrack.MODE_STREAM)
                                    .build()
                                if (rebuilt.state == AudioTrack.STATE_INITIALIZED) {
                                    track = rebuilt
                                    sr = newSr
                                    captureSampleRate = sr
                                    capLatencyMs = chunkFrames * 1000.0 / sr
                                    outLatencyMs = try { rebuilt.bufferSizeInFrames * 1000.0 / sr } catch (t: Throwable) { 0.0 }
                                    try { rebuilt.setVolume(outVolume) } catch (t: Throwable) { }
                                    rebuilt.play()
                                    routeNote = "ROUTE CHANGED: " + (oldRouteLine ?: "?") + " to " + AudioPath.outputDevice(this) +
                                        " | " + (oldRateLine ?: "?") + " to " + newSr + "Hz — output rebuilt, DSP state preserved"
                                    // Build #122: report the new route only AFTER the rebuild
                                    // confirmed the output path is ready.
                                    try {
                                        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                                            .notify(NOTIF_ID, buildRichNotification())
                                    } catch (_: Throwable) { }
                                } else {
                                    lastError = AudioErrors.AUDIO_TRACK_INITIALIZATION_FAILED + ": rebuild after route change failed"
                                    try { rebuilt.release() } catch (t: Throwable) { }
                                }
                            } catch (t: Throwable) {
                                lastError = AudioErrors.OUTPUT_ROUTE_CHANGED + ": rebuild failed — " + (t.message ?: t.toString())
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.toString()
        } finally {
            running = false
            try { recorder?.stop() } catch (t: Throwable) { }
            try { recorder?.release() } catch (t: Throwable) { }
            try { track?.stop() } catch (t: Throwable) { }
            try { track?.release() } catch (t: Throwable) { }
            try { projection?.stop() } catch (t: Throwable) { }
            try { wl?.let { if (it.isHeld) it.release() } } catch (t: Throwable) { }
            try { if (registerCb) devCallback?.let {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(it)
            } } catch (t: Throwable) { }
            // Build #118: anonymized local capability log (spec 15) —
            // technical facts only, never user content.
            try {
                val result = if (framesCaptured > 0) "CAPTURE_OK" else if (noEligiblePlayback) "NO_ELIGIBLE_PLAYBACK" else "ERROR"
                AudioCapabilityManager.appendCapabilityLog(this, result, framesCaptured, underruns, NeonDsp.available)
            } catch (t: Throwable) { }
            // Build #120: signal-path session record (spec 1) — counters only,
            // no audio, no personal information.
            try {
                val sess = org.json.JSONObject()
                sess.put("start_ms", sessionStartedAt)
                sess.put("dur_ms", android.os.SystemClock.elapsedRealtime() - sessionStartedAt)
                sess.put("frames_cap", framesCaptured)
                sess.put("frames_rec", recordFrames)
                sess.put("frames_jni", if (NeonDsp.available) NeonDsp.jniFrames() else 0L)
                sess.put("frames_dsp", if (NeonDsp.available) NeonDsp.processedFrames() else 0L)
                sess.put("frames_out", framesDone)
                sess.put("underruns", underruns)
                sess.put("clips", if (NeonDsp.available) NeonDsp.clipCount() else 0L)
                sess.put("nan", if (NeonDsp.available) NeonDsp.nanCount() else 0L)
                sess.put("dsp_cpu", (dspLoadPct * 10).toInt())
                sess.put("buffer_ms", (captureBufferMs * 10).toInt())
                sess.put("buffer_mode", bufferMode)
                sess.put("sr", captureSampleRate)
                sess.put("route", AudioPath.outputDevice(this))
                sess.put("route_changes", sessionRouteChanges)
                sess.put("buffer_changes", sessionBufferChanges)
                sess.put("bypass", bypass)
                sess.put("error", lastError ?: "none")
                AudioCapabilityManager.appendSessionRecord(this, sess)
            } catch (t: Throwable) { }
            stopSelf()
        }
    }

        private fun notifyBufferAdjusted(change: String) {
        try {
            val openPi = PendingIntent.getActivity(
                this, 2, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(NOTIF_ID, NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("SonicCore")
                    .setContentText("Audio buffer adjusted: " + change)
                    .setStyle(NotificationCompat.BigTextStyle().bigText("Audio buffer adjusted: " + change))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(openPi)
                    .addAction(0, "Stop", actionPi(ACTION_STOP))
                    .build())
        } catch (_: Throwable) { }
    }

    // ── Build #121: rich notification (spec 32) — technical facts only,
    // never sensitive playback information. Rebuilt every ~2s from the
    // mirror loop (never from the audio thread). ──
    private fun buildRichNotification(): android.app.Notification {
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        // Build #122: explicit notification states derived ONLY from the
        // existing authoritative state (companion counters, lastError,
        // noEligiblePlayback, bypass, NeonDsp availability). No second
        // status system, no fabricated values: unavailable fields are
        // omitted, never invented.
        val recentData = lastDataAt > 0L &&
            (android.os.SystemClock.elapsedRealtime() - lastDataAt) < 3000L
        val title: String
        val body: String
        if (lastError != null) {
            title = "SonicCore · AUDIO ERROR"
            body = simpleErrorText(lastError!!) + "\nTap to view diagnostics."
        } else if (noEligiblePlayback) {
            title = "SonicCore · CAPTURE BLOCKED"
            body = "Source app does not permit playback capture"
        } else if (!recentData && framesCaptured == 0L) {
            title = "SonicCore · WAITING"
            body = "Waiting for eligible playback"
        } else if (bypass) {
            title = "SonicCore · DSP BYPASS"
            body = "Capture active · DSP bypassed"
        } else if (!NeonDsp.available) {
            title = "SonicCore · DSP ERROR"
            body = "Native DSP unavailable — tap to view diagnostics"
        } else {
            title = "SonicCore · DSP ACTIVE"
            val sb = StringBuilder()
            if (captureSampleRate > 0) sb.append(captureSampleRate / 1000).append(" kHz · Stereo")
            val dev = try { AudioPath.outputDevice(this) } catch (_: Throwable) { "" }
            if (dev.isNotEmpty()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(dev) }
            fun dbOf(ms: Int): String =
                if (NeonDsp.available && ms > 0) "%.1f".format(20 * kotlin.math.log10(ms / 1000.0)) + " dB" else ""
            val inDb = if (NeonDsp.available) runCatching { dbOf(NeonDsp.inRmsMs()) }.getOrDefault("") else ""
            val outDb = if (NeonDsp.available) runCatching { dbOf(NeonDsp.outRmsMs()) }.getOrDefault("") else ""
            if (inDb.isNotEmpty() && outDb.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append("Input ").append(inDb).append(" · Output ").append(outDb)
            }
            body = sb.toString()
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(0, "Pause", actionPi(ACTION_PAUSE))
            .addAction(0, "Stop", actionPi(ACTION_STOP))
            .build()
    }

    /** Simple, non-technical explanation for the notification; the detailed
     *  error code stays on the diagnostics screen (spec 12). */
    private fun simpleErrorText(err: String): String = when {
        err.contains("permission", ignoreCase = true) -> "Capture permission denied."
        err.contains("projection", ignoreCase = true) -> "MediaProjection stopped."
        err.contains("track", ignoreCase = true) -> "AudioTrack initialization failed."
        err.contains("record", ignoreCase = true) -> "AudioRecord initialization failed."
        else -> "An audio component failed."
    }

private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "System capture", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun actionPi(action: String): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getService(
            this, if (action == ACTION_PAUSE) 3 else 1,
            Intent(this, CaptureEqService::class.java).setAction(action),
            flags)
    }

    override fun onDestroy() {
        requestStop = true
        super.onDestroy()
    }
}
