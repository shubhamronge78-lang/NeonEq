package com.neon.eq.engine

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

// Quick Settings tile — flip the system-wide EQ on/off from the notification
// shade without opening the app. The tile never touches the audio engine
// directly; it only reads the persisted "enabled" flag for its own state and
// delegates the actual toggle to EQService, which owns the engine lifecycle.
// This keeps a cold tile click cheap and race-free even when the app process
// isn't running yet.
class EQTileService : TileService() {

    private fun readEnabled(): Boolean =
        getSharedPreferences("neon_eq_state", MODE_PRIVATE).getBoolean("enabled", true)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        if (readEnabled()) {
            // Off: route through the service so live effects release cleanly.
            // If the service isn't running it will start, handle ACTION_STOP
            // and stop itself — a harmless, self-cleaning path.
            val stop = Intent(this, EQService::class.java).apply { action = EQService.ACTION_STOP }
            try { startService(stop) } catch (_: Throwable) {}
        } else {
            // On: plain start flips the engine on and applies the persisted config.
            val start = Intent(this, EQService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(start)
                else startService(start)
            } catch (_: Throwable) {}
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        try {
            tile.label = "Neon EQ"
            tile.state = if (readEnabled()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        } catch (_: Throwable) {}
    }
}
