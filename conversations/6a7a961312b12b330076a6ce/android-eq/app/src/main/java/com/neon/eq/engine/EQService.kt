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
        if (isStickyRestart) {
            // Restore persisted state without forcing it on.
            if (engine.isBoot()) {
                engine.setEnabled(true)
            } else {
                engine.setEnabled(false)
            }
        } else {
            // User-initiated start (from Activity or BootReceiver) — turn it on.
            engine.setEnabled(true)
        }

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
        return START_STICKY
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
}
