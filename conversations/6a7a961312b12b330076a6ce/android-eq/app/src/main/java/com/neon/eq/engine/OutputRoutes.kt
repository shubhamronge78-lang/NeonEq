package com.neon.eq.engine

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * v144 OUTPUT STUDIO — Android glue over OutputContract.
 *
 * HONEST SCOPE (spec §2/§8/§40): Android public APIs do NOT let a third-party
 * app force-select the media output route, and do NOT expose simultaneous
 * multi-route media output. Output Studio therefore:
 *   - DISCOVERS real outputs via AudioManager.getDevices(GET_DEVICES_OUTPUTS)
 *   - DISPLAYS honest per-route capabilities (supported rates/channels from
 *     AudioDeviceInfo; anything Android does not report is labelled so)
 *   - SURVIVES + VERIFIES route changes via the existing CaptureEqService
 *     rebuild path (v136) — no second audio path, no second DSP engine
 *   - OFFERS the system output switcher (Bluetooth/output settings deep-link)
 *     instead of pretending app-level routing control Android never granted
 *   - PER-OUTPUT EQ PROFILES reuse the ONE existing DSP state, applied
 *     atomically through the existing preset apply path
 */
object OutputRoutes {

    data class Route(
        val key: String,
        val productName: String?,
        val category: Int,
        val sampleRates: IntArray,
        val channelCount: Int,
        val isActive: Boolean
    ) {
        fun label(ctx: Context): String = displayNameWith(ctx)
        private fun displayNameWith(ctx: Context): String =
            OutputContract.displayName(key, productName, nicknames(ctx))
    }

    // ── classification (public API types → contract categories) ──
    fun categoryOf(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputContract.CAT_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputContract.CAT_EARPIECE
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_BUS -> OutputContract.CAT_WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputContract.CAT_USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> OutputContract.CAT_BLUETOOTH
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_HEADPHONES -> OutputContract.CAT_LE_AUDIO
        else -> OutputContract.CAT_OTHER
    }

