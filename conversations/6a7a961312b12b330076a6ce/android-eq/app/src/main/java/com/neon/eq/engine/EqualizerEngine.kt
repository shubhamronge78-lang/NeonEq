package com.neon.eq.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.*
import android.util.Log

class EqualizerEngine(private val context: Context) {

    companion object {
        const val MAX_BANDS = 31
        const val MIN_BANDS = 5
        const val BASS_BOOST_STRENGTH_MAX = 1000
        const val VIRTUALIZER_STRENGTH_MAX = 1000
        private const val TAG = "EqualizerEngine"
    }

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var audioSessionId: Int = 0
    private var silenceTrack: AudioTrack? = null

    var bandCount = 10
        private set

    data class BandInfo(val index: Int, val freq: Int, val minLevel: Short, val maxLevel: Short)

    var bands: List<BandInfo> = emptyList()
        private set

    var enabled = false
        private set

    var isReady = false
        private set

    var statusMessage: String = "Initializing..."
        private set

    /**
     * Creates a dedicated audio session and plays silence to keep it alive.
     * This is the key trick for system-wide EQ: we create our own session,
     * attach the AudioEffects to it, and play silent audio so the effects
     * stay active even when no other app is playing audio.
     */
    fun attachToSession(sessionId: Int) {
        audioSessionId = sessionId
        release()
        setup()
    }

    fun attachToGlobalSession() {
        release()
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioSessionId = am.generateAudioSessionId()

            // Create and play a silent AudioTrack to keep the session alive
            val sampleRate = 44100
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            silenceTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setSessionId(audioSessionId)
                .build()

            // Write a tiny buffer of silence
            val silence = ShortArray(bufferSize)
            silenceTrack?.write(silence, 0, silence.size)
            silenceTrack?.play()

            setup()
        } catch (e: Exception) {
            Log.e(TAG, "Global session setup failed, trying session 0", e)
            audioSessionId = 0
            setup()
        }
    }

    private fun setup() {
        try {
            equalizer = Equalizer(0, audioSessionId).also { eq ->
                eq.enabled = true
                val numBands = eq.numberOfBands.toInt()
                val usableBands = minOf(numBands, MAX_BANDS)

                // Pick evenly spaced bands
                val indices = if (usableBands <= bandCount) {
                    (0 until usableBands).toList()
                } else {
                    (0 until bandCount).map { i ->
                        (i * usableBands / bandCount)
                    }
                }

                bands = indices.map { i ->
                    BandInfo(
                        index = i,
                        freq = eq.getCenterFreq(i.toShort()) / 1000, // milliHz -> Hz
                        minLevel = eq.bandLevelRange[0],
                        maxLevel = eq.bandLevelRange[1]
                    )
                }

                statusMessage = "Equalizer ready: $numBands bands available"
                isReady = true
            }

            try {
                bassBoost = BassBoost(0, audioSessionId).also { it.enabled = false }
            } catch (e: Exception) {
                Log.w(TAG, "BassBoost not available", e)
            }

            try {
                virtualizer = Virtualizer(0, audioSessionId).also { it.enabled = false }
            } catch (e: Exception) {
                Log.w(TAG, "Virtualizer not available", e)
            }

            try {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
            } catch (e: Exception) {
                Log.w(TAG, "LoudnessEnhancer not available", e)
            }

            enabled = true
        } catch (e: Exception) {
            Log.e(TAG, "Setup failed", e)
            statusMessage = "EQ setup failed: ${e.message}"
            isReady = false
        }
    }

    fun setBandLevel(bandIndex: Int, level: Short) {
        try {
            val info = bands.getOrNull(bandIndex) ?: return
            equalizer?.setBandLevel(info.index.toShort(), level)
        } catch (e: Exception) {
            Log.e(TAG, "setBandLevel failed", e)
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
            Log.e(TAG, "BassBoost failed", e)
        }
    }

    fun setVirtualizer(strength: Int) {
        try {
            virtualizer?.apply {
                setStrength(strength.coerceIn(0, VIRTUALIZER_STRENGTH_MAX).toShort())
                enabled = strength > 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Virtualizer failed", e)
        }
    }

    fun setLoudness(gain: Int) {
        try {
            loudnessEnhancer?.setTargetGain(gain.coerceIn(0, 4000))
        } catch (e: Exception) {
            Log.e(TAG, "Loudness failed", e)
        }
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        try { equalizer?.enabled = on } catch (_: Exception) {}
        try { bassBoost?.enabled = on && (bassBoost?.roundedStrength ?: 0) > 0 } catch (_: Exception) {}
        try { virtualizer?.enabled = on && (virtualizer?.roundedStrength ?: 0) > 0 } catch (_: Exception) {}
    }

    fun applyPreset(levels: ShortArray) {
        levels.forEachIndexed { i, level -> setBandLevel(i, level) }
    }

    fun release() {
        try { equalizer?.release() } catch (_: Exception) {}
        equalizer = null
        try { bassBoost?.release() } catch (_: Exception) {}
        bassBoost = null
        try { virtualizer?.release() } catch (_: Exception) {}
        virtualizer = null
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        try { silenceTrack?.stop(); silenceTrack?.release() } catch (_: Exception) {}
        silenceTrack = null
        enabled = false
        isReady = false
    }
}
