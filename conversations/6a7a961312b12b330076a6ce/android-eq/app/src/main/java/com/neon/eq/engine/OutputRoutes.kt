package com.neon.eq.engine

import android.app.ActivityManager
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * v145 PERFORMANCE + STABILITY HARDENING of the v144 Output Studio glue.
 *
 * ROOT CAUSE FIXED (found in v144): devices() called activeKey(), and
 * activeKey() called devices() — mutual recursion on every route event and
 * status tick, plus 2-4 redundant full device scans per event. This rewrite:
 *  - ONE devices() scan per snapshot (buildSnapshot), zero recursion
 *  - ONE immutable OutputUiState published atomically (§6), deduplicated by
 *    structural equality (§5) — callers simply compare before setting state
 *  - debounced/coalesced event handling upstream in MainActivity (§4)
 *  - prefs (favorites/nicknames/profiles/history) cached in memory;
 *    JSON parsing happens once, not on every lookup or route event (§18/§25)
 *  - instrumentation counters (§35): builds, skipped-duplicate builds,
 *    last enumeration duration — never touched from the audio thread
 *
 * Unchanged: honest scope (no app-forced routing, multi-output honestly
 * unavailable, system-settings hand-off), lifecycle-safe callback
 * registration/unregistration, audio path untouched.
 */
object OutputRoutes {

    // ── v145: in-memory prefs caches (main-thread only; route callbacks run
    // on the main thread per AudioManager's callback contract) ──
    @Volatile private var favCache: Set<String>? = null
    @Volatile private var nickCache: Map<String, String>? = null
    @Volatile private var profCache: Map<String, String>? = null
    @Volatile private var histCache: List<Pair<Long, String>>? = null
    @Volatile private var followCache: Boolean? = null
    @Volatile private var prefCache: String? = null
    @Volatile private var prefLoaded = false
    @Volatile private var perfCache: Int? = null

    // ── §27 rotation safety: survives Activity recreation, so a rotation
    // never re-applies an output profile (lastKey check in the consumer) ──
    @Volatile var lastReportedKey: String? = null

    // ── §35 instrumentation (development diagnostics; no log spam) ──
    @Volatile var snapshotBuilds: Int = 0
    @Volatile var snapshotSkips: Int = 0
    @Volatile var lastEnumMs: Int = 0

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("output_studio", Context.MODE_PRIVATE)

    fun categoryOf(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputContract.CAT_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputContract.CAT_EARPIECE
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> OutputContract.CAT_WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputContract.CAT_USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> OutputContract.CAT_BLUETOOTH
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> OutputContract.CAT_LE_AUDIO
        else -> OutputContract.CAT_OTHER
    }

