package com.neon.eq.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class EQService : Service() {

    companion object {
        const val CHANNEL_ID = "neon_eq_channel"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.neon.eq.action.STOP"
    }

    // Shared singleton — the SAME engine instance the Activity's UI is bound to.
    // This keeps the audio effects alive in the background even after the app UI
    // is closed, instead of dying with the Activity.
    private val engine by lazy { EqualizerEngine.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ACTION_STOP — explicit user "Turn Off" from notification or UI.
        if (intent?.action == ACTION_STOP) {
            engine.setEnabled(false)
            engine.release()
            EQWidgetProvider.pushUpdate(this)
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // System sticky restart (intent == null): the OS killed us for memory and is
        // recreating the service. We must NOT blindly force setEnabled(true) here —
        // the user may have turned the EQ off before the kill. Respect the persisted
        // state: if it was off, we re-attach the engine (so the UI stays in sync when
        // reopened) but keep effects disabled. If it was on, restore everything.
        val isStickyRestart = intent == null

        engine.attachToGlobalSession()
        // Build #90: only apply the last preset when the EQ will actually be ON —
        // a sticky restart into the OFF state used to push the curve to the
        // hardware first and then disable the effects anyway.
        val willBeEnabled = if (isStickyRestart) engine.isBoot() else true
        if (willBeEnabled) {
            // Auto-apply last preset on service start (boot, sticky restart) so the
            // EQ comes back with the right curve even without the UI being opened.
            engine.applyLastPreset()
        }
        engine.setEnabled(willBeEnabled)

        val stopIntent = Intent(this, EQService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neon EQ Active")
            .setContentText("System-wide equalizer running in the background")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Turn Off", stopPendingIntent)
            .build()
        startForeground(NOTIF_ID, notif)
        // Build #90: keep the shade honest — the notification now shows the LIVE
        // preset (and per-app profile when one engages) instead of a static
        // caption, and refreshes whenever the preset changes.
        isForeground = true
        engine.onPresetChanged = { refreshNotification() }
        // Keep any home screen widgets in sync with the engine's state —
        // covers app toggles, boot starts, tile clicks and widget clicks.
        EQWidgetProvider.pushUpdate(this)
        return START_STICKY
    }

    private var isForeground = false

    // Build #90: live notification content — preset (and active per-app profile,
    // when one engages) instead of a static caption.
    private fun buildNotification(): android.app.Notification {
        val stopIntent = Intent(this, EQService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val preset = try { engine.selectedPresetName } catch (_: Throwable) { "—" }
        val profile = try { engine.activeProfile() } catch (_: Throwable) { null }
        val text = if (profile != null) "Preset: $preset (profile: $profile)"
                   else "Preset: $preset"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neon EQ Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Turn Off", stopPendingIntent)
            .build()
    }

    private fun refreshNotification() {
        if (!isForeground) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Neon EQ",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Equalizer active notification" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    // Deliberately NOT releasing the engine here on generic onDestroy — the service
    // is only meant to release effects when explicitly stopped via ACTION_STOP above.
    // A plain onDestroy can happen for reasons outside our control (memory pressure);
    // we don't want an incidental service teardown to silently kill the user's EQ.
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Clear the engine-held callback so a destroyed service instance can't
        // be retained by the singleton engine.
        isForeground = false
        engine.onPresetChanged = null
        super.onDestroy()
    }
}
