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
        const val BASS_BOOST_STRENGTH_MAX = 1000
        const val VIRTUALIZER_STRENGTH_MAX = 1000
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

    private val currentBandLevels = loadLevels()
    private var currentBassBoost = prefs.getInt(KEY_BASS, 0)
    private var currentVirtualizer = prefs.getInt(KEY_VIRT, 0)
    private var currentLoudness = prefs.getInt(KEY_LOUD, 0).coerceIn(0, 2000)
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

    fun currentLevelsSnapshot(): ShortArray = currentBandLevels.copyOf()
    fun currentBassBoostValue(): Int = currentBassBoost
    fun currentVirtualizerValue(): Int = currentVirtualizer
    fun currentLoudnessValue(): Int = currentLoudness

    fun setSelectedPresetName(name: String) {
        selectedPresetName = name
        try { prefs.edit().putString(KEY_PRESET_NAME, name).apply() } catch (_: Throwable) { }
    }

    // Called on startup when auto-apply preset setting is enabled.
    // Restores the last saved preset's band levels + effects into the live engine.
    fun applyLastPreset(): Boolean {
        if (!isAutoApplyPreset()) return false
        val name = selectedPresetName
        if (name == "Flat" || name == "Custom") return false

        // Check built-in presets first
        val builtIn = Presets.presets.find { it.name == name }
        if (builtIn != null) {
            val target = ShortArray(31) { i -> builtIn.levels.getOrElse(i) { 0 } }
            try {
                for (i in 0 until 31) {
                    bands.getOrNull(i)?.let { it.level = target[i] }
                }
                currentBandLevels = target
                persistLevels()
            } catch (_: Throwable) { }
            return true
        }

        // Check custom presets
        val custom = listCustomPresets().find { it.name == name }
        if (custom != null) {
            try {
                for (i in 0 until 31) {
                    bands.getOrNull(i)?.let { it.level = custom.levels.getOrElse(i) { 0 } }
                }
                currentBandLevels = ShortArray(31) { i -> custom.levels.getOrElse(i) { 0 } }
                persistLevels()
                setBassBoost(custom.bassBoost)
                setVirtualizer(custom.virtualizer)
                setLoudness(custom.loudness)
            } catch (_: Throwable) { }
            return true
        }
        return false
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
                            val millibel = (currentBandLevels[i].toInt() * DB_TO_MILLIBEL).toShort()
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
                            if (currentLoudness > 0) le.setTargetGain(currentLoudness.coerceIn(0, 2000))
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

    private fun reflectSessionId(config: AudioPlaybackConfiguration): Int {
        return try { (sessionIdMethod?.invoke(config) as? Int) ?: 0 } catch (t: Throwable) { 0 }
    }
    private fun reflectIsActive(config: AudioPlaybackConfiguration): Boolean {
        return try { (isActiveMethod?.invoke(config) as? Boolean) ?: false } catch (t: Throwable) { false }
    }

    private fun scanForActiveSessions() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val configs = am.getActivePlaybackConfigurations()

            val activeSessionIds = mutableSetOf<Int>()
            for (config in configs) {
                val sessionId = reflectSessionId(config)
                if (sessionId != 0 && reflectIsActive(config)) {
                    activeSessionIds.add(sessionId)
                }
            }

            Log.d(TAG, "Active sessions: $activeSessionIds (${configs.size} configs)")

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

    private fun attachToSession(sessionId: Int) {
        try {
            Log.d(TAG, "Attaching EQ to session $sessionId")
            val eq = Equalizer(0, sessionId)
            eq.enabled = currentEnabled

            val numBands = eq.numberOfBands.toInt()
            val usable = minOf(numBands, MAX_BANDS)

            for (i in 0 until minOf(bandCount, usable)) {
                val bandIndex = if (usable <= bandCount) i else i * usable / bandCount
                val millibel = (currentBandLevels[i].toInt() * DB_TO_MILLIBEL).toShort()
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
                if (currentLoudness > 0) loud.setTargetGain(currentLoudness.coerceIn(0, 2000))
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

    fun setBandLevel(band: Int, level: Short) {
        currentBandLevels[band] = level
        persistLevels()
        val millibel = (level.toInt() * DB_TO_MILLIBEL).toShort()
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
                    val millibel = (currentBandLevels[i].toInt() * DB_TO_MILLIBEL).toShort()
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
        }
    }

    fun setVirtualizer(strength: Int) {
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
        }
    }

    fun setLoudness(gain: Int) {
        val safeGain = gain.coerceIn(0, 2000)
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
        }
    }

    // Batch-set all band levels in a single audio thread task — used by preset
    // animation frames so we enqueue ONE job per frame instead of 31 separate
    // setBandLevel calls (372 total during a 12-frame animation). Same result,
    // far less thread contention and native API churn.
    fun setBandLevels(levels: ShortArray) {
        for (i in levels.indices) {
            if (i < currentBandLevels.size) currentBandLevels[i] = levels[i]
        }
        persistLevels()
        audioExecutor.execute {
            for (band in 0 until levels.size) {
                val millibel = (levels[band].toInt() * DB_TO_MILLIBEL).toShort()
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
            try { if (!on) globalLoudness?.setTargetGain(0) else globalLoudness?.setTargetGain(currentLoudness.coerceIn(0, 2000)) } catch (_: Throwable) {}
            try { visualizer?.enabled = on } catch (_: Throwable) {}

            for ((_, sfx) in activeFX) {
                try { sfx.equalizer.enabled = on } catch (_: Throwable) {}
                try { sfx.bassBoost?.enabled = on && (sfx.bassBoost?.roundedStrength ?: 0) > 0 } catch (_: Throwable) {}
                try { sfx.virtualizer?.enabled = on && (sfx.virtualizer?.roundedStrength ?: 0) > 0 } catch (_: Throwable) {}
                try { if (!on) sfx.loudnessEnhancer?.setTargetGain(0) else sfx.loudnessEnhancer?.setTargetGain(currentLoudness.coerceIn(0, 2000)) } catch (_: Throwable) {}
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
