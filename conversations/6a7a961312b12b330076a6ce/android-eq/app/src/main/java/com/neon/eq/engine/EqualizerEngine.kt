package com.neon.eq.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

class EqualizerEngine(private val context: Context) {

    companion object {
        const val MAX_BANDS = 31
        const val MIN_BANDS = 5
        const val BASS_BOOST_STRENGTH_MAX = 1000
        const val VIRTUALIZER_STRENGTH_MAX = 1000
        private const val TAG = "EqualizerEngine"
    }

    // Single background thread for ALL audio operations — never blocks UI
    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NeonEQ-Audio").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var audioSessionId: Int = 0
    private var silenceTrack: AudioTrack? = null

    @Volatile var bandCount = 5
        private set

    data class BandInfo(val index: Int, val freq: Int, val minLevel: Short, val maxLevel: Short)

    @Volatile var bands: List<BandInfo> = emptyList()
        private set

    @Volatile var enabled = false
        private set

    @Volatile var isReady = false
        private set

    @Volatile var statusMessage: String = "Initializing..."
        private set

    // Callback for UI updates
    var onReady: ((Boolean, String, List<BandInfo>) -> Unit)? = null

    fun attachToGlobalSession() {
        audioExecutor.execute {
            releaseInternal()
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioSessionId = am.generateAudioSessionId()
                Log.d(TAG, "Generated audio session: $audioSessionId")

                // Create silent AudioTrack to keep session alive
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
                    .setBufferSizeInBytes(bufferSize.coerceAtLeast(1))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setSessionId(audioSessionId)
                    .build()

                val silence = ShortArray(bufferSize.coerceAtLeast(1))
                silenceTrack?.write(silence, 0, silence.size)
                silenceTrack?.play()
                Log.d(TAG, "Silence track playing on session $audioSessionId")

                setupInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Global session failed, trying session 0", e)
                audioSessionId = 0
                setupInternal()
            }
        }
    }

    private fun setupInternal() {
        try {
            equalizer = Equalizer(0, audioSessionId).also { eq ->
                eq.enabled = true
                val numBands = eq.numberOfBands.toInt()
                val usableBands = minOf(numBands, MAX_BANDS)

                val indices = if (usableBands <= bandCount) {
                    (0 until usableBands).toList()
                } else {
                    (0 until bandCount).map { i -> i * usableBands / bandCount }
                }

                bands = indices.map { i ->
                    BandInfo(
                        index = i,
                        freq = eq.getCenterFreq(i.toShort()) / 1000,
                        minLevel = eq.bandLevelRange[0],
                        maxLevel = eq.bandLevelRange[1]
                    )
                }

                statusMessage = "Ready — ${numBands} bands, session $audioSessionId"
                isReady = true
                enabled = true
            }

            try { bassBoost = BassBoost(0, audioSessionId).also { it.enabled = false } } catch (e: Exception) { Log.w(TAG, "BassBoost N/A", e) }
            try { virtualizer = Virtualizer(0, audioSessionId).also { it.enabled = false } } catch (e: Exception) { Log.w(TAG, "Virtualizer N/A", e) }
            try { loudnessEnhancer = LoudnessEnhancer(audioSessionId) } catch (e: Exception) { Log.w(TAG, "Loudness N/A", e) }

        } catch (e: Exception) {
            Log.e(TAG, "Setup failed", e)
            statusMessage = "Setup failed: ${e.message}"
            isReady = false
        }

        // Notify UI on main thread
        val ready = isReady
        val msg = statusMessage
        val bandList = bands
        mainHandler.post {
            onReady?.invoke(ready, msg, bandList)
        }
    }

    fun setBandLevel(bandIndex: Int, level: Short) {
        audioExecutor.execute {
            try {
                val info = bands.getOrNull(bandIndex) ?: return@execute
                equalizer?.setBandLevel(info.index.toShort(), level)
            } catch (e: Exception) {
                Log.e(TAG, "setBandLevel", e)
            }
        }
    }

    fun setBandCount(count: Int) {
        bandCount = count.coerceIn(MIN_BANDS, MAX_BANDS)
        audioExecutor.execute {
            // Just re-read bands, don't recreate the Equalizer
            try {
                equalizer?.let { eq ->
                    val numBands = eq.numberOfBands.toInt()
                    val usableBands = minOf(numBands, MAX_BANDS)
                    val indices = if (usableBands <= bandCount) {
                        (0 until usableBands).toList()
                    } else {
                        (0 until bandCount).map { i -> i * usableBands / bandCount }
                    }
                    bands = indices.map { i ->
                        BandInfo(
                            index = i,
                            freq = eq.getCenterFreq(i.toShort()) / 1000,
                            minLevel = eq.bandLevelRange[0],
                            maxLevel = eq.bandLevelRange[1]
                        )
                    }
                    statusMessage = "Ready — ${numBands} bands, ${bandCount} shown"
                }
            } catch (e: Exception) {
                Log.e(TAG, "setBandCount", e)
            }

            val bandList = bands
            val msg = statusMessage
            mainHandler.post {
                onReady?.invoke(isReady, msg, bandList)
            }
        }
    }

    fun setBassBoost(strength: Int) {
        audioExecutor.execute {
            try {
                bassBoost?.apply {
                    setStrength(strength.coerceIn(0, BASS_BOOST_STRENGTH_MAX).toShort())
                    enabled = strength > 0
                }
            } catch (e: Exception) { Log.e(TAG, "BassBoost", e) }
        }
    }

    fun setVirtualizer(strength: Int) {
        audioExecutor.execute {
            try {
                virtualizer?.apply {
                    setStrength(strength.coerceIn(0, VIRTUALIZER_STRENGTH_MAX).toShort())
                    enabled = strength > 0
                }
            } catch (e: Exception) { Log.e(TAG, "Virtualizer", e) }
        }
    }

    fun setLoudness(gain: Int) {
        audioExecutor.execute {
            try { loudnessEnhancer?.setTargetGain(gain.coerceIn(0, 4000)) }
            catch (e: Exception) { Log.e(TAG, "Loudness", e) }
        }
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        audioExecutor.execute {
            try { equalizer?.enabled = on } catch (_: Exception) {}
            try { bassBoost?.enabled = on && (bassBoost?.roundedStrength ?: 0) > 0 } catch (_: Exception) {}
            try { virtualizer?.enabled = on && (virtualizer?.roundedStrength ?: 0) > 0 } catch (_: Exception) {}
        }
    }

    fun release() {
        audioExecutor.execute { releaseInternal() }
    }

    private fun releaseInternal() {
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
