package com.neon.eq.engine

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.*
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class EqualizerEngine private constructor(context: Context) {

    // Always hold applicationContext internally — this engine is now a long-lived
    // singleton shared between the Activity and the background service, so holding
    // an Activity context here would leak it every time the screen rotates or closes.
    private val context: Context = context.applicationContext

    companion object {
        const val MAX_BANDS = 31
        const val MIN_BANDS = 5
        const val BASS_BOOST_STRENGTH_MAX = 300
        const val VIRTUALIZER_STRENGTH_MAX = 300
        private const val TAG = "NeonEQ"
        private const val DB_TO_MILLIBEL = 100
        private const val POLL_INTERVAL_MS = 1500L

        private const val PREFS_NAME = "neon_eq_state"
        private const val KEY_BAND_COUNT = "band_count"
        private const val KEY_LEVELS = "levels"
        private const val KEY_BASS = "bass"
        private const val KEY_VIRT = "virt"
        private const val KEY_LOUD = "loud"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRESET_NAME = "preset_name"
        private const val KEY_CUSTOM_PRESETS = "custom_presets"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_AUTO_APPLY_PRESET = "auto_apply_preset"
        private const val KEY_SHOW_VISUALIZER = "show_visualizer"
        private const val KEY_SHOW_GLOW = "show_glow"
        private const val KEY_VIS_STYLE = "vis_style"
        private const val KEY_APP_PROFILES = "app_profiles"

        @Volatile private var instance: EqualizerEngine? = null

        // Shared singleton: the Activity (for UI) and EQService (for background
        // persistence) must control the SAME set of audio effects, not two competing
        // instances. Without this, closing the app used to kill the effects outright
        // (Activity.onDestroy called engine.release()), which defeats the entire
        // point of a "system-wide" equalizer — it only worked while the app was open.
        fun getInstance(context: Context): EqualizerEngine =
            instance ?: synchronized(this) {
                instance ?: EqualizerEngine(context).also { instance = it }
            }
    }

    private val prefs: SharedPreferences =
        this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NeonEQ-Audio").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollHandler = Handler(Looper.getMainLooper())

    private data class SessionFX(
        val sessionId: Int,
        val equalizer: Equalizer,
        val bassBoost: BassBoost?,
        val virtualizer: Virtualizer?,
        val loudnessEnhancer: LoudnessEnhancer?,
        // Build #68: this session's OWN band map (hardware band j samples the UI
        // curve at bandPos[j]) and level range — captured at attach. Sessions
        // can expose a different band layout than the global EQ on MIUI.
        val bandPos: FloatArray = FloatArray(0),
        val minLevel: Short = -1500,
        val maxLevel: Short = 1500
    )
    private val activeFX = ConcurrentHashMap<Int, SessionFX>()

    // Global session 0 effects
    private var globalEQ: Equalizer? = null
    private var globalBassBoost: BassBoost? = null
    private var globalVirtualizer: Virtualizer? = null
    private var globalLoudness: LoudnessEnhancer? = null

    // Real-time spectrum visualizer, attached to global session 0 alongside the EQ.
    // Best-effort only — some devices/ROMs won't allow it, we degrade silently.
    private var visualizer: Visualizer? = null
    var onWaveform: ((ByteArray) -> Unit)? = null

    // Build #58: MIUI silently stops Visualizer callbacks (screen-off, track
    // change, output switch) without any error — the object stays "enabled" but
    // delivers nothing. lastWaveformAt lets the session poller detect a stalled
    // capture and re-attach it; visRetryCount grows the retry backoff so a
    // device that never delivers data doesn't get hammered.
    @Volatile private var lastWaveformAt = 0L
    // @Volatile: written by the audio capture thread (reset on every healthy
    // callback) and by the session-poll thread (incremented on stall retries).
    @Volatile private var visRetryCount = 0

    @Volatile var bandCount = prefs.getInt(KEY_BAND_COUNT, 5)
        private set
    @Volatile var bands: List<BandInfo> = emptyList()
    @Volatile var enabled = false
        private set
    @Volatile var isReady = false
    @Volatile var statusMessage = "Initializing..."
    @Volatile var activeSessionCount = 0
    @Volatile var selectedPresetName: String = prefs.getString(KEY_PRESET_NAME, "Flat") ?: "Flat"
        private set

    private var currentBandLevels = loadLevels()
    private var currentBassBoost = prefs.getInt(KEY_BASS, 150).coerceIn(0, 300)
    private var currentVirtualizer = prefs.getInt(KEY_VIRT, 150).coerceIn(0, 300)
    private var currentLoudness = prefs.getInt(KEY_LOUD, 150).coerceIn(0, 300)
    private var currentEnabled = prefs.getBoolean(KEY_ENABLED, true)

    // Custom setter: if the engine already finished init (isReady/watchdog fired)
    // BEFORE Compose got around to subscribing — very possible on slow first-launch
    // CPUs like the Redmi 10c's — immediately replay the last known state to the new
    // subscriber instead of silently dropping it. Without this, a late subscriber
    // means the UI waits forever for a callback that already happened and went nowhere.
    var onReady: ((Boolean, String, List<BandInfo>) -> Unit)? = null
        set(value) {
            field = value
            if (isReady) {
                value?.invoke(isReady, statusMessage, bands)
            }
        }
    var onSessionUpdate: ((Int) -> Unit)? = null

    data class BandInfo(val index: Int, val freq: Int, val minLevel: Short, val maxLevel: Short)

    private fun loadLevels(): ShortArray {
        val raw = prefs.getString(KEY_LEVELS, null)
        val arr = ShortArray(31) { 0 }
        if (raw != null) {
            try {
                raw.split(",").forEachIndexed { i, s -> if (i < 31) arr[i] = s.toShort() }
            } catch (_: Throwable) { }
        }
        return arr
    }

    // Build #68: persistence debounce. Slider drags fire setBandLevel / fx
    // setters per animation frame (up to 60Hz) — each event used to build a
    // 31-value string and schedule its own prefs write. Now coalesced: at most
    // one levels write and one batched scalar write per 200ms, always carrying
    // the latest values.
    private val persistHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val persistLevelsRunnable = Runnable {
        try {
            prefs.edit().putString(KEY_LEVELS, currentBandLevels.joinToString(",")).apply()
        } catch (_: Throwable) { }
    }
    private fun persistLevels() {
        persistHandler.removeCallbacks(persistLevelsRunnable)
        persistHandler.postDelayed(persistLevelsRunnable, 200L)
    }

    private val dirtyScalars = ConcurrentHashMap<String, Int>()
    private val persistScalarsRunnable = Runnable {
        val batch = ArrayList<Map.Entry<String, Int>>()
        val it = dirtyScalars.entries.iterator()
        while (it.hasNext()) { val e = it.next(); batch.add(e); it.remove() }
        if (batch.isEmpty()) return@Runnable
        try {
            val ed = prefs.edit()
            for (e in batch) ed.putInt(e.key, e.value)
            ed.apply()
        } catch (_: Throwable) { }
    }
    private fun persistScalar(key: String, value: Int) {
        dirtyScalars[key] = value
        persistHandler.removeCallbacks(persistScalarsRunnable)
        persistHandler.postDelayed(persistScalarsRunnable, 200L)
    }

    // Pre-amp compensation: bass boost and very hot band boosts can push the output
    // past a small speaker's headroom. We subtract a small offset from the levels sent
    // to the hardware; the UI still shows the user's intended values.
    // Build #58 fix: LoudnessEnhancer and Virtualizer are NOT compensated anymore.
    // Loudness is the user explicitly asking for more volume — subtracting gain for
    // it made "EQ on" quieter than "EQ off" with the default 150/150/150 settings
    // (reported on Redmi 10C). The virtualizer is a stereo-width effect with
    // negligible level gain. Bass boost (the actual distortion source) is still
    // compensated but at half the old rate, and band boosts only count above +6dB —
    // so typical settings are now transparent, and only extreme shapes get trimmed.
    private fun computePreampDb(): Int {
        var preamp = 0
        if (currentBassBoost > 0) preamp += currentBassBoost / 200   // ~0.5dB per 100 strength
        var maxBand = 0
        for (i in currentBandLevels.indices) {
            val v = currentBandLevels[i].toInt()
            if (v > maxBand) maxBand = v
        }
        if (maxBand > 6) preamp += (maxBand - 6) / 2                  // half the excess above +6dB
        return preamp.coerceAtMost(4)  // never reduce by more than -4dB total
    }

    private fun applyPreamp(level: Int): Short {
        val adjusted = (level - computePreampDb()).coerceIn(-15, 15)
        return (adjusted * DB_TO_MILLIBEL).toShort()
    }

    // Build #59: ramped band transitions. Hard gain jumps (preset switch, per-app
    // profile auto-switch, auto-apply on startup) click/pop on many devices and
    // sound harsh on live audio. We ease the hardware curve from the old shape
    // to the new one across ~120ms with a smoothstep profile instead. The
    // generation counter lets a newer ramp — or a direct band drag — cancel the
    // in-flight one, so user input always wins.
    @Volatile private var rampGeneration = 0

    private fun rampBands(from: IntArray, to: IntArray) {
        val gen = ++rampGeneration
        rampActiveUntil = SystemClock.elapsedRealtime() + 250L  // heal must not hard-jump the glide
        audioExecutor.execute {
            var maxDelta = 0
            for (i in to.indices) {
                val d = kotlin.math.abs(to[i] - from.getOrElse(i) { 0 })
                if (d > maxDelta) maxDelta = d
            }
            // Micro-adjustments (<=2dB) apply instantly — no perceptible click,
            // and ramping them would just add lag.
            if (maxDelta <= 2) { applyBands(to); return@execute }
            val steps = 8
            for (s in 1..steps) {
                if (rampGeneration != gen) return@execute  // superseded — newer ramp or drag
                val t = s.toFloat() / steps
                val eased = t * t * (3f - 2f * t)   // smoothstep: soft start & end
                val frame = IntArray(to.size) { i ->
                    val start = from.getOrElse(i) { 0 }
                    start + ((to[i] - start) * eased).toInt()
                }
                applyBands(frame)
                if (s < steps) try { Thread.sleep(15) } catch (_: InterruptedException) { return@execute }
            }
        }
    }

    // Build #61: on-device diagnostics. No adb on the Redmi 10C — this surfaces
    // the live session-attach internals so we can see exactly what MIUI is
    // doing when the EQ goes silent. Polled once a second from the Settings
    // diagnostics panel; reads are cheap and Throwable-caught throughout.
    fun diagnostics(): String {
        val sb = StringBuilder()
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val configs = try { am.getActivePlaybackConfigurations() } catch (_: Throwable) { emptyList<AudioPlaybackConfiguration>() }
            val pkgs = configs.mapNotNull { resolvePlayingPackage(it) }.distinct()
            sb.append("configs: ").append(configs.size)
            sb.append(" | playing: ").append(if (pkgs.isEmpty()) "—" else pkgs.joinToString(","))

            val sessionList = activeFX.keys.sorted()
            sb.append("\nsessions attached: ").append(if (sessionList.isEmpty()) "—" else sessionList.joinToString(","))
            sb.append("\nglobalEQ: ").append(if (globalEQ != null) "attached" else "null")
            sb.append(" | enabled: ").append(enabled)

            val anyEQ = activeFX.values.firstOrNull()?.equalizer ?: globalEQ
            val hwBands = try { anyEQ?.numberOfBands?.toInt() ?: 0 } catch (_: Throwable) { 0 }
            sb.append("\nUI bands: ").append(bandCount).append(" | hw bands: ").append(hwBands)

            // Read back the ACTUAL hardware gain on UI band 0 — proves whether
            // the device is applying our levels or silently ignoring them.
            // BandInfo.index is Int; the AudioEffect API takes Short.
            val idx: Short = (bands.getOrNull(0)?.index ?: -1).toShort()
            val sessionEq = activeFX.values.firstOrNull()?.equalizer
            val sessionLvl = try { if (idx >= 0) sessionEq?.getBandLevel(idx) else null } catch (_: Throwable) { null }
            val globalEqLocal = globalEQ  // local copy so Kotlin can smart-cast
            val globalLvl = try { if (idx >= 0) globalEqLocal?.getBandLevel(idx) else null } catch (_: Throwable) { null }
            sb.append("\nband0 readback: session=")
            sb.append(if (sessionLvl != null) "${sessionLvl / 100}dB" else "n/a")
            sb.append(" global=").append(if (globalLvl != null) "${globalLvl / 100}dB" else "n/a")
            sb.append(" | set=").append(currentBandLevels.getOrNull(0) ?: 0)
            sb.append("dB preamp=").append(computePreampDb()).append("dB")

            val fxEnabled = try { sessionEq?.enabled } catch (_: Throwable) { null }
            sb.append("\nsession EQ enabled: ").append(fxEnabled ?: "n/a")
            sb.append(" | heals: ").append(healCount)
            val fxProbe = activeFX.values.firstOrNull()
            val bRead = try { fxProbe?.bassBoost?.properties?.strength?.toInt() } catch (_: Throwable) { null }
            val vRead = try { fxProbe?.virtualizer?.properties?.strength?.toInt() } catch (_: Throwable) { null }
            val lRead = try { fxProbe?.loudnessEnhancer?.targetGain?.toInt() } catch (_: Throwable) { null }
            sb.append("\nfx set: bass=").append(currentBassBoost)
            sb.append(" virt=").append(currentVirtualizer)
            sb.append(" loud=").append(loudnessMillibels(currentLoudness))
            sb.append(" | read: ").append(bRead ?: -1).append("/").append(vRead ?: -1).append("/").append(lRead ?: -1)
            sb.append(" | applied: ").append(appliedBass).append("/").append(appliedVirt).append("/").append(loudnessMillibels(appliedLoud))
            // Build #66: last glide transition — kind, frames executed, measured
            // duration, and whether a newer transition superseded it.
            sb.append("\ntransition: kind=").append(if (traceKind.isEmpty()) "none" else traceKind)
            sb.append(" frames=").append(traceFrames)
            sb.append(" ").append(traceMs).append("ms superseded=").append(traceSuperseded)
            if (activeProfilePackage != null) sb.append("\nprofile active: ").append(activeProfilePackage)
        } catch (t: Throwable) {
            sb.append("\ndiag error: ").append(t.message)
        }
        return sb.toString()
    }

    // Apply one full band frame (with preamp compensation) to the global EQ and
    // every active per-session EQ. Build #67: the frame is sampled onto the
    // hardware bands by interpolated position, and each write is clamped to the
    // device's band level range — an out-of-range setBandLevel throws (caught
    // silently) and would leave the band drifting at its old value.
    private fun applyBands(levels: IntArray) {
        if (bands.isNotEmpty() && bandPos.isNotEmpty()) {
            val g = globalEQ
            for (j in bands.indices) {
                val bi = bands[j]
                val mb = applyPreamp(sampleCurveAt(levels, bandPos[j]))
                    .toInt().coerceIn(bi.minLevel.toInt(), bi.maxLevel.toInt())
                try { g?.setBandLevel(bi.index.toShort(), mb.toShort()) } catch (_: Throwable) {}
            }
        }
        // Build #68: sessions sample through their OWN band map — a session with
        // a different band layout than the global EQ no longer receives writes
        // at out-of-range indices (which throw silently and would leave that
        // session running without EQ).
        for ((_, sfx) in activeFX) {
            applyCurveToEq(sfx.equalizer, sfx.bandPos, sfx.minLevel.toInt(), sfx.maxLevel.toInt(), levels)
        }
    }



    fun currentLevelsSnapshot(): ShortArray = currentBandLevels.copyOf()

    // Build #67: ShortArray.toIntArray() doesn't exist in the stdlib.
    private fun levelsInts(): IntArray = IntArray(currentBandLevels.size) { i -> currentBandLevels[i].toInt() }
    fun currentBassBoostValue(): Int = currentBassBoost
    fun currentVirtualizerValue(): Int = currentVirtualizer
    fun currentLoudnessValue(): Int = currentLoudness

    fun setSelectedPresetName(name: String) {
        markUserOverrideIfApplicable()
        selectedPresetName = name
        try { prefs.edit().putString(KEY_PRESET_NAME, name).apply() } catch (_: Throwable) { }
    }

    // ── Per-app audio profiles ──
    // Maps a playing app's package name to a preset name (built-in or custom).
    // The session polling loop detects which app is producing audio and swaps
    // the EQ to that app's profile automatically, restoring the user's
    // previous preset when the app stops. Manual user input always wins:
    // any direct band/effect/preset change suppresses the profile for the
    // currently playing app until a different app starts playing.

    @Volatile private var appProfiles: MutableMap<String, String> = loadAppProfiles()
    @Volatile private var activeProfilePackage: String? = null
    @Volatile private var suppressedProfilePackage: String? = null
    @Volatile private var restorePresetName: String? = null
    // Bass/Virtualizer/Loudness the user had before a profile engaged — restored
    // on revert so built-in restore presets (which only set band levels) don't
    // leave the profiled app's effect values stuck on.
    @Volatile private var restoreEffects: IntArray? = null
    @Volatile private var applyingProfile = false
    @Volatile private var lastPlayingPackage: String? = null

    fun listAppProfiles(): Map<String, String> = LinkedHashMap(appProfiles)
    fun playingPackage(): String? = lastPlayingPackage

    fun setAppProfile(pkg: String, preset: String?) {
        try {
            if (preset == null) appProfiles.remove(pkg) else appProfiles[pkg] = preset
            persistAppProfiles()
        } catch (_: Throwable) { }
    }

    private fun loadAppProfiles(): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        try {
            val raw = prefs.getString(KEY_APP_PROFILES, null) ?: return map
            val obj = org.json.JSONObject(raw)
            for (key in obj.keys()) map[key] = obj.getString(key)
        } catch (_: Throwable) { }
        return map
    }

    private fun persistAppProfiles() {
        try {
            val obj = org.json.JSONObject()
            for ((k, v) in appProfiles) obj.put(k, v)
            prefs.edit().putString(KEY_APP_PROFILES, obj.toString()).apply()
        } catch (_: Throwable) { }
    }

    // Apply a preset (built-in or custom) by name. Returns false if no preset
    // with that name exists.
    fun applyPresetByName(name: String): Boolean {
        val builtIn = Presets.presets.find { it.name == name }
        if (builtIn != null) {
            try {
                val levels = ShortArray(31) { i -> builtIn.levels.getOrElse(i) { 0 } }
                setBandLevels(levels)
            } catch (_: Throwable) { }
            return true
        }
        val custom = listCustomPresets().find { it.name == name }
        if (custom != null) {
            try {
                val levels = ShortArray(31) { i -> custom.levels.getOrElse(i) { 0 } }
                // Build #65: bands + fx in ONE coordinated ~120ms transition
                // (was: band ramp + fx glide as two serialized jobs).
                applyFullState(levels, custom.bassBoost, custom.virtualizer, custom.loudness, smooth = true)
            } catch (_: Throwable) { }
            return true
        }
        return false
    }

    // Called at the top of every user-facing setter. If a per-app profile is
    // currently auto-applied, the user's direct change suppresses the profile
    // for the playing app (their manual choice wins until playback changes).
    private fun markUserOverrideIfApplicable() {
        if (applyingProfile) return
        if (activeProfilePackage != null) {
            suppressedProfilePackage = activeProfilePackage
            activeProfilePackage = null
            restorePresetName = null
            restoreEffects = null
        }
    }

    // Resolve the package name of the app producing audio. AudioPlaybackConfiguration
    // exposes the client UID via public API; PackageManager maps UID → package.
    // Fully public-API path, no reflection needed — but Throwable-caught anyway.
    private fun reflectClientUid(config: AudioPlaybackConfiguration): Int {
        return try { (clientUidMethod?.invoke(config) as? Int) ?: -1 } catch (t: Throwable) { -1 }
    }

    private fun resolvePlayingPackage(config: AudioPlaybackConfiguration?): String? {
        if (config == null) return null
        return try {
            val uid = reflectClientUid(config)
            if (uid < 0) null else context.packageManager.getPackagesForUid(uid)?.firstOrNull()
        } catch (_: Throwable) { null }
    }

    // Core auto-switch logic, invoked once per session scan tick.
    private fun maybeApplyAppProfile(playingPkg: String?) {
        lastPlayingPackage = playingPkg
        // A suppressed profile stays suppressed only while its app is playing.
        if (playingPkg != suppressedProfilePackage) suppressedProfilePackage = null

        val profilePreset = playingPkg?.let { appProfiles[it] }
        if (playingPkg != null && profilePreset != null && playingPkg != suppressedProfilePackage) {
            if (activeProfilePackage != playingPkg) {
                if (activeProfilePackage == null) {
                    restorePresetName = selectedPresetName
                    restoreEffects = intArrayOf(currentBassBoost, currentVirtualizer, currentLoudness)
                }
                activeProfilePackage = playingPkg
                applyingProfile = true
                try {
                    if (applyPresetByName(profilePreset)) {
                        selectedPresetName = profilePreset
                        try { prefs.edit().putString(KEY_PRESET_NAME, profilePreset).apply() } catch (_: Throwable) { }
                    }
                } finally { applyingProfile = false }
            }
        } else {
            if (activeProfilePackage != null) {
                activeProfilePackage = null
                val restore = restorePresetName
                restorePresetName = null
                applyingProfile = true
                try {
                    if (restore != null && applyPresetByName(restore)) {
                        selectedPresetName = restore
                        try { prefs.edit().putString(KEY_PRESET_NAME, restore).apply() } catch (_: Throwable) { }
                    } else {
                        val flat = ShortArray(31) { 0 }
                        setBandLevels(flat)
                        selectedPresetName = "Flat"
                        try { prefs.edit().putString(KEY_PRESET_NAME, "Flat").apply() } catch (_: Throwable) { }
                    }
                    // Put the user's pre-profile Bass/Virtualizer/Loudness back.
                    // (applyPresetByName only restores effects for custom presets,
                    // so a built-in restore target would leave the profiled app's
                    // effect values stuck on without this.)
                    val fx = restoreEffects
                    restoreEffects = null
                    if (fx != null) {
                        setEffects(fx[0], fx[1], fx[2], smooth = true)
                    }
                } finally { applyingProfile = false }
            }
        }
    }

    // Called on startup when auto-apply preset setting is enabled.
    // Restores the last saved preset's band levels + effects into the live engine.
    // Uses batched setBandLevels() — one audio-thread task instead of 31 separate
    // setBandLevel() calls (31 persist + 31 enqueue vs 1 persist + 1 enqueue).
    fun applyLastPreset(): Boolean {
        if (!isAutoApplyPreset()) return false
        val name = selectedPresetName
        if (name == "Flat" || name == "Custom") return false
        return applyPresetByName(name)
    }

    // ── Custom presets (JSON serialization with backward-compat migration) ──

    private fun migrateOldFormat(raw: String): String {
        // Old format: "name1|lvl,lvl,...;name2|lvl,lvl,..."
        // If the string contains | and doesn't start with [, it's the old format.
        if (raw.isBlank() || raw.startsWith("[")) return raw
        val migrated = org.json.JSONArray()
        for (entry in raw.split(";")) {
            val parts = entry.split("|")
            if (parts.size != 2) continue
            val name = parts[0]
            val levels = parts[1].split(",").map { it.toShortOrNull() ?: 0 }
            val obj = org.json.JSONObject()
            obj.put("name", name)
            val arr = org.json.JSONArray()
            for (lvl in levels) arr.put(lvl.toInt())
            obj.put("levels", arr)
            obj.put("bass", 0)
            obj.put("virt", 0)
            obj.put("loud", 0)
            migrated.put(obj)
        }
        val json = migrated.toString()
        try { prefs.edit().putString(KEY_CUSTOM_PRESETS, json).apply() } catch (_: Throwable) { }
        return json
    }

    fun listCustomPresets(): List<Presets.CustomPreset> {
        val raw = prefs.getString(KEY_CUSTOM_PRESETS, null) ?: return emptyList()
        val json = migrateOldFormat(raw)
        if (json.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val levelsArr = obj.getJSONArray("levels")
                val levels = ShortArray(31) { idx -> levelsArr.optInt(idx, 0).toShort() }
                Presets.CustomPreset(
                    name = name,
                    levels = levels,
                    bassBoost = obj.optInt("bass", 0),
                    virtualizer = obj.optInt("virt", 0),
                    loudness = obj.optInt("loud", 0)
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    fun customPresetExists(name: String): Boolean = listCustomPresets().any { it.name == name }

    fun saveCustomPreset(name: String, levels: ShortArray, bass: Int = 0, virt: Int = 0, loud: Int = 0) {
        val existing = listCustomPresets().filter { it.name != name }
        val updated = existing + Presets.CustomPreset(name, levels, bass, virt, loud)
        persistCustomPresets(updated)
    }

    fun updateCustomPreset(name: String, levels: ShortArray, bass: Int, virt: Int, loud: Int) {
        val updated = listCustomPresets().map {
            if (it.name == name) Presets.CustomPreset(name, levels, bass, virt, loud) else it
        }
        persistCustomPresets(updated)
    }

    fun renameCustomPreset(oldName: String, newName: String) {
        val updated = listCustomPresets().map {
            if (it.name == oldName) it.copy(name = newName) else it
        }
        persistCustomPresets(updated)
    }

    fun deleteCustomPreset(name: String) {
        val remaining = listCustomPresets().filter { it.name != name }
        persistCustomPresets(remaining)
    }

    // Duplicate a custom preset — creates a copy with " (copy)" suffix.
    // If that name also exists, append a number until unique.
    fun duplicateCustomPreset(name: String): String {
        val list = listCustomPresets().toMutableList()
        val src = list.find { it.name == name } ?: return ""
        var newName = "$name (copy)"
        var n = 2
        while (list.any { it.name == newName }) {
            newName = "$name (copy $n)"
            n++
        }
        list.add(src.copy(name = newName))
        persistCustomPresets(list)
        return newName
    }

    // Reorder: move a custom preset one slot left (delta -1) or right (delta +1)
    // in the saved order. Returns false if already at the edge or not found.
    fun moveCustomPreset(name: String, delta: Int): Boolean {
        val list = listCustomPresets().toMutableList()
        val idx = list.indexOfFirst { it.name == name }
        if (idx < 0) return false
        val newIdx = (idx + delta).coerceIn(0, list.size - 1)
        if (newIdx == idx) return false
        val item = list.removeAt(idx)
        list.add(newIdx, item)
        persistCustomPresets(list)
        return true
    }

    // ── Full backup / restore ──

    // Export EVERYTHING — band levels, effect strengths, selected preset,
    // custom presets, and settings toggles — as one JSON blob for device
    // migration or safekeeping.
    fun exportFullBackup(): String {
        val root = org.json.JSONObject()
        root.put("type", "neoneq_backup")
        root.put("version", 1)
        root.put("levels", currentBandLevels.joinToString(","))
        root.put("bass", currentBassBoost)
        root.put("virt", currentVirtualizer)
        root.put("loud", currentLoudness)
        root.put("preset", selectedPresetName)
        root.put("enabled", currentEnabled)
        root.put("startOnBoot", isStartOnBoot())
        root.put("autoApplyPreset", isAutoApplyPreset())
        root.put("showVisualizer", isShowVisualizer())
        root.put("showGlow", isShowGlow())
        // Embed custom presets using the same format Presets.exportToJson emits.
        root.put("presets", org.json.JSONObject(exportCustomPresets()).optJSONArray("presets"))
        root.put("appProfiles", org.json.JSONObject(appProfiles as Map<*, *>))
        return root.toString(2)
    }

    // Restore from a full backup blob. Returns false if the JSON is not a
    // valid NeonEQ backup. Custom presets are replaced wholesale (backup
    // semantics, not merge) — current levels and effects are applied live.
    fun importFullBackup(json: String): Boolean {
        try {
            val root = org.json.JSONObject(json)
            if (root.optString("type") != "neoneq_backup") return false

            val levelsStr = root.optString("levels", "")
            if (levelsStr.isNotEmpty()) {
                val lv = ShortArray(31) { 0 }
                levelsStr.split(",").forEachIndexed { i, s ->
                    if (i < 31) lv[i] = (s.trim().toIntOrNull() ?: 0).toShort()
                }
                setBandLevels(lv)
            }
            setEffects(root.optInt("bass", 0), root.optInt("virt", 0), root.optInt("loud", 0), smooth = true)
            setSelectedPresetName(root.optString("preset", "Flat"))
            setStartOnBoot(root.optBoolean("startOnBoot", true))
            setAutoApplyPreset(root.optBoolean("autoApplyPreset", false))
            setShowVisualizer(root.optBoolean("showVisualizer", true))
            setShowGlow(root.optBoolean("showGlow", true))

            val profObj = root.optJSONObject("appProfiles")
            if (profObj != null) {
                val restored = LinkedHashMap<String, String>()
                for (key in profObj.keys()) restored[key] = profObj.getString(key)
                appProfiles = restored
                persistAppProfiles()
            }

            val arr = root.optJSONArray("presets")
            if (arr != null) {
                val restored = mutableListOf<Presets.CustomPreset>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val levelsArr = obj.optJSONArray("levels") ?: continue
                    restored.add(Presets.CustomPreset(
                        name = obj.optString("name", "Imported"),
                        levels = ShortArray(31) { idx -> levelsArr.optInt(idx, 0).toShort() },
                        bassBoost = obj.optInt("bass", 0),
                        virtualizer = obj.optInt("virt", 0),
                        loudness = obj.optInt("loud", 0)
                    ))
                }
                persistCustomPresets(restored)
            }
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    // ── Export / Import ──

    // Export ALL custom presets as a shareable JSON string.
    fun exportCustomPresets(): String {
        return Presets.exportToJson(listCustomPresets())
    }

    // Import presets from a JSON string. Returns the count of presets actually
    // imported (skipping duplicates with the same name).
    fun importCustomPresets(json: String): Int {
        val imported = Presets.importFromJson(json)
        if (imported.isEmpty()) return 0
        val existing = listCustomPresets().toMutableList()
        val existingNames = existing.map { it.name }.toMutableSet()
        var added = 0
        for (p in imported) {
            if (p.name !in existingNames) {
                existing.add(p)
                existingNames.add(p.name)
                added++
            }
        }
        if (added > 0) persistCustomPresets(existing)
        return added
    }

    private fun persistCustomPresets(presets: List<Presets.CustomPreset>) {
        try {
            val arr = org.json.JSONArray()
            for (p in presets) {
                val obj = org.json.JSONObject()
                obj.put("name", p.name)
                val levels = org.json.JSONArray()
                for (lvl in p.levels) levels.put(lvl.toInt())
                obj.put("levels", levels)
                obj.put("bass", p.bassBoost)
                obj.put("virt", p.virtualizer)
                obj.put("loud", p.loudness)
                arr.put(obj)
            }
            prefs.edit().putString(KEY_CUSTOM_PRESETS, arr.toString()).apply()
        } catch (_: Throwable) { }
    }

    // Hard watchdog: no matter what happens on the audio thread (native hang, hidden-API
    // Error on strict ROMs like MIUI, low-end HAL quirks), the loading screen MUST clear.
    // This posts a fallback "ready" state if the real init hasn't reported back in time.
    private val watchdogRunnable = Runnable {
        if (!uiNotified) {
            Log.w(TAG, "Watchdog fired — init did not complete in time, forcing degraded ready state")
            uiNotified = true
            statusMessage = "Limited EQ support on this device — try again or reduce bands"
            isReady = true
            enabled = currentEnabled
            if (bands.isEmpty()) bands = fallbackBands(bandCount); bandPos = FloatArray(bandCount) { it.toFloat() }
            onReady?.invoke(isReady, statusMessage, bands)
        }
    }
    @Volatile private var uiNotified = false

    private fun fallbackBands(count: Int): List<BandInfo> {
        // Generic band spread so sliders still render even if no real Equalizer attached.
        val freqs = listOf(60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000)
        val n = count.coerceIn(MIN_BANDS, MAX_BANDS)
        return (0 until n).map { i ->
            val f = freqs.getOrElse(i * freqs.size / n) { 1000 }
            BandInfo(i, f, -1500, 1500)
        }
    }

    fun attachToGlobalSession() {
        uiNotified = false
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, 4000L)

        audioExecutor.execute {
            try {
                try { releaseInternal() } catch (t: Throwable) { Log.w(TAG, "releaseInternal failed: ${t.message}") }

                // ── Try global session 0 ──
                try {
                    globalEQ = Equalizer(0, 0).also { eq ->
                        eq.enabled = currentEnabled
                        val numBands = eq.numberOfBands.toInt()
                        val usable = minOf(numBands, MAX_BANDS)
                        bands = pickBands(eq, bandCount, usable)
                        Log.d(TAG, "Global session 0: $numBands bands")
                        // Reapply persisted band levels immediately on (re)attach.
                        for (i in 0 until minOf(bandCount, usable)) {
                            val bandIndex = if (usable <= bandCount) i else i * usable / bandCount
                            val millibel = applyPreamp(currentBandLevels[i].toInt())
                            try { eq.setBandLevel(bandIndex.toShort(), millibel) } catch (_: Throwable) { }
                        }
                    }

                    try {
                        globalBassBoost = BassBoost(0, 0).also { bb ->
                            bb.enabled = currentEnabled && currentBassBoost > 0
                            if (currentBassBoost > 0) bb.setStrength(currentBassBoost.coerceIn(0, BASS_BOOST_STRENGTH_MAX).toShort())
                        }
                        Log.d(TAG, "Global BassBoost created")
                    } catch (t: Throwable) { Log.w(TAG, "Global BassBoost failed: ${t.message}") }

                    try {
                        globalVirtualizer = Virtualizer(0, 0).also { v ->
                            v.enabled = currentEnabled && currentVirtualizer > 0
                            if (currentVirtualizer > 0) v.setStrength(currentVirtualizer.coerceIn(0, VIRTUALIZER_STRENGTH_MAX).toShort())
                        }
                        Log.d(TAG, "Global Virtualizer created")
                    } catch (t: Throwable) { Log.w(TAG, "Global Virtualizer failed: ${t.message}") }

                    try {
                        globalLoudness = LoudnessEnhancer(0).also { le ->
                            le.enabled = currentEnabled && currentLoudness > 0
                            if (currentLoudness > 0) le.setTargetGain(loudnessMillibels(currentLoudness))
                        }
                        Log.d(TAG, "Global LoudnessEnhancer created")
                    } catch (t: Throwable) { Log.w(TAG, "Global Loudness failed: ${t.message}") }

                    try { attachVisualizer() } catch (t: Throwable) { Log.w(TAG, "Visualizer failed: ${t.message}") }

                } catch (t: Throwable) {
                    Log.w(TAG, "Session 0 failed: ${t.message}")
                    globalEQ = null
                }

                if (globalEQ != null) {
                    statusMessage = "Global EQ ready — play music to test"
                    isReady = true
                    enabled = currentEnabled
                } else {
                    statusMessage = "Scanning for audio sessions..."
                    isReady = true
                    enabled = currentEnabled
                    if (bands.isEmpty()) bands = fallbackBands(bandCount); bandPos = FloatArray(bandCount) { it.toFloat() }
                }

                try { startSessionPolling() } catch (t: Throwable) { Log.w(TAG, "startSessionPolling failed: ${t.message}") }
                try { scanForActiveSessions() } catch (t: Throwable) { Log.w(TAG, "scanForActiveSessions failed: ${t.message}") }

            } catch (t: Throwable) {
                // Absolute last resort — something we didn't anticipate blew up. Never let
                // the UI hang: report degraded-but-usable state.
                Log.e(TAG, "attachToGlobalSession fatal, degrading gracefully", t)
                statusMessage = "EQ running in limited mode on this device"
                isReady = true
                enabled = currentEnabled
                if (bands.isEmpty()) bands = fallbackBands(bandCount); bandPos = FloatArray(bandCount) { it.toFloat() }
            } finally {
                mainHandler.removeCallbacks(watchdogRunnable)
                if (!uiNotified) {
                    uiNotified = true
                    mainHandler.post { onReady?.invoke(isReady, statusMessage, bands) }
                }
            }
        }
    }

    // Best-effort real-time waveform capture off the global mix, so the UI can render
    // a live neon spectrum. Requires RECORD_AUDIO; if not granted or unsupported on
    // this device, this silently no-ops and the UI falls back to an idle animation.
    // Call after RECORD_AUDIO is granted mid-session — the very first attach may have
    // run before the permission dialog resolved, in which case Visualizer construction
    // throws (caught silently) and visualizer stays null forever with no automatic retry.
    // This lets MainActivity kick it back into life without resetting the rest of the EQ.
    fun retryVisualizerIfNeeded() {
        if (visualizer != null) return
        audioExecutor.execute {
            try { attachVisualizer() } catch (t: Throwable) { Log.w(TAG, "retryVisualizer failed: ${t.message}") }
        }
    }

    private fun attachVisualizer() {
        try {
            visualizer?.runCatching { release() }
            visualizer = Visualizer(0).apply {
                val range = Visualizer.getCaptureSizeRange()
                captureSize = range[1].coerceAtMost(1024).coerceAtLeast(range[0])
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        // Data is flowing — capture is healthy, reset the retry backoff.
                        lastWaveformAt = SystemClock.elapsedRealtime()
                        visRetryCount = 0
                        waveform?.let { data -> mainHandler.post { onWaveform?.invoke(data) } }
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) { }
                }, (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(1), true, false)
                enabled = true
            }
            // Grace period so the stall detector doesn't fire before the first callback.
            lastWaveformAt = SystemClock.elapsedRealtime()
        } catch (t: Throwable) {
            Log.w(TAG, "attachVisualizer failed: ${t.message}")
            visualizer = null
            // Back the stall detector off — permission may not be granted yet.
            lastWaveformAt = SystemClock.elapsedRealtime()
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            try { scanForActiveSessions() } catch (t: Throwable) { Log.e(TAG, "poll tick failed", t) }
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun startSessionPolling() {
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)

        // Build #59: react to playback config changes the moment they happen
        // instead of waiting for the 1.5s poll — the first second of every new
        // track used to play unprocessed while the poller caught up. Public
        // API on 26+; the 1.5s polling stays as the MIUI fallback.
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                        // Scan on the poll handler so session attach stays
                        // single-threaded with the regular polling.
                        pollHandler.post {
                            try { scanForActiveSessions() } catch (t: Throwable) { Log.e(TAG, "playback-callback scan failed", t) }
                        }
                    }
                }, null)
                Log.d(TAG, "AudioPlaybackCallback registered — instant session attach")
            } catch (t: Throwable) {
                Log.w(TAG, "registerAudioPlaybackCallback failed: ${t.message}")
            }
        }
    }

    private fun stopSessionPolling() {
        pollHandler.removeCallbacks(pollRunnable)
    }

    // getSessionId()/isActive() on AudioPlaybackConfiguration are @SystemApi (hidden) —
    // not part of the public SDK, so we reach them via reflection. Falls back to
    // global session 0 only if the platform blocks it.
    private val sessionIdMethod by lazy {
        try {
            AudioPlaybackConfiguration::class.java.getMethod("getSessionId").also { it.isAccessible = true }
        } catch (t: Throwable) { null }
    }
    private val isActiveMethod by lazy {
        try {
            AudioPlaybackConfiguration::class.java.getMethod("isActive").also { it.isAccessible = true }
        } catch (t: Throwable) { null }
    }
    // getClientUid() is @SystemApi (hidden) on compileSdk 34 — same treatment as
    // the session-id methods above: reflect, and fall back gracefully if blocked.
    private val clientUidMethod by lazy {
        try {
            AudioPlaybackConfiguration::class.java.getMethod("getClientUid").also { it.isAccessible = true }
        } catch (t: Throwable) { null }
    }

    private fun reflectSessionId(config: AudioPlaybackConfiguration): Int {
        return try { (sessionIdMethod?.invoke(config) as? Int) ?: 0 } catch (t: Throwable) { 0 }
    }
    private fun reflectIsActive(config: AudioPlaybackConfiguration): Boolean {
        return try { (isActiveMethod?.invoke(config) as? Boolean) ?: false } catch (t: Throwable) { false }
    }

    // Track whether we've already done a brute-force scan for this audio-playing state.
    // Avoids re-scanning 48 session IDs every 1.5s poll when reflection is blocked.
    // Build #62: brute-force retry gate (replaces the once-only flag — a scan
    // that ran too early, before the session was allocated, never retried).
    @Volatile private var lastBruteForceAt = 0L
    @Volatile private var rampActiveUntil = 0L   // drift heal must not fight the preset ramp
    @Volatile var healCount = 0L                 // surfaced in the diagnostics panel
    // Build #70: rotating interior-band cursor per EQ (session id, or -1 for the
    // global EQ). Wide EQs verify first + last + a rotating window of interior
    // bands so EVERY band is covered across scan cycles.
    private val healCursor = ConcurrentHashMap<Int, Int>()

    private fun scanForActiveSessions() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val configs = am.getActivePlaybackConfigurations()

            // Per-app profiles: pick the config we'd attach an EQ to (one with a
            // real session id; falls back to first config when reflection is
            // blocked) and swap presets if that app has a profile.
            val profileConfig = configs.firstOrNull { reflectSessionId(it) != 0 } ?: configs.firstOrNull()
            maybeApplyAppProfile(resolvePlayingPackage(profileConfig))

            val activeSessionIds = mutableSetOf<Int>()

            // Method 1: AudioPlaybackConfiguration reflection — don't require isActive()
            // because MIUI often returns false even when audio is actively playing.
            for (config in configs) {
                val sessionId = reflectSessionId(config)
                if (sessionId != 0) {
                    activeSessionIds.add(sessionId)
                }
            }

            Log.d(TAG, "Session scan: ${configs.size} configs, ${activeSessionIds.size} sessions via reflection")

            // Method 2: Brute-force fallback — if configs exist (audio is playing) but
            // reflection found no session IDs (hidden API blocked on MIUI), try to
            // create an Equalizer on candidate sessions. If it has bands, it's a
            // real audio session. Build #62: retried with a 3s cooldown while
            // audio plays but nothing is attached (was once per playing state —
            // a scan that ran before the session was allocated never retried).
            if (activeSessionIds.isEmpty() && configs.isNotEmpty() && activeFX.isEmpty() &&
                SystemClock.elapsedRealtime() - lastBruteForceAt > 3000L) {
                Log.d(TAG, "Reflection found nothing with ${configs.size} configs — brute-force scan")
                lastBruteForceAt = SystemClock.elapsedRealtime()
                for (sid in 1..64) {
                    if (activeFX.containsKey(sid) || sid == 0) continue
                    try {
                        val testEq = Equalizer(0, sid)
                        val numBands = testEq.numberOfBands.toInt()
                        if (numBands > 0) {
                            // Real session — but don't keep this test EQ, let attachToSession create it properly
                            testEq.release()
                            activeSessionIds.add(sid)
                            Log.d(TAG, "Brute-force found session $sid ($numBands bands)")
                        } else {
                            testEq.release()
                        }
                    } catch (e: Throwable) { /* session doesn't exist, skip */ }
                }
            }

            // Build #58: visualizer self-heal — if the EQ is enabled and audio is
            // playing but no waveform has arrived for a while, the MIUI capture is
            // silently dead. Re-attach it, with a growing backoff on repeat failures.
            if (enabled && configs.isNotEmpty() &&
                SystemClock.elapsedRealtime() - lastWaveformAt > 4000L * (visRetryCount + 1)) {
                Log.w(TAG, "Visualizer stalled ${(SystemClock.elapsedRealtime() - lastWaveformAt) / 1000}s — re-attaching")
                audioExecutor.execute {
                    try { attachVisualizer() } catch (t: Throwable) { Log.w(TAG, "self-heal re-attach failed: ${t.message}") }
                }
                visRetryCount = (visRetryCount + 1).coerceAtMost(14)  // cap backoff at ~60s
            }

            val stale = activeFX.keys - activeSessionIds
            for (id in stale) {
                Log.d(TAG, "Removing stale session $id")
                releaseSession(id)
            }

            for (sessionId in activeSessionIds) {
                if (!activeFX.containsKey(sessionId)) {
                    attachToSession(sessionId)
                }
            }

            // Reapply current band levels + effects to any newly attached sessions
            if (activeSessionIds.isNotEmpty()) {
                reapplyStateToSessions()
            }

            if (bands.isEmpty()) {
                val anyEQ = activeFX.values.firstOrNull()?.equalizer ?: globalEQ
                if (anyEQ != null) {
                    val numBands = anyEQ.numberOfBands.toInt()
                    val usable = minOf(numBands, MAX_BANDS)
                    bands = pickBands(anyEQ, bandCount, usable)
                }
            }

            // Build #62: MIUI drift self-heal. MIUI can silently disable our
            // effects or reset band levels (its own audio chain restarting,
            // effectsframework churn). Every scan: re-assert enabled, and verify
            // the hardware actually holds our band-0 gain — if not, re-apply the
            // full state. Skipped while a preset ramp is in flight so the heal
            // can't hard-jump a ~120ms glide.
            if (activeFX.isNotEmpty()) {
                try {
                    var drifted = false
                    // Build #70: every band, every scan (small EQs) or every band
                    // across scans (wide EQs, rotating interior window) — verified
                    // through each session's OWN interpolated map. Edge-only
                    // checking (first/last) was blind to MIUI resetting interior
                    // bands; band-0-only was blind to the high region entirely.
                    val levelsI = levelsInts()
                    val rampOk = SystemClock.elapsedRealtime() >= rampActiveUntil
                    for ((id, sfx) in activeFX) {
                        try {
                            if (sfx.equalizer.enabled != currentEnabled) sfx.equalizer.enabled = currentEnabled
                            if (enabled && rampOk) {
                                val probes = interiorProbes(id, sfx.bandPos.size)
                                if (verifyBandsDrifted(sfx.equalizer, sfx.bandPos, levelsI, probes, shouldFullSweep(id))) drifted = true
                            }
                        } catch (_: Throwable) {}
                    }
                    val g = globalEQ  // local copy so Kotlin can smart-cast
                    if (g != null) {
                        try {
                            if (g.enabled != currentEnabled) g.enabled = currentEnabled
                            // Build #70: the global EQ gets the same all-band
                            // verification (it only had its enabled flag checked).
                            if (enabled && rampOk && bands.isNotEmpty() && bandPos.isNotEmpty()) {
                                val probes = interiorProbes(-1, bandPos.size)
                                if (verifyBandsDrifted(g, bandPos, levelsI, probes, shouldFullSweep(-1))) drifted = true
                            }
                        } catch (_: Throwable) {}
                    }
                    // Build #63: effects drift — MIUI can disable or reset
                    // BassBoost/Virtualizer/Loudness the same way it resets EQ
                    // bands. Verify enabled flags and applied strengths; a
                    // missing (unsupported) effect is NOT drift — only
                    // present-but-wrong triggers a heal.
                    val wantBass = currentEnabled && currentBassBoost > 0
                    val wantVirt = currentEnabled && currentVirtualizer > 0
                    val wantLoud = currentEnabled && currentLoudness > 0
                    if ((wantBass || wantVirt || wantLoud) &&
                        SystemClock.elapsedRealtime() >= rampActiveUntil) {  // never heal mid-glide
                        for ((_, sfx) in activeFX) {
                            if (wantBass) {
                                try {
                                    val b = sfx.bassBoost
                                    if (b != null && (!b.enabled ||
                                        b.properties.strength.toInt() != currentBassBoost.coerceIn(0, BASS_BOOST_STRENGTH_MAX))) drifted = true
                                } catch (_: Throwable) {}
                            }
                            if (wantVirt) {
                                try {
                                    val v = sfx.virtualizer
                                    if (v != null && (!v.enabled ||
                                        v.properties.strength.toInt() != currentVirtualizer.coerceIn(0, VIRTUALIZER_STRENGTH_MAX))) drifted = true
                                } catch (_: Throwable) {}
                            }
                            if (wantLoud) {
                                try {
                                    val l = sfx.loudnessEnhancer
                                    if (l != null && (!l.enabled || l.targetGain.toInt() != loudnessMillibels(currentLoudness))) drifted = true
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                    if (drifted) {
                        healCount++
                        Log.w(TAG, "Band-level drift detected (heal #${healCount}) — re-applying full state")
                        reapplyStateToSessions()
                    }
                } catch (t: Throwable) { Log.w(TAG, "drift heal failed: ${t.message}") }
            }

            activeSessionCount = activeFX.size
            if (activeFX.isNotEmpty()) {
                statusMessage = "EQ active on ${activeFX.size} session(s) + global"
                isReady = true
            } else if (globalEQ != null) {
                statusMessage = "Global EQ ready — play music"
            } else {
                statusMessage = "Play music in another app..."
            }

            mainHandler.post {
                onReady?.invoke(isReady, statusMessage, bands)
                onSessionUpdate?.invoke(activeSessionCount)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Session scan failed", t)
        }
    }

    // Reapply all current EQ state (band levels, bass, virtualizer, loudness, enabled)
    // to all per-session FX. Called after new sessions are attached so they don't
    // start with default/flat settings.
    private fun reapplyStateToSessions() {
        for ((_, sfx) in activeFX) {
            try {
                sfx.equalizer.enabled = currentEnabled
                // Build #68: the session's captured band map — same formula as
                // attach, no per-band recompute of the curve array.
                applyCurveToEq(sfx.equalizer, sfx.bandPos, sfx.minLevel.toInt(), sfx.maxLevel.toInt(), levelsInts())
            } catch (e: Throwable) { Log.e(TAG, "reapply EQ for session", e) }

        }

        // Build #65: fx re-apply goes through the single shared applier — this
        // was the fourth near-duplicate of the effects wiring.
        applyEffectsToHardware(currentBassBoost, currentVirtualizer, currentLoudness)
    }

    private fun attachToSession(sessionId: Int) {
        try {
            Log.d(TAG, "Attaching EQ to session $sessionId")
            val eq = Equalizer(0, sessionId)
            eq.enabled = currentEnabled

            val numBands = eq.numberOfBands.toInt()
            if (numBands <= 0) {
                // Build #62: a 0-band equalizer is a stub — attaching it gives a
                // false "EQ active" status while doing nothing to the audio.
                Log.w(TAG, "Session $sessionId: EQ has 0 bands (stub) — skipping")
                try { eq.release() } catch (_: Throwable) {}
                return
            }
            val usable = minOf(numBands, MAX_BANDS)

            // Build #67: sample the whole UI curve onto this session's own bands
            // (interpolated, per-session layout). Was: nearest-index step math
            // that diverged from the ramp-path mapping — the same UI slider
            // could land on a different hardware band depending on which code
            // path wrote the value.
            val range = try { eq.bandLevelRange } catch (_: Throwable) { shortArrayOf(-1500, 1500) }
            val positions = FloatArray(usable) { j ->
                j * (bandCount - 1).toFloat() / maxOf(usable - 1, 1).toFloat()
            }
            applyCurveToEq(eq, positions, range[0].toInt(), range[1].toInt(), levelsInts())

            var bb: BassBoost? = null
            var virt: Virtualizer? = null
            var loud: LoudnessEnhancer? = null
            try {
                bb = BassBoost(0, sessionId).also {
                    it.enabled = currentEnabled && currentBassBoost > 0
                    if (currentBassBoost > 0) it.setStrength(currentBassBoost.coerceIn(0, BASS_BOOST_STRENGTH_MAX).toShort())
                }
            } catch (e: Throwable) { Log.w(TAG, "BassBoost N/A for session $sessionId", e) }
            try {
                virt = Virtualizer(0, sessionId).also {
                    it.enabled = currentEnabled && currentVirtualizer > 0
                    if (currentVirtualizer > 0) it.setStrength(currentVirtualizer.coerceIn(0, VIRTUALIZER_STRENGTH_MAX).toShort())
                }
            } catch (e: Throwable) { Log.w(TAG, "Virtualizer N/A for session $sessionId", e) }
            try {
                loud = LoudnessEnhancer(sessionId).also { le ->
                    // Build #63: LoudnessEnhancer was NEVER enabled — AudioEffect
                    // starts disabled and setTargetGain alone does nothing. The
                    // loudness slider has been a silent no-op until now.
                    le.enabled = currentEnabled && currentLoudness > 0
                    if (currentLoudness > 0) le.setTargetGain(loudnessMillibels(currentLoudness))
                }
            } catch (e: Throwable) { Log.w(TAG, "Loudness N/A for session $sessionId", e) }

            activeFX[sessionId] = SessionFX(sessionId, eq, bb, virt, loud, positions, range[0], range[1])
            Log.d(TAG, "Session $sessionId: $numBands bands attached")

            if (bands.isEmpty()) {
                bands = pickBands(eq, bandCount, usable)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to attach to session $sessionId", e)
        }
    }

    private fun releaseSession(sessionId: Int) {
        healCursor.remove(sessionId)   // Build #70: reap the rotating probe cursor
        sweepTick.remove(sessionId)     // Build #75: reap the full-sweep tick
        activeFX.remove(sessionId)?.let { sfx ->
            sfx.equalizer.runCatching { release() }
            sfx.bassBoost?.runCatching { release() }
            sfx.virtualizer?.runCatching { release() }
            sfx.loudnessEnhancer?.runCatching { release() }
        }
    }

    // Build #67: position of each hardware band in UI-slot space. The UI curve
    // is SAMPLED at these (fractional) positions with linear interpolation, so
    // on a 5-band device ALL 31 sliders contribute (previously only the first
    // `usable` sliders were live and the rest were silently dead).
    @Volatile private var bandPos = FloatArray(0)

    private fun pickBands(eq: Equalizer, count: Int, usable: Int): List<BandInfo> {
        val indices = if (usable <= count) (0 until usable).toList()
        else (0 until count).map { it * usable / count }
        bandPos = FloatArray(indices.size) { j ->
            indices[j] * (count - 1).toFloat() / maxOf(usable - 1, 1).toFloat()
        }
        return indices.map { i ->
            BandInfo(i, eq.getCenterFreq(i.toShort()) / 1000, eq.bandLevelRange[0], eq.bandLevelRange[1])
        }
    }

    // Build #68: write the sampled curve to ONE equalizer (global or session)
    // using that EQ's own band positions and level range.
    private fun applyCurveToEq(eq: Equalizer, positions: FloatArray, minL: Int, maxL: Int, levels: IntArray) {
        for (j in positions.indices) {
            val mb = applyPreamp(sampleCurveAt(levels, positions[j]))
                .toInt().coerceIn(minL, maxL)
            try { eq.setBandLevel(j.toShort(), mb.toShort()) } catch (_: Throwable) {}
        }
    }

    // Build #70: rotating interior-band probes for one EQ. Wide EQs (n > 12) get
    // 3 interior bands per scan, advancing a per-EQ cursor, so the full curve is
    // verified every ceil((n-2)/3) scans instead of never (first/last only).
    private fun interiorProbes(cursorKey: Int, n: Int): IntArray {
        if (n <= 12) return IntArray(0)   // small EQs get a full sweep every scan
        val span = n - 2
        val cur = (healCursor[cursorKey] ?: 0) % span
        val probes = IntArray(minOf(3, span)) { k -> ((cur + k) % span) + 1 }
        healCursor[cursorKey] = (cur + probes.size) % span
        return probes
    }

    // Build #70: verify an equalizer's full sampled curve. Full sweep when the
    // band count is small (Redmi 10C: ~5 bands — a handful of binder reads);
    // first + last + rotating interior probes on wide EQs. Expected values
    // honor the interpolated mapping via sampleCurveAt. Returns true on drift.
    // Build #75 hardening: every band read is isolated — a throwing read can
    // no longer abort the whole sweep and mask real drift on the remaining
    // bands; a flaky read counts as drift for its band (reasserting is
    // harmless, and a truly dead EQ is reaped by the scanner). Wide EQs also
    // get a full-sweep pass every 8th scan so the rotating probes are a
    // speed optimization, never the only coverage.
    private fun verifyBandsDrifted(eq: Equalizer, positions: FloatArray, levels: IntArray, probes: IntArray, fullSweep: Boolean): Boolean {
        val n = positions.size
        if (n <= 0) return false
        var drifted = false
        fun check(j: Int) {
            try {
                if (eq.getBandLevel(j.toShort()) != applyPreamp(sampleCurveAt(levels, positions[j]))) drifted = true
            } catch (_: Throwable) { drifted = true }
        }
        if (n <= 12 || fullSweep) {
            for (j in 0 until n) check(j)
            return drifted
        }
        check(0)
        check(n - 1)
        for (j in probes) check(j)
        return drifted
    }

    // Build #75: per-EQ scan tick — every 8th scan of an EQ runs a full band
    // sweep even on wide equalizers, guaranteeing complete verification
    // coverage across scans.
    private val sweepTick = ConcurrentHashMap<Int, Int>()
    private fun shouldFullSweep(key: Int): Boolean {
        val t = (sweepTick[key] ?: 0) + 1
        sweepTick[key] = t
        return t % 8 == 0
    }

    // Linear interpolation of the UI curve at a fractional slot position.
    private fun sampleCurveAt(levels: IntArray, pos: Float): Int {
        if (levels.isEmpty()) return 0
        val p = pos.coerceIn(0f, (levels.size - 1).toFloat())
        val lo = kotlin.math.floor(p).toInt().coerceIn(0, levels.size - 1)
        val hi = kotlin.math.ceil(p).toInt().coerceIn(0, levels.size - 1)
        val frac = p - lo
        return (levels[lo] * (1f - frac) + levels[hi] * frac).toInt()
    }

    // ── Public API ──


    // Reapply all current band levels to global + per-session EQs with preamp compensation.
    // Called when effect sliders change (bass/virt/loud) so the preamp updates live.
    // Build #67: routed through the single curve sampler — same interpolated
    // mapping and range clamping as every other band write.
    private fun reapplyBandLevelsToHardware() {
        applyBands(levelsInts())
    }

    fun setBandLevel(band: Int, level: Short) {
        markUserOverrideIfApplicable()
        rampGeneration++   // a direct drag supersedes any in-flight preset ramp
        currentBandLevels[band] = level
        persistLevels()
        audioExecutor.execute {
            // Build #67: full curve reapply per drag frame. One UI slot can
            // legitimately drive more than one hardware band under the
            // interpolated mapping — this keeps the mapping in ONE place.
            applyBands(levelsInts())
        }
    }

    fun setBandCount(count: Int) {
        markUserOverrideIfApplicable()
        bandCount = count.coerceIn(MIN_BANDS, MAX_BANDS)
        persistScalar(KEY_BAND_COUNT, bandCount)
        audioExecutor.execute {
            try {
                val anyEQ = globalEQ ?: activeFX.values.firstOrNull()?.equalizer
                if (anyEQ != null) {
                    val usable = minOf(anyEQ.numberOfBands.toInt(), MAX_BANDS)
                    bands = pickBands(anyEQ, bandCount, usable)
                }
                // Build #69: sessions re-capture their band positions with the NEW
                // bandCount — each from its own captured width, since sessions can
                // expose different band layouts. The curve is then sampled
                // everywhere through the shared sampler (no more legacy
                // per-index write loop here).
                for ((id, sfx) in activeFX) {
                    val n = sfx.bandPos.size
                    if (n > 0) {
                        val fresh = FloatArray(n) { j ->
                            j * (bandCount - 1).toFloat() / maxOf(n - 1, 1).toFloat()
                        }
                        activeFX[id] = sfx.copy(bandPos = fresh)
                    }
                }
                applyBands(levelsInts())
            } catch (e: Throwable) { Log.e(TAG, "setBandCount", e) }
            mainHandler.post { onReady?.invoke(isReady, statusMessage, bands) }
        }
    }

    // Build #64: applied (hardware) effect values — the fx glide starts from
    // these, not from UI targets, so consecutive transitions chain smoothly.
    @Volatile private var appliedBass = 0
    @Volatile private var appliedVirt = 0
    @Volatile private var appliedLoud = 0
    @Volatile private var fxRampGeneration = 0

    // Build #66: glide trace — measures the REAL transition timing on-device
    // (no adb on the Redmi 10C). Written by the setEffects / applyFullState
    // glide loops, surfaced in the diagnostics panel.
    @Volatile private var traceKind = ""          // "fx" | "full" | ""
    @Volatile private var traceMs = 0L           // measured wall-clock duration
    @Volatile private var traceFrames = 0        // frames actually executed
    @Volatile private var traceSuperseded = false // killed by a newer transition

    // Build #73: loudness response curve. The slider is 0-300 UI units, but the
    // hardware wants millibels. A 0.75-exponent power curve makes the whole
    // range bite harder (150 -> ~594 mB vs the old flat 150 mB) with the same
    // one-finger feel at the top, capped at 1000 mB (+10 dB) to stay clear of
    // distortion. EVERY hardware write and drift check goes through this.
    private fun loudnessMillibels(v: Int): Int =
        (Math.pow((v.coerceIn(0, 300) / 300.0), 0.75) * 1000.0).toInt()

    // Build #64: ONE shared applier for the whole effects chain — global path
    // plus every session, all three effects, in a single pass. Sets strength/
    // gain BEFORE enabling (enabling first lets the effect go live at its
    // default/zero state for a moment — audible blip on re-enable).
    private fun applyEffectsToHardware(bass: Int, virt: Int, loud: Int) {
        try {
            val b = bass.coerceIn(0, BASS_BOOST_STRENGTH_MAX)
            val v = virt.coerceIn(0, VIRTUALIZER_STRENGTH_MAX)
            val l = loud.coerceIn(0, 300)
            val wantBass = currentEnabled && b > 0
            val wantVirt = currentEnabled && v > 0
            val wantLoud = currentEnabled && l > 0
            val gb = globalBassBoost
            val gv = globalVirtualizer
            val gl = globalLoudness
            try { gb?.apply { setStrength(b.toShort()); enabled = wantBass } } catch (_: Throwable) {}
            try { gv?.apply { setStrength(v.toShort()); enabled = wantVirt } } catch (_: Throwable) {}
            val lmb = loudnessMillibels(l)
            try { gl?.apply { if (lmb > 0) setTargetGain(lmb); enabled = wantLoud } } catch (_: Throwable) {}
            for ((_, sfx) in activeFX) {
                try { sfx.bassBoost?.apply { setStrength(b.toShort()); enabled = wantBass } } catch (_: Throwable) {}
                try { sfx.virtualizer?.apply { setStrength(v.toShort()); enabled = wantVirt } } catch (_: Throwable) {}
                try { sfx.loudnessEnhancer?.apply { if (lmb > 0) setTargetGain(lmb); enabled = wantLoud } } catch (_: Throwable) {}
            }
            appliedBass = b; appliedVirt = v; appliedLoud = l
        } catch (t: Throwable) { Log.w(TAG, "applyEffects failed: ${'$'}{t.message}") }
    }

    // Build #64: batch-set the effects chain. Preset switches, per-app profile
    // auto-switches and backup restores used to hard-jump bass/virt/loudness
    // (audible pop, especially a big loudness step). smooth=true glides all
    // three from their APPLIED values to the targets over ~120ms (8 smoothstep
    // frames, mirroring the band ramp) — superseded by any newer glide or
    // direct slider call. Slider drags stay instant (setX paths).
    fun setEffects(bass: Int, virt: Int, loud: Int, smooth: Boolean = false) {
        markUserOverrideIfApplicable()
        currentBassBoost = bass.coerceIn(0, BASS_BOOST_STRENGTH_MAX)
        currentVirtualizer = virt.coerceIn(0, VIRTUALIZER_STRENGTH_MAX)
        currentLoudness = loud.coerceIn(0, 300)
        persistScalar(KEY_BASS, currentBassBoost)
        persistScalar(KEY_VIRT, currentVirtualizer)
        persistScalar(KEY_LOUD, currentLoudness)
        audioExecutor.execute {
            if (!smooth) {
                fxRampGeneration++  // direct call supersedes any in-flight glide
                applyEffectsToHardware(currentBassBoost, currentVirtualizer, currentLoudness)
                reapplyBandLevelsToHardware()
                return@execute
            }
            val gen = ++fxRampGeneration
            rampActiveUntil = SystemClock.elapsedRealtime() + 250L  // heal must not fight the glide
            val tb = currentBassBoost; val tv = currentVirtualizer; val tl = currentLoudness
            val maxDelta = maxOf(
                kotlin.math.abs(tb - appliedBass),
                kotlin.math.abs(tv - appliedVirt),
                kotlin.math.abs(tl - appliedLoud))
            if (maxDelta <= 5) {  // tiny change — instant, no glide lag
                applyEffectsToHardware(tb, tv, tl)
                reapplyBandLevelsToHardware()
                return@execute
            }
            val fb = appliedBass; val fv = appliedVirt; val fl = appliedLoud
            val t0 = SystemClock.elapsedRealtime()
            traceKind = "fx"; traceMs = 0L; traceFrames = 0; traceSuperseded = false
            val steps = 8
            for (s in 1..steps) {
                if (fxRampGeneration != gen) { traceSuperseded = true; return@execute }  // superseded
                val t = s.toFloat() / steps
                val eased = t * t * (3f - 2f * t)   // smoothstep: soft start & end
                applyEffectsToHardware(
                    fb + ((tb - fb) * eased).toInt(),
                    fv + ((tv - fv) * eased).toInt(),
                    fl + ((tl - fl) * eased).toInt())
                traceFrames = s
                if (s < steps) try { Thread.sleep(15) } catch (_: InterruptedException) { traceSuperseded = true; return@execute }
            }
            traceMs = SystemClock.elapsedRealtime() - t0
            reapplyBandLevelsToHardware()  // preamp compensation tracks final values
        }
    }

    // Build #65: ONE coordinated transition — bands AND the effects chain ramp
    // in lockstep inside a single executor job. A full-state switch previously
    // ran the band ramp and the fx glide as two separate jobs, serialized
    // back-to-back (~240ms) and free to drift out of sync mid-transition.
    // Superseded by any newer ramp (band drag) or fx call (slider), either of
    // which bumps its generation counter and kills this loop.
    fun applyFullState(levels: ShortArray, bass: Int, virt: Int, loud: Int, smooth: Boolean = false) {
        markUserOverrideIfApplicable()
        val to = IntArray(levels.size) { i -> levels[i].toInt() }
        val from = IntArray(levels.size) { i -> currentBandLevels.getOrNull(i)?.toInt() ?: 0 }
        for (i in levels.indices) {
            if (i < currentBandLevels.size) currentBandLevels[i] = levels[i]
        }
        persistLevels()
        currentBassBoost = bass.coerceIn(0, BASS_BOOST_STRENGTH_MAX)
        currentVirtualizer = virt.coerceIn(0, VIRTUALIZER_STRENGTH_MAX)
        currentLoudness = loud.coerceIn(0, 300)
        persistScalar(KEY_BASS, currentBassBoost)
        persistScalar(KEY_VIRT, currentVirtualizer)
        persistScalar(KEY_LOUD, currentLoudness)
        audioExecutor.execute {
            val gen = ++rampGeneration
            fxRampGeneration = gen  // one job drives both counters
            rampActiveUntil = SystemClock.elapsedRealtime() + 250L  // heal must not fight the glide
            val tb = currentBassBoost; val tv = currentVirtualizer; val tl = currentLoudness
            val fb = appliedBass; val fv = appliedVirt; val fl = appliedLoud
            var maxBand = 0
            for (i in to.indices) {
                val d = kotlin.math.abs(to[i] - from.getOrElse(i) { 0 })
                if (d > maxBand) maxBand = d
            }
            val maxDelta = maxOf(maxBand,
                kotlin.math.abs(tb - fb), kotlin.math.abs(tv - fv), kotlin.math.abs(tl - fl))
            if (!smooth || maxDelta <= 2) {
                applyBands(to)
                applyEffectsToHardware(tb, tv, tl)
                reapplyBandLevelsToHardware()
                return@execute
            }
            val t0 = SystemClock.elapsedRealtime()
            traceKind = "full"; traceMs = 0L; traceFrames = 0; traceSuperseded = false
            val steps = 8
            for (s in 1..steps) {
                if (rampGeneration != gen || fxRampGeneration != gen) { traceSuperseded = true; return@execute }  // superseded
                val t = s.toFloat() / steps
                val eased = t * t * (3f - 2f * t)   // smoothstep: soft start & end
                val frame = IntArray(to.size) { i ->
                    val start = from.getOrElse(i) { 0 }
                    start + ((to[i] - start) * eased).toInt()
                }
                applyBands(frame)
                applyEffectsToHardware(
                    fb + ((tb - fb) * eased).toInt(),
                    fv + ((tv - fv) * eased).toInt(),
                    fl + ((tl - fl) * eased).toInt())
                traceFrames = s
                if (s < steps) try { Thread.sleep(15) } catch (_: InterruptedException) { traceSuperseded = true; return@execute }
            }
            traceMs = SystemClock.elapsedRealtime() - t0
        }
    }

    fun setBassBoost(strength: Int) {
        markUserOverrideIfApplicable()
        currentBassBoost = strength.coerceIn(0, BASS_BOOST_STRENGTH_MAX)
        persistScalar(KEY_BASS, currentBassBoost)
        audioExecutor.execute {
            fxRampGeneration++  // direct slider set supersedes any in-flight glide
            applyEffectsToHardware(currentBassBoost, currentVirtualizer, currentLoudness)
            reapplyBandLevelsToHardware()
        }
    }

    fun setVirtualizer(strength: Int) {
        markUserOverrideIfApplicable()
        currentVirtualizer = strength.coerceIn(0, VIRTUALIZER_STRENGTH_MAX)
        persistScalar(KEY_VIRT, currentVirtualizer)
        audioExecutor.execute {
            fxRampGeneration++
            applyEffectsToHardware(currentBassBoost, currentVirtualizer, currentLoudness)
            reapplyBandLevelsToHardware()
        }
    }

    fun setLoudness(gain: Int) {
        markUserOverrideIfApplicable()
        currentLoudness = gain.coerceIn(0, 300)
        persistScalar(KEY_LOUD, currentLoudness)
        audioExecutor.execute {
            fxRampGeneration++
            applyEffectsToHardware(currentBassBoost, currentVirtualizer, currentLoudness)
            reapplyBandLevelsToHardware()
        }
    }

    // Batch-set all band levels in a single audio thread task — used by preset
    // animation frames so we enqueue ONE job per frame instead of 31 separate
    // setBandLevel calls (372 total during a 12-frame animation). Same result,
    // far less thread contention and native API churn.
    fun setBandLevels(levels: ShortArray) {
        markUserOverrideIfApplicable()
        // Capture the pre-change curve BEFORE overwriting so the hardware can
        // ramp from old → new (Build #59 — click-free preset switches).
        // currentBandLevels jumps to the target immediately so the UI stays
        // truthful; only the hardware curve eases over ~120ms.
        val from = IntArray(levels.size) { i -> currentBandLevels.getOrNull(i)?.toInt() ?: 0 }
        val to = IntArray(levels.size) { i -> levels[i].toInt() }
        for (i in levels.indices) {
            if (i < currentBandLevels.size) currentBandLevels[i] = levels[i]
        }
        persistLevels()
        rampBands(from, to)
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        currentEnabled = on
        try { prefs.edit().putBoolean(KEY_ENABLED, on).apply() } catch (_: Throwable) { }
        audioExecutor.execute {
            try { globalEQ?.enabled = on } catch (_: Throwable) {}
            try { visualizer?.enabled = on } catch (_: Throwable) {}

            // Build #64: the whole effects chain (global + every session, enabled
            // flags AND strengths/gains) through the single shared applier.
            applyEffectsToHardware(currentBassBoost, currentVirtualizer, currentLoudness)

            for ((_, sfx) in activeFX) {
                try { sfx.equalizer.enabled = on } catch (_: Throwable) {}
            }
        }
    }

    fun isBoot(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    // ── User preferences (settings panel) ──

    fun isStartOnBoot(): Boolean = prefs.getBoolean(KEY_START_ON_BOOT, true)
    fun setStartOnBoot(on: Boolean) {
        try { prefs.edit().putBoolean(KEY_START_ON_BOOT, on).apply() } catch (_: Throwable) { }
    }

    fun isAutoApplyPreset(): Boolean = prefs.getBoolean(KEY_AUTO_APPLY_PRESET, false)
    fun setAutoApplyPreset(on: Boolean) {
        try { prefs.edit().putBoolean(KEY_AUTO_APPLY_PRESET, on).apply() } catch (_: Throwable) { }
    }

    fun isShowVisualizer(): Boolean = prefs.getBoolean(KEY_SHOW_VISUALIZER, true)
    fun setShowVisualizer(on: Boolean) {
        try { prefs.edit().putBoolean(KEY_SHOW_VISUALIZER, on).apply() } catch (_: Throwable) { }
    }

    fun isShowGlow(): Boolean = prefs.getBoolean(KEY_SHOW_GLOW, true)

    fun getVisualizerStyle(): String {
        return try {
            prefs.getString(KEY_VIS_STYLE, "bars") ?: "bars"
        } catch (_: Throwable) { "bars" }
    }

    fun setVisualizerStyle(style: String) {
        try { prefs.edit().putString(KEY_VIS_STYLE, style).apply() } catch (_: Throwable) { }
    }
    fun setShowGlow(on: Boolean) {
        try { prefs.edit().putBoolean(KEY_SHOW_GLOW, on).apply() } catch (_: Throwable) { }
    }

    fun reattach() {
        audioExecutor.execute {
            releaseInternal()
            attachToGlobalSession()
        }
    }

    fun release() {
        stopSessionPolling()
        audioExecutor.execute { releaseInternal() }
    }

    private fun releaseInternal() {
        for (id in activeFX.keys.toList()) { releaseSession(id) }
        activeFX.clear()
        globalEQ?.runCatching { release() }; globalEQ = null
        globalBassBoost?.runCatching { release() }; globalBassBoost = null
        globalVirtualizer?.runCatching { release() }; globalVirtualizer = null
        globalLoudness?.runCatching { release() }; globalLoudness = null
        visualizer?.runCatching { release() }; visualizer = null
        enabled = false; isReady = false
    }
}