    fun devices(ctx: Context): List<Route> {
        val routes = mutableListOf<Route>()
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return emptyList()
            val bestKey = activeKey(ctx)
            for (d in devs) {
                val cat = categoryOf(d.type)
                if (cat == OutputContract.CAT_OTHER) continue
                val rates = try { d.sampleRates ?: IntArray(0) } catch (_: Throwable) { IntArray(0) }
                val ch = try { d.channelCounts.maxOrNull() ?: 0 } catch (_: Throwable) { 0 }
                val name = try { d.productName?.toString() } catch (_: Throwable) { null }
                val key = OutputContract.routeKey(cat, name)
                if (routes.any { it.key == key }) continue
                routes.add(Route(key, name, cat, rates, ch, key == bestKey))
            }
        } catch (_: Throwable) { return emptyList() }
        return routes.sortedBy { OutputContract.categoryPriority(it.category) }
    }

    /** The one route Android is currently sending media to, by honest priority
     *  (same ordering AudioPath uses). Null = none reported. */
    fun activeKey(ctx: Context): String? {
        val list = devices(ctx)
        return list.firstOrNull { it.category != OutputContract.CAT_EARPIECE }?.key ?: list.firstOrNull()?.key
    }

    fun activeRoute(ctx: Context): Route? {
        val k = activeKey(ctx) ?: return null
        return devices(ctx).firstOrNull { it.key == k }
    }

    /** Human label for status lines: nickname > product name > category name. */
    fun activeRouteLabel(ctx: Context): String {
        val r = activeRoute(ctx) ?: return "ROUTE UNKNOWN"
        return OutputContract.displayName(r.key, r.productName, nicknames(ctx))
    }

    // ── prefs-backed favs / nicknames / profiles / history ──
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("output_studio", Context.MODE_PRIVATE)

    fun favorites(ctx: Context): Set<String> =
        try { prefs(ctx).getStringSet("favs", emptySet()) ?: emptySet() } catch (_: Throwable) { emptySet() }

    fun toggleFavorite(ctx: Context, key: String): Boolean {
        val f = favorites(ctx).toMutableSet()
        val now = if (f.contains(key)) { f.remove(key); false } else { f.add(key); true }
        try { prefs(ctx).edit().putStringSet("favs", f).apply() } catch (_: Throwable) {}
        return now
    }

    fun nicknames(ctx: Context): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val raw = prefs(ctx).getString("nicknames", null) ?: return map
            val o = JSONObject(raw)
            o.keys().forEach { k -> o.optString(k)?.let { v -> if (v.isNotBlank()) map[k] = v } }
        } catch (_: Throwable) {}
        return map
    }

    fun setNickname(ctx: Context, key: String, nickname: String?): Boolean {
        val map = nicknames(ctx).toMutableMap()
        val clean = OutputContract.sanitizeNickname(nickname)
        if (clean == null) map.remove(key) else map[key] = clean
        return try { prefs(ctx).edit().putString("nicknames", JSONObject(map as Map<*, *>).toString()).apply(); true } catch (_: Throwable) { false }
    }

    fun outputProfiles(ctx: Context): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val raw = prefs(ctx).getString("profiles", null) ?: return map
            val o = JSONObject(raw)
            o.keys().forEach { k -> val v = o.optString(k); if (v.isNotBlank()) map[k] = v }
        } catch (_: Throwable) {}
        return map
    }

    fun setOutputProfile(ctx: Context, key: String, presetName: String?) {
        val map = outputProfiles(ctx).toMutableMap()
        val clean = presetName?.trim().orEmpty()
        if (clean.isEmpty()) map.remove(key) else map[key] = clean
        try { prefs(ctx).edit().putString("profiles", JSONObject(map as Map<*, *>).toString()).apply() } catch (_: Throwable) {}
    }

    fun followSystem(ctx: Context): Boolean =
        try { prefs(ctx).getBoolean("follow", true) } catch (_: Throwable) { true }

    fun setFollowSystem(ctx: Context, on: Boolean) {
        try { prefs(ctx).edit().putBoolean("follow", on).apply() } catch (_: Throwable) {}
    }

    fun preferredOutput(ctx: Context): String? =
        try { prefs(ctx).getString("preferred", null) } catch (_: Throwable) { null }

    fun setPreferredOutput(ctx: Context, key: String?) {
        try { prefs(ctx).edit().putString("preferred", key).apply() } catch (_: Throwable) {}
    }

    fun profileFor(ctx: Context, key: String): String? =
        OutputContract.profileFor(outputProfiles(ctx), key)

    fun history(ctx: Context): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        try {
            val raw = prefs(ctx).getString("history", null) ?: return out
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val t = o.optLong("t", 0L)
                val n = o.optString("r", "")
                if (t > 0 && n.isNotBlank()) out.add(t to n)
            }
        } catch (_: Throwable) {}
        return out
    }

    /** Called from a UI thread (never the audio thread). Bounded, no disk IO
     *  from any callback that audio runs on. */
    fun rememberRoute(ctx: Context, routeName: String) {
        val updated = OutputContract.rememberHistory(history(ctx), System.currentTimeMillis() to routeName)
        try {
            val a = JSONArray()
            updated.forEach { (t, r) -> a.put(JSONObject().put("t", t).put("r", r)) }
            prefs(ctx).edit().putString("history", a.toString()).apply()
        } catch (_: Throwable) {}
    }

    // ── live route-change log (in-memory ring, diagnostics only) ──
    private val log = ArrayDeque<Pair<Long, String>>()

    @Synchronized
    fun note(msg: String) {
        val now = System.currentTimeMillis()
        if (log.lastOrNull()?.second == msg) return
        log.addLast(now to msg)
        while (log.size > 12) log.removeFirst()
    }

    @Synchronized
    fun logEntries(): List<Pair<Long, String>> = log.toList()

    /** Lifecycle-safe registration: caller MUST unregister in onDispose. */
    fun registerCallback(ctx: Context, cb: AudioDeviceCallback): Boolean = try {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerAudioDeviceCallback(cb, null)
        true
    } catch (_: Throwable) { false }

    fun unregisterCallback(ctx: Context, cb: AudioDeviceCallback) {
        try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(cb) } catch (_: Throwable) {}
    }
}