    /**
     * ONE scan of the output device list. No active-route logic here (v144
     * recursion bug), no capability probing, no disk access — safe to call
     * from a route callback. Crash-safe (§32): any Throwable → empty list.
     */
    fun devices(ctx: Context): List<OutputContract.RouteUi> {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return emptyList()
            val out = mutableListOf<OutputContract.RouteUi>()
            for (d in devs) {
                val cat = categoryOf(d.type)
                if (cat == OutputContract.CAT_OTHER) continue
                val name = try { d.productName?.toString() } catch (_: Throwable) { null }
                val key = OutputContract.routeKey(cat, name)
                if (out.any { it.key == key }) continue
                val rates = try { d.sampleRates ?: IntArray(0) } catch (_: Throwable) { IntArray(0) }
                val ch = try { d.channelCounts.maxOrNull() ?: 0 } catch (_: Throwable) { 0 }
                out.add(OutputContract.RouteUi(key, name, cat, rates.maxOrNull() ?: 0, ch))
            }
            out
        } catch (_: Throwable) { emptyList() }
    }

    /**
     * §6: the single atomic UI snapshot. ONE devices() scan total. Never
     * throws (§32): any unexpected Android behavior → EMPTY, "WAITING FOR
     * OUTPUT" — never a crash and never a fake ACTIVE state.
     */
    fun buildSnapshot(ctx: Context): OutputContract.OutputUiState {
        val t0 = android.os.SystemClock.uptimeMillis()
        snapshotBuilds++
        return try {
            val list = devices(ctx)
            val idx = OutputContract.activeIndex(list.map { it.category })
            val routes = if (idx == null) list else list.mapIndexed { i, r -> if (i == idx) r.copy(isActive = true) else r }
            val nicks = nicknames(ctx)
            val active = idx?.let { routes[it] }
            val activeName = if (active != null)
                OutputContract.displayName(active.key, active.productName, nicks)
            else "WAITING FOR OUTPUT"
            OutputContract.OutputUiState(
                activeName = activeName,
                activeKey = active?.key,
                routes = routes,
                fallbackNote = OutputContract.fallbackNote(routes, idx),
                profileName = if (active != null) OutputContract.profileFor(profiles(ctx), active.key) else null,
                followSystem = followSystem(ctx)
            )
        } catch (_: Throwable) {
            OutputContract.OutputUiState.EMPTY
        } finally {
            lastEnumMs = (android.os.SystemClock.uptimeMillis() - t0).toInt()
        }
    }

    /** Legacy label helper — now snapshot-based (single scan, no recursion). */
    fun activeRouteLabel(ctx: Context): String = buildSnapshot(ctx).activeName

    // ── cached prefs accessors: JSON parsed once per process, updated on write ──
    fun favorites(ctx: Context): Set<String> {
        favCache?.let { return it }
        val v = try { prefs(ctx).getStringSet("favs", emptySet()) ?: emptySet() } catch (_: Throwable) { emptySet() }
        favCache = v
        return v
    }

    fun toggleFavorite(ctx: Context, key: String): Boolean {
        val f = favorites(ctx).toMutableSet()
        val now = if (f.contains(key)) { f.remove(key); false } else { f.add(key); true }
        favCache = f
        try { prefs(ctx).edit().putStringSet("favs", f).apply() } catch (_: Throwable) {}
        return now
    }

    fun nicknames(ctx: Context): Map<String, String> {
        nickCache?.let { return it }
        val map = mutableMapOf<String, String>()
        try {
            val raw = prefs(ctx).getString("nicknames", null)
            if (raw != null) {
                val o = JSONObject(raw)
                o.keys().forEach { k -> o.optString(k)?.let { v -> if (v.isNotBlank()) map[k] = v } }
            }
        } catch (_: Throwable) {}
        nickCache = map
        return map
    }

    fun setNickname(ctx: Context, key: String, nickname: String?): Boolean {
        val map = nicknames(ctx).toMutableMap()
        val clean = OutputContract.sanitizeNickname(nickname)
        if (clean == null) map.remove(key) else map[key] = clean
        nickCache = map
        return try { prefs(ctx).edit().putString("nicknames", JSONObject(map as Map<*, *>).toString()).apply(); true } catch (_: Throwable) { false }
    }

    fun profiles(ctx: Context): Map<String, String> {
        profCache?.let { return it }
        val map = mutableMapOf<String, String>()
        try {
            val raw = prefs(ctx).getString("profiles", null)
            if (raw != null) {
                val o = JSONObject(raw)
                o.keys().forEach { k -> val v = o.optString(k); if (v.isNotBlank()) map[k] = v }
            }
        } catch (_: Throwable) {}
        profCache = map
        return map
    }

    fun setOutputProfile(ctx: Context, key: String, presetName: String?) {
        val map = profiles(ctx).toMutableMap()
        val clean = presetName?.trim().orEmpty()
        if (clean.isEmpty()) map.remove(key) else map[key] = clean
        profCache = map
        try { prefs(ctx).edit().putString("profiles", JSONObject(map as Map<*, *>).toString()).apply() } catch (_: Throwable) {}
    }

    fun profileFor(ctx: Context, key: String): String? =
        OutputContract.profileFor(profiles(ctx), key)

    fun followSystem(ctx: Context): Boolean {
        followCache?.let { return it }
        val v = try { prefs(ctx).getBoolean("follow", true) } catch (_: Throwable) { true }
        followCache = v
        return v
    }

    fun setFollowSystem(ctx: Context, on: Boolean) {
        followCache = on
        try { prefs(ctx).edit().putBoolean("follow", on).apply() } catch (_: Throwable) {}
    }

    fun preferredOutput(ctx: Context): String? {
        if (prefLoaded) return prefCache
        prefCache = try { prefs(ctx).getString("preferred", null) } catch (_: Throwable) { null }
        prefLoaded = true
        return prefCache
    }

    fun setPreferredOutput(ctx: Context, key: String?) {
        prefCache = key
        prefLoaded = true
        try { prefs(ctx).edit().putString("preferred", key).apply() } catch (_: Throwable) {}
    }

    fun history(ctx: Context): List<Pair<Long, String>> {
        histCache?.let { return it }
        val out = mutableListOf<Pair<Long, String>>()
        try {
            val raw = prefs(ctx).getString("history", null)
            if (raw != null) {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val t = o.optLong("t", 0L)
                    val n = o.optString("r", "")
                    if (t > 0 && n.isNotBlank()) out.add(t to n)
                }
            }
        } catch (_: Throwable) {}
        histCache = out
        return out
    }

    /** Main-thread only (route callbacks arrive there); bounded; cheap. */
    fun rememberRoute(ctx: Context, routeName: String) {
        val updated = OutputContract.rememberHistory(history(ctx), System.currentTimeMillis() to routeName)
        histCache = updated
        try {
            val a = JSONArray()
            updated.forEach { (t, r) -> a.put(JSONObject().put("t", t).put("r", r)) }
            prefs(ctx).edit().putString("history", a.toString()).apply()
        } catch (_: Throwable) {}
    }

    // ── §31 PERFORMANCE MODE: UI-only setting, DSP quality never changes ──
    fun perfMode(ctx: Context): OutputContract.PerfMode = when (perfCache) {
        1 -> OutputContract.PerfMode.QUALITY
        2 -> OutputContract.PerfMode.PERFORMANCE
        else -> try {
            val v = prefs(ctx).getInt("perf_mode", 0)
            perfCache = v
            when (v) {
                1 -> OutputContract.PerfMode.QUALITY
                2 -> OutputContract.PerfMode.PERFORMANCE
                else -> OutputContract.PerfMode.AUTO
            }
        } catch (_: Throwable) { OutputContract.PerfMode.AUTO }
    }

    fun setPerfMode(ctx: Context, mode: OutputContract.PerfMode) {
        val v = when (mode) {
            OutputContract.PerfMode.QUALITY -> 1
            OutputContract.PerfMode.PERFORMANCE -> 2
            else -> 0
        }
        perfCache = v
        try { prefs(ctx).edit().putInt("perf_mode", v).apply() } catch (_: Throwable) {}
    }

    /** Low-RAM detection for AUTO mode; any failure → conservative default. */
    private fun isLowRam(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    } catch (_: Throwable) { false }

    /** §14: visual meter update interval — UI only, audio stays real-time. */
    fun meterIntervalMs(ctx: Context): Int =
        OutputContract.meterIntervalMs(perfMode(ctx), isLowRam(ctx))

    // ── in-memory route-change log (bounded ring, diagnostics only) ──
    private val log = ArrayDeque<Pair<Long, String>>()

    @Synchronized
    fun note(msg: String) {
        if (log.lastOrNull()?.second == msg) return
        log.addLast(System.currentTimeMillis() to msg)
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
