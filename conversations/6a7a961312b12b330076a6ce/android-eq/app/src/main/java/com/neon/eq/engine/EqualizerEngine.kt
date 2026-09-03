package com.neon.eq.engine

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.*
import android.os.Handler
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
        val loudnessEnhancer: LoudnessEnhancer?
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

    private fun persistLevels() {
        try {
            prefs.edit().putString(KEY_LEVELS, currentBandLevels.joinToString(",")).apply()
        } catch (_: Throwable) { }
    }

    private fun persistScalar(key: String, value: Int) {
        try { prefs.edit().putInt(key, value).apply() } catch (_: Throwable) { }
    }

    // Pre-amp compensation: when bass boost, virtualizer, or loudness are active,
    // they add gain on top of the EQ. On phone speakers this causes clipping/distortion.
    // We subtract a computed offset from all band levels sent to the hardware so the
    // total output stays within the speaker's headroom. The UI shows the user's intended
    // levels — only the hardware values are reduced.
    private fun computePreampDb(): Int {
        var preamp = 0
        if (currentBassBoost > 0) preamp += currentBassBoost / 100   // ~1dB per 100 strength
        if (currentVirtualizer > 0) preamp += currentVirtualizer / 200  // ~0.5dB per 100
        if (currentLoudness > 0) preamp += currentLoudness / 100     // ~1dB per 100mB
        return preamp.coerceAtMost(6)  // never reduce by more than -6dB total
    }

    private fun applyPreamp(level: Int): Short {
        val adjusted = (level - computePreampDb()).coerceIn(-15, 15)
        return (adjusted * DB_TO_MILLIBEL).toShort()
    }



    fun currentLevelsSnapshot(): ShortArray = currentBandLevels.copyOf()
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
                setBandLevels(levels)
                setBassBoost(custom.bassBoost)
                setVirtualizer(custom.virtualizer)
                setLoudness(custom.loudness)
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
                if (activeProfilePackage == null) restorePresetName = selectedPresetName
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
            setBassBoost(root.optInt("bass", 0))
            setVirtualizer(root.optInt("virt", 0))
            setLoudness(root.optInt("loud", 0))
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
            if (bands.isEmpty()) bands = fallbackBands(bandCount)
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
                            if (currentLoudness > 0) le.setTargetGain(currentLoudness.coerceIn(0, 300))
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
                    if (bands.isEmpty()) bands = fallbackBands(bandCount)
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
                if (bands.isEmpty()) bands = fallbackBands(bandCount)
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
                        waveform?.let { data -> mainHandler.post { onWaveform?.invoke(data) } }
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) { }
                }, (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(1), true, false)
                enabled = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "attachVisualizer failed: ${t.message}")
            visualizer = null
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
    @Volatile private var bruteForceDone = false

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
            // create an Equalizer on sessions 1..48. If it has bands, it's a real
            // audio session. Only do this once per audio-playing state to avoid churn.
            if (activeSessionIds.isEmpty() && configs.isNotEmpty() && !bruteForceDone) {
                Log.d(TAG, "Reflection found nothing with ${configs.size} configs — brute-force scan")
                bruteForceDone = true
                for (sid in 1..48) {
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

            // Reset brute-force flag when no audio is playing so we re-scan next time
            if (configs.isEmpty()) bruteForceDone = false

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
                val numBands = sfx.equalizer.numberOfBands.toInt()
                val usable = minOf(numBands, MAX_BANDS)
                for (i in 0 until minOf(bandCount, usable)) {
                    val bandIndex = if (usable <= bandCount) i else i * usable / bandCount
                    val millibel = applyPreamp(currentBandLevels[i].toInt())
                    try { sfx.equalizer.setBandLevel(bandIndex.toShort(), millibel) } catch (_: Throwable) {}
                }
            } catch (e: Throwable) { Log.e(TAG, "reapply EQ for session", e) }

            try { sfx.bassBoost?.apply {
                setStrength(currentBassBoost.coerceIn(0, BASS_BOOST_STRENGTH_MAX).toShort())
                enabled = currentEnabled && currentBassBoost > 0
            } } catch (e: Throwable) { Log.e(TAG, "reapply bass for session", e) }

            try { sfx.virtualizer?.apply {
                setStrength(currentVirtualizer.coerceIn(0, VIRTUALIZER_STRENGTH_MAX).toShort())
                enabled = currentEnabled && currentVirtualizer > 0
            } } catch (e: Throwable) { Log.e(TAG, "reapply virt for session", e) }

            try { sfx.loudnessEnhancer?.apply {
                if (currentLoudness > 0) setTargetGain(currentLoudness.coerceIn(0, 300))
            } } catch (e: Throwable) { Log.e(TAG, "reapply loud for session", e) }
        }
    }

    private fun attachToSession(sessionId: Int) {
        try {
            Log.d(TAG, "Attaching EQ to session $sessionId")
            val eq = Equalizer(0, sessionId)
            eq.enabled = currentEnabled

            val numBands = eq.numberOfBands.toInt()
            val usable = minOf(numBands, MAX_BANDS)

            for (i in 0 until minOf(bandCount, usable)) {
                val bandIndex = if (usable <= bandCount) i else i * usable / bandCount
                val millibel = applyPreamp(currentBandLevels[i].toInt())
                try { eq.setBandLevel(bandIndex.toShort(), millibel) } catch (_: Throwable) {}
            }

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
                loud = LoudnessEnhancer(sessionId)
                if (currentLoudness > 0) loud.setTargetGain(currentLoudness.coerceIn(0, 300))
            } catch (e: Throwable) { Log.w(TAG, "Loudness N/A for session $sessionId", e) }

            activeFX[sessionId] = SessionFX(sessionId, eq, bb, virt, loud)
            Log.d(TAG, "Session $sessionId: $numBands bands attached")

            if (bands.isEmpty()) {
                bands = pickBands(eq, bandCount, usable)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to attach to session $sessionId", e)
        }
    }

    private fun releaseSession(sessionId: Int) {
        activeFX.remove(sessionId)?.let { sfx ->
            sfx.equalizer.runCatching { release() }
            sfx.bassBoost?.runCatching { release() }
            sfx.virtualizer?.runCatching { release() }
            sfx.loudnessEnhancer?.runCatching { release() }
        }
    }

    private fun pickBands(eq: Equalizer, count: Int, usable: Int): List<BandInfo> {
        val indices = if (usable <= count) (0 until usable).toList()
        else (0 until count).map { it * usable / count }
        return indices.map { i ->
            BandInfo(i, eq.getCenterFreq(i.toShort()) / 1000, eq.bandLevelRange[0], eq.bandLevelRange[1])
        }
    }

    // ── Public API ──


    // Reapply all current band levels to global + per-session EQs with preamp compensation.
    // Called when effect sliders change (bass/virt/loud) so the preamp updates live.
    private fun reapplyBandLevelsToHardware() {
        for (i in 0 until bandCount) {
            val millibel = applyPreamp(currentBandLevels[i].toInt())
            bands.getOrNull(i)?.let { bi ->
                try { globalEQ?.setBandLevel(bi.index.toShort(), millibel) } catch (_: Throwable) {}
                for ((_, sfx) in activeFX) {
                    try { sfx.equalizer.setBandLevel(bi.index.toShort(), millibel) } catch (_: Throwable) {}
                }
            }
        }
    }

    fun setBandLevel(band: Int, level: Short) {
        markUserOverrideIfApplicable()
        currentBandLevels[band] = level
        persistLevels()
        val millibel = applyPreamp(level.toInt())
        audioExecutor.execute {
            try { bands.getOrNull(band)?.let { bi -> globalEQ?.setBandLevel(bi.index.toShort(), millibel) } }
            catch (e: Throwable) { Log.e(TAG, "globalEQ setBandLevel", e) }
            for ((_, sfx) in activeFX) {
                try { bands.getOrNull(band)?.let { bi -> sfx.equalizer.setBandLevel(bi.index.toShort(), millibel) } }
                catch (e: Throwable) { Log.e(TAG, "session EQ setBandLevel", e) }
            }
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
                for (i in 0 until bandCount) {
                    val millibel = applyPreamp(currentBandLevels[i].toInt())
                    try { bands.getOrNull(i)?.let { bi -> globalEQ?.setBandLevel(bi.index.toShort(), millibel) } } catch (_: Throwable) {}
                    for ((_, sfx) in activeFX) {
                        try { bands.getOrNull(i)?.let { bi -> sfx.equalizer.setBandLevel(bi.index.toShort(), millibel) } } catch (_: Throwable) {}
                    }
                }
            } catch (e: Throwable) { Log.e(TAG, "setBandCount", e) }
            mainHandler.post { onReady?.invoke(isReady, statusMessage, bands) }
        }
    }

    fun setBassBoost(strength: Int) {
        markUserOverrideIfApplicable()
        currentBassBoost = strength
        persistScalar(KEY_BASS, strength)
        audioExecutor.execute {
            try {
                globalBassBoost?.apply {
                    setStrength(strength.coerceIn(0, BASS_BOOST_STRENGTH_MAX).toShort())
                    enabled = strength > 0
                }
            } catch (e: Throwable) { Log.e(TAG, "Global BassBoost", e) }

            for ((_, sfx) in activeFX) {
                try { sfx.bassBoost?.apply { setStrength(strength.coerceIn(0, BASS_BOOST_STRENGTH_MAX).toShort()); enabled = strength > 0 } }
                catch (e: Throwable) { Log.e(TAG, "Session BassBoost", e) }
            }

            // Preamp changed — reapply band levels with new compensation
            reapplyBandLevelsToHardware()
        }
    }

    fun setVirtualizer(strength: Int) {
        markUserOverrideIfApplicable()
        currentVirtualizer = strength
        persistScalar(KEY_VIRT, strength)
        audioExecutor.execute {
            try {
                globalVirtualizer?.apply {
                    setStrength(strength.coerceIn(0, VIRTUALIZER_STRENGTH_MAX).toShort())
                    enabled = strength > 0
                }
            } catch (e: Throwable) { Log.e(TAG, "Global Virtualizer", e) }

            for ((_, sfx) in activeFX) {
                try { sfx.virtualizer?.apply { setStrength(strength.coerceIn(0, VIRTUALIZER_STRENGTH_MAX).toShort()); enabled = strength > 0 } }
                catch (e: Throwable) { Log.e(TAG, "Session Virtualizer", e) }
            }

            // Preamp changed — reapply band levels with new compensation
            reapplyBandLevelsToHardware()
        }
    }

    fun setLoudness(gain: Int) {
        markUserOverrideIfApplicable()
        val safeGain = gain.coerceIn(0, 300)
        currentLoudness = safeGain
        persistScalar(KEY_LOUD, safeGain)
        audioExecutor.execute {
            try {
                globalLoudness?.setTargetGain(safeGain)
            } catch (e: Throwable) { Log.e(TAG, "Global Loudness", e) }

            for ((_, sfx) in activeFX) {
                try { sfx.loudnessEnhancer?.setTargetGain(safeGain) }
                catch (e: Throwable) { Log.e(TAG, "Session Loudness", e) }
            }

            // Preamp changed — reapply band levels with new compensation
            reapplyBandLevelsToHardware()
        }
    }

    // Batch-set all band levels in a single audio thread task — used by preset
    // animation frames so we enqueue ONE job per frame instead of 31 separate
    // setBandLevel calls (372 total during a 12-frame animation). Same result,
    // far less thread contention and native API churn.
    fun setBandLevels(levels: ShortArray) {
        markUserOverrideIfApplicable()
        for (i in levels.indices) {
            if (i < currentBandLevels.size) currentBandLevels[i] = levels[i]
        }
        persistLevels()
        audioExecutor.execute {
            for (band in 0 until levels.size) {
                val millibel = applyPreamp(levels[band].toInt())
                bands.getOrNull(band)?.let { bi ->
                    try { globalEQ?.setBandLevel(bi.index.toShort(), millibel) } catch (_: Throwable) {}
                    for ((_, sfx) in activeFX) {
                        try { sfx.equalizer.setBandLevel(bi.index.toShort(), millibel) } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        currentEnabled = on
        try { prefs.edit().putBoolean(KEY_ENABLED, on).apply() } catch (_: Throwable) { }
        audioExecutor.execute {
            try { globalEQ?.enabled = on } catch (_: Throwable) {}
            try { globalBassBoost?.enabled = on && currentBassBoost > 0 } catch (_: Throwable) {}
            try { globalVirtualizer?.enabled = on && currentVirtualizer > 0 } catch (_: Throwable) {}
            try { if (!on) globalLoudness?.setTargetGain(0) else globalLoudness?.setTargetGain(currentLoudness.coerceIn(0, 300)) } catch (_: Throwable) {}
            try { visualizer?.enabled = on } catch (_: Throwable) {}

            for ((_, sfx) in activeFX) {
                try { sfx.equalizer.enabled = on } catch (_: Throwable) {}
                try { sfx.bassBoost?.enabled = on && (sfx.bassBoost?.roundedStrength ?: 0) > 0 } catch (_: Throwable) {}
                try { sfx.virtualizer?.enabled = on && (sfx.virtualizer?.roundedStrength ?: 0) > 0 } catch (_: Throwable) {}
                try { if (!on) sfx.loudnessEnhancer?.setTargetGain(0) else sfx.loudnessEnhancer?.setTargetGain(currentLoudness.coerceIn(0, 300)) } catch (_: Throwable) {}
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
