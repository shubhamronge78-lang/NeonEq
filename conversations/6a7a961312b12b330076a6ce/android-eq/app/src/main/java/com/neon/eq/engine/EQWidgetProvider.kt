package com.neon.eq.engine

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import com.neon.eq.R
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

// Home screen toggle widget. Tap it to flip the system-wide EQ on/off without
// opening the app. Same philosophy as the QS tile: the widget never touches the
// engine directly — it reads the persisted "enabled" flag for its label and
// delegates the actual toggle to EQService, which owns the engine lifecycle.
class EQWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE = "com.neon.eq.action.WIDGET_TOGGLE"

        // Push the current persisted state to every installed widget instance.
        // Called from EQService on every start/stop so the widget stays in sync
        // no matter where the toggle came from (app, tile, widget, boot).
        fun pushUpdate(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, EQWidgetProvider::class.java))
                if (ids.isEmpty()) return
                val enabled = context
                    .getSharedPreferences("neon_eq_state", Context.MODE_PRIVATE)
                    .getBoolean("enabled", true)
                val views = RemoteViews(context.packageName, R.layout.neon_widget).apply {
                    setTextViewText(R.id.widget_toggle, if (enabled) "ON" else "OFF")
                    setBoolean(R.id.widget_toggle, "setEnabled", !enabled)
                    setInt(R.id.widget_toggle, "setTextColor",
                        if (enabled) 0xFF00E5FF.toInt() else 0xFF6A6A7A.toInt())
                }
                views.setOnClickPendingIntent(R.id.widget_toggle, togglePendingIntent(context))
                mgr.updateAppWidget(ids, views)
            } catch (_: Throwable) { }
        }

        private fun togglePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, EQWidgetProvider::class.java).apply { action = ACTION_TOGGLE }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, 1, intent, flags)
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        pushUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        try {
            val prefs = context.getSharedPreferences("neon_eq_state", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("enabled", true)
            if (enabled) {
                val stop = Intent(context, EQService::class.java).apply { action = EQService.ACTION_STOP }
                context.startService(stop)
            } else {
                val start = Intent(context, EQService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start)
                else context.startService(start)
            }
        } catch (_: Throwable) {
            // Service start races (e.g. background restrictions) — still refresh
            // the widget so it reflects the true persisted state.
        }
        // EQService.start/stop pushes its own update; also refresh immediately
        // so the widget responds instantly to the tap.
        pushUpdate(context)
    }
}
