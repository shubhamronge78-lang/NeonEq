package com.neon.eq.engine

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class EqualizerEngine(private val context: Context) {

    companion object {
        const val MAX_BANDS = 31
        const val MIN_BANDS = 5
        const val BASS_BOOST_STRENGTH_MAX = 1000
        const val VIRTUALIZER_STRENGTH_MAX = 1000
        private const val TAG = "NeonEQ"
        private const val DB_TO_MILLIBEL = 100
        private const val POLL_INTERVAL_MS = 1500L
    }

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
    private var globalEQ: Equalizer? = null

    @Volatile var bandCount = 5
        private set
    @Volatile var bands: List<BandInfo> = emptyList()
    @Volatile var enabled = false
        private set
    @Volatile var isReady = false
    @Volatile var statusMessage = "Initializing..."
    @Volatile var activeSessionCount = 0

    private val currentBandLevels = ShortArray(31) { 0 }
    private var currentBassBoost = 0
    private var currentVirtualizer = 0
    private var currentLoudness = 0
    private var currentEnabled = true

    var onReady: ((Boolean, String, List<BandInfo>) -> Unit)? = null
    var onSessionUpdate: ((Int) -> Unit)? = null

    data class BandInfo(val index: Int, val freq: Int, val minLevel: Short, val maxLevel: Short)

    fun attachToGlobalSession() {
        audioExecutor.execute {
            releaseInternal()

            // ── Try global session 0 ──
            try {
                globalEQ = Equalizer(0, 0).also { eq ->
                    eq.enabled = true
                    val numBands = eq.numberOfBands.toInt()
                    val usable = minOf(numBands, MAX_BANDS)
                    bands = pickBands(eq, bandCount, usable)
                    Log.d(TAG, "Global session 0: $numBands bands")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Session 0 failed: ${e.message}")
                globalEQ = null
            }

            if (globalEQ != null) {
                statusMessage = "Global EQ ready — play music to test"
                isReady = true
                enabled = true
            } else {
                statusMessage = "Scanning for audio sessions..."
                isReady = true
                enabled = true
            }

            startSessionPolling()
            scanForActiveSessions()

            mainHandler.post { onReady?.invoke(isReady, statusMessage, bands) }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            scanForActiveSessions()
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

    private fun scanForActiveSessions() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val configs = am.getActivePlaybackConfigurations()

            val activeSessionIds = mutableSetOf<Int>()
            for (config in configs) {
                val sessionId = config.audioSessionId
                if (sessionId != 0 && config.isActive) {
                    activeSessionIds.add(sessionId)
                }
            }

            Log.d(TAG, "Active sessions: $activeSessionIds (${configs.size} configs)")

            // Remove stale sessions
            val stale = activeFX.keys - activeSessionIds
            for (id in stale) {
                Log.d(TAG, "Removing stale session $id")
                releaseSession(id)
            }

            // Attach to new sessions
            for (sessionId in activeSessionIds) {
                if (!activeFX.containsKey(sessionId)) {
                    attachToSession(sessionId)
                }
            }

            // Update band info
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
                statusMessage = "EQ active on ${activeFX.size} session(s)"
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
        } catch (e: Exception) {
            Log.e(TAG, "Session scan failed", e)
        }
    }

    private fun attachToSession(sessionId: Int) {
        try {
            Log.d(TAG, "Attaching EQ to session $sessionId")
            val eq = Equalizer(0, sessionId)
            eq.enabled = currentEnabled

            val numBands = eq.numberOfBands.toInt()
            val usable = minOf(numBands, MAX_BANDS)

            // Apply stored band levels to this new session
            for (i in 0 until minOf(bandCount, usable)) {
                val bandIndex = if (usable <= bandCount) i else i * usable / bandCount
                val millibel = (currentBandLevels[i].toInt() * DB_TO_MILLIBEL).toShort()
                try { eq.setBandLevel(bandIndex.toShort(), millibel) } catch (_: Exception) {}
            }

            var bb: BassBoost? = null
            var virt: Virtualizer? = null
            var loud: LoudnessEnhancer? = null
            try {
                bb = BassBoost(0, sessionId).also {
                    it.enabled = currentBassBoost > 0
                    if (currentBassBoost > 0) it.setStrength(currentBassBoost.coerceIn(0, BASS_BOOST_STRENGTH_MAX).toShort())
                }
            } catch (e: Exception) { Log.w(TAG, "BassBoost N/A for session $sessionId", e) }
            try {
                virt = Virtualizer(0, sessionId).also {
                    it.enabled = currentVirtualizer > 0
                    if (currentVirtualizer > 0) it.setStrength(currentVirtualizer.coerceIn(0, VIRTUALIZER_STRENGTH_MAX).toShort())
                }
            } catch (e: Exception) { Log.w(TAG, "Virtualizer N/A for session $sessionId", e) }
            try {
                loud = LoudnessEnhancer(sessionId)
                if (currentLoudness > 0) loud.setTargetGain(currentLoudness.coerceIn(0, 4000))
            } catch (e: Exception) { Log.w(TAG, "Loudness N/A for session $sessionId", e) }

            activeFX[sessionId] = SessionFX(sessionId, eq, bb, virt, loud)
            Log.d(TAG, "Session $sessionId: $numBands bands attached")

            if (bands.isEmpty()) {
                bands = pickBands(eq, bandCount, usable)
            }
        } catch (e: Exception) {
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
        val millibel = (level.toInt() * DB_TO_MILLIBEL).toShort()
        audioExecutor.execute {
            try { bands.getOrNull(band)?.let { bi -> globalEQ?.setBandLevel(bi.index.toShort(), millibel) } }
            catch (e: Exception) { Log.e(TAG, "globalEQ setBandLevel", e) }
            for ((_, sfx) in activeFX) {
                try { bands.getOrNull(band)?.let { bi -> sfx.equalizer.setBandLevel(bi.index.toShort(), millibel) } }
                catch (e: Exception) { Log.e(TAG, "session EQ setBandLevel", e) }
            }
        }
    }

    fun setBandCount(count: Int) {
        bandCount = count.coerceIn(MIN_BANDS, MAX_BANDS)
        audioExecutor.execute {
            try {
                val anyEQ = globalEQ ?: activeFX.values.firstOrNull()?.equalizer
                if (anyEQ != null) {
                    val usable = minOf(anyEQ.numberOfBands.toInt(), MAX_BANDS)
                    bands = pickBands(anyEQ, bandCount, usable)
                }
            } catch (e: Exception) { Log.e(TAG, "setBandCount", e) }
            mainHandler.post { onReady?.invoke(isReady, statusMessage, bands) }
        }
    }

    fun setBassBoost(strength: Int) {
        currentBassBoost = strength
        audioExecutor.execute {
            for ((_, sfx) in activeFX) {
                try { sfx.bassBoost?.apply { setStrength(strength.coerceIn(0, BASS_BOOST_STRENGTH_MAX).toShort()); enabled = strength > 0 } }
                catch (e: Exception) { Log.e(TAG, "BassBoost", e) }
            }
        }
    }

    fun setVirtualizer(strength: Int) {
        currentVirtualizer = strength
        audioExecutor.execute {
            for ((_, sfx) in activeFX) {
                try { sfx.virtualizer?.apply { setStrength(strength.coerceIn(0, VIRTUALIZER_STRENGTH_MAX).toShort()); enabled = strength > 0 } }
                catch (e: Exception) { Log.e(TAG, "Virtualizer", e) }
            }
        }
    }

    fun setLoudness(gain: Int) {
        currentLoudness = gain
        audioExecutor.execute {
            for ((_, sfx) in activeFX) {
                try { sfx.loudnessEnhancer?.setTargetGain(gain.coerceIn(0, 4000)) }
                catch (e: Exception) { Log.e(TAG, "Loudness", e) }
            }
        }
    }

    fun setReverb(preset: Short) {}

    fun setEnabled(on: Boolean) {
        enabled = on
        currentEnabled = on
        audioExecutor.execute {
            try { globalEQ?.enabled = on } catch (_: Exception) {}
            for ((_, sfx) in activeFX) {
                try { sfx.equalizer.enabled = on } catch (_: Exception) {}
                try { sfx.bassBoost?.enabled = on && (sfx.bassBoost?.roundedStrength ?: 0) > 0 } catch (_: Exception) {}
                try { sfx.virtualizer?.enabled = on && (sfx.virtualizer?.roundedStrength ?: 0) > 0 } catch (_: Exception) {}
            }
        }
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
        enabled = false; isReady = false
    }
}
