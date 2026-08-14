package com.neon.eq.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class EQService : Service() {

    companion object {
        const val CHANNEL_ID = "neon_eq_channel"
        const val NOTIF_ID = 1
    }

    private val engine = EqualizerEngine(this)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        engine.attachToGlobalSession()
        engine.setEnabled(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neon EQ Active")
            .setContentText("System-wide equalizer running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
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

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
