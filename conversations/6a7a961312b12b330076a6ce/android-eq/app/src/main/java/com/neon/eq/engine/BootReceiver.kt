package com.neon.eq.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

// Restarts the background EQ service after a reboot, so the system-wide equalizer
// keeps working without the user needing to reopen the app — but only if it was
// left in the "on" state before the reboot (respects the user's last choice).
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val engine = EqualizerEngine.getInstance(context)
            if (engine.isBoot()) {
                val serviceIntent = Intent(context, EQService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        } catch (_: Throwable) {
            // Best-effort — never crash a boot receiver.
        }
    }
}
