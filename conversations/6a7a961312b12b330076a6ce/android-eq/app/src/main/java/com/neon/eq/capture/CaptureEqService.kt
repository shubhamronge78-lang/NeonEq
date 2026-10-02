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
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NeonEQ Audio Engine Active")
            .setContentText("Android capture path active — tap OPEN for controls")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, "Pause", actionPi(ACTION_PAUSE))
            .addAction(0, "Stop", actionPi(ACTION_STOP))
            .build()
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
            outLatencyMs = try { track.bufferSizeInFrames * 1000.0 / sr } catch (t: Throwable) { 0.0 }
            var pauseApplied = false
            var zeroReads = 0
            var mirrorCounter = 0
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
                framesCaptured += n / 2

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
                    // Build #118: adaptive buffer — never keep a size that
                    // continuously underruns on this device.
                    if (bufferMode == "low" && underruns > 50) {
                        bufferMode = "balanced"
                        eng.setCaptureBufferMode("balanced")
                        routeNote = "Adaptive buffer: LOW to BALANCED (underruns detected)"
                    } else if (bufferMode == "balanced" && underruns > 250) {
                        bufferMode = "stable"
                        eng.setCaptureBufferMode("stable")
                        routeNote = "Adaptive buffer: BALANCED to STABLE (underruns detected)"
                    }
                    // Build #118: output route change -> safe reconfigure
                    // (STOP -> UPDATE FORMAT -> RESTART OUTPUT -> RESUME DSP).
                    if (routeDirty) {
                        routeDirty = false
                        val newSr = try {
                            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: sr
                        } catch (t: Throwable) { sr }
                        if (newSr != sr) {
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
                                    routeNote = AudioErrors.OUTPUT_ROUTE_CHANGED + ": output rebuilt at " + newSr + "Hz"
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
            stopSelf()
        }
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
