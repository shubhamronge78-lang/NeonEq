package com.neon.eq.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaProjection
import android.media.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.neon.eq.R
import com.neon.eq.dsp.NeonDsp

/**
 * Build #116: the no-root system-audio path.
 *
 * Android 10+ AudioPlaybackCapture: with the user's MediaProjection consent,
 * an app may capture OTHER apps' media playback, process it, and play it back.
 * This is a public, sanctioned API — Vivo cannot (and does not) block it.
 *
 * The honest limitation (stated in-app, not hidden): Android plays the
 * ORIGINAL audio and our processed copy simultaneously — no public API lets
 * one app silence or redirect another app's output. DRM content, calls, and
 * apps that opt out of capture are excluded by Android itself.
 */
class CaptureEqService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var framesDone = 0L
        @Volatile var underruns = 0
        @Volatile var latencyMs = 0.0
        @Volatile var lastError: String? = null
        @Volatile var captureSampleRate = 48000
        @Volatile var resultCode = 0
        @Volatile var resultData: Intent? = null
        @Volatile private var requestStop = false

        const val ACTION_STOP = "com.neon.eq.capture.STOP"
        const val CHANNEL_ID = "neoneq_capture"
        const val NOTIF_ID = 42

        fun line(): String =
            (if (running) "ACTIVE" else "off") + " · frames:" + framesDone +
                " · underruns:" + underruns + " · " + captureSampleRate + "Hz" +
                (if (running) " · ~" + "%.0f".format(latencyMs) + "ms" else "") +
                (lastError?.let { " · ERR:$it" } ?: "")
    }

    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NeonEq Active")
            .setContentText("Capturing & processing playback — no-root system path")
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            lastError = "foreground: " + (t.message ?: t.toString())
        }

        if (intent?.action == ACTION_STOP) {
            requestStop = true
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running && thread?.isAlive != true) {
            requestStop = false
            lastError = null
            framesDone = 0L
            underruns = 0
            thread = Thread { captureLoop() }.apply { start() }
        }
        return START_NOT_STICKY
    }

    private fun captureLoop() {
        var recorder: AudioRecord? = null
        var track: AudioTrack? = null
        var projection: MediaProjection? = null
        var wl: PowerManager.WakeLock? = null
        try {
            val data = resultData
            if (data == null || Build.VERSION.SDK_INT < 29) {
                lastError = "playback capture needs Android 10+ and projection consent"
                return
            }
            // The service is already foreground with type mediaProjection —
            // required on Android 14+ BEFORE requesting the projection.
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = try {
                mpm.getMediaProjection(resultCode, data)
            } catch (t: Throwable) {
                lastError = "projection refused: " + (t.message ?: t.toString()); null
            } ?: return

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { requestStop = true }
            }, null)

            val sr = 48000
            captureSampleRate = sr

            val captureConfig = AudioPlaybackCaptureConfiguration(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

            val minIn = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(maxOf(minIn * 2, 16384))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

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
                .setBufferSizeInBytes(maxOf(minOut * 2, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try { track.performanceMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY } catch (t: Throwable) { }

            // Mirror the live engine curve into the native DSP chain.
            try {
                if (NeonDsp.available) {
                    val eng = com.neon.eq.engine.EqualizerEngine.getInstance(applicationContext)
                    NeonDsp.init(sr, 10)
                    val gains = FloatArray(10) { i -> (eng.currentBandLevels.getOrNull(i)?.toInt() ?: 0).toFloat() }
                    NeonDsp.setGraphicGains(gains)
                    NeonDsp.setPreamp(eng.loudnessAppliedMb(eng.currentLoudness) / 100f)
                    NeonDsp.setShelves(0f, 0f)
                    NeonDsp.setStereo(1f, 0f, false, false)
                    NeonDsp.setCompressor(false, -18f, 4f, 5f, 150f)
                    NeonDsp.setLimiter(true, -1f)
                    NeonDsp.setConvolverEnabled(false)
                    NeonDsp.resetStats()
                }
            } catch (t: Throwable) { lastError = "dsp setup: " + (t.message ?: t.toString()) }

            recorder.startRecording()
            track.play()
            running = true

            wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "neoneq:capture")
            try { wl.acquire(60 * 60 * 1000L) } catch (t: Throwable) { }

            // 20 ms chunks — small enough for low latency, large enough to
            // avoid underruns on slow CPUs (Redmi 10C class).
            val chunk = ShortArray(960 * 2)
            while (!requestStop) {
                val t0 = System.nanoTime()
                val n = recorder.read(chunk, 0, chunk.size)
                if (n < 0) { lastError = "capture read failed: $n"; break }
                if (n == 0) { Thread.sleep(10); continue }
                if (NeonDsp.available) {
                    try { NeonDsp.process(chunk, n / 2) } catch (t: Throwable) { }
                }
                track.write(chunk, 0, n)
                framesDone += n / 2
                latencyMs = (System.nanoTime() - t0) / 1e6 +
                    (track.bufferSizeInFrames.toDouble() / sr * 1000.0)
                if (framesDone % 48000L < 960L) {
                    try { underruns = track.underrunCount } catch (t: Throwable) { }
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

    private fun stopPendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getService(
            this, 1,
            Intent(this, CaptureEqService::class.java).setAction(ACTION_STOP),
            flags)
    }
}
