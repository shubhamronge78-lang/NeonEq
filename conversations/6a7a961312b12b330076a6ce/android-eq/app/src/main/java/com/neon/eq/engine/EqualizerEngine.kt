package com.neon.eq.engine

import android.content.Context
import android.media.audiofx.*
import android.util.Log

class EqualizerEngine(private val context: Context) {

    companion object {
        const val MAX_BANDS = 31
        const val MIN_BANDS = 5
        const val BASS_BOOST_STRENGTH_MAX = 1000
        const val VIRTUALIZER_STRENGTH_MAX = 1000
    }

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var audioSessionId: Int = 0

    var bandCount = 10
        private set

    data class BandInfo(val index: Int, val freq: Int, val minLevel: Short, val maxLevel: Short)

    var bands: List<BandInfo> = emptyList()
        private set

    var enabled = false
        private set

    fun attachToSession(sessionId: Int) {
        audioSessionId = sessionId
        release()
        setup()
    }

    private fun setup() {
        try {
            equalizer = Equalizer(0, audioSessionId).also {
                it.enabled = true
                val numBands = it.numberOfBands.toInt()
                val usableBands = minOf(numBands, MAX_BANDS)
                val step = usableBands / bandCount
                bands = (0 until usableBands step maxOf(step, 1)).take(bandCount).map { i ->
                    BandInfo(
                        index = i,
                        freq = it.getCenterFreq(i.toShort()) / 1000, // Hz
                        minLevel = it.bandLevelRange[0],
                        maxLevel = it.bandLevelRange[1]
                    )
                }
            }

            bassBoost = BassBoost(0, audioSessionId).also { it.enabled = false }
            virtualizer = Virtualizer(0, audioSessionId).also { it.enabled = false }
            loudnessEnhancer = LoudnessEnhancer(audioSessionId)
            enabled = true
        } catch (e: Exception) {
            Log.e("EqualizerEngine", "Setup failed", e)
        }
    }

    fun setBandLevel(bandIndex: Int, level: Short) {
        try {
            val info = bands.getOrNull(bandIndex) ?: return
            equalizer?.setBandLevel(info.index.toShort(), level)
        } catch (e: Exception) {
            Log.e("EqualizerEngine", "setBandLevel failed", e)
        }
    }

    fun setBandCount(count: Int) {
        bandCount = count.coerceIn(MIN_BANDS, MAX_BANDS)
        setup()
    }

    fun setBassBoost(strength: Int) {
        try {
            bassBoost?.apply {
                setStrength(strength.coerceIn(0, BASS_BOOST_STRENGTH_MAX).toShort())
                enabled = strength > 0
            }
        } catch (e: Exception) {
            Log.e("EqualizerEngine", "BassBoost failed", e)
        }
    }

    fun setVirtualizer(strength: Int) {
        try {
            virtualizer?.apply {
                setStrength(strength.coerceIn(0, VIRTUALIZER_STRENGTH_MAX).toShort())
                enabled = strength > 0
            }
        } catch (e: Exception) {
            Log.e("EqualizerEngine", "Virtualizer failed", e)
        }
    }

    fun setLoudness(gain: Int) {
        try {
            loudnessEnhancer?.setTargetGain(gain.coerceIn(0, 4000))
        } catch (e: Exception) {
            Log.e("EqualizerEngine", "Loudness failed", e)
        }
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        equalizer?.enabled = on
        bassBoost?.enabled = on && (bassBoost?.roundedStrength ?: 0) > 0
        virtualizer?.enabled = on && (virtualizer?.roundedStrength ?: 0) > 0
    }

    fun applyPreset(levels: ShortArray) {
        levels.forEachIndexed { i, level -> setBandLevel(i, level) }
    }

    fun release() {
        equalizer?.release(); equalizer = null
        bassBoost?.release(); bassBoost = null
        virtualizer?.release(); virtualizer = null
        loudnessEnhancer?.release(); loudnessEnhancer = null
        enabled = false
    }
}
