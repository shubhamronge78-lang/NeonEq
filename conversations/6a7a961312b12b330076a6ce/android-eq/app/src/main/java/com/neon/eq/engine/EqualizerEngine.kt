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
        private const val TAG = "NeonEQ"
    }

    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NeonEQ-Audio").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var reverb: PresetReverb? = null
    private var audioSessionId: Int = 0
    private var silenceTrack: AudioTrack? = null

    @Volatile var bandCount = 5
        private set
    @Volatile var bands: List<BandInfo> = emptyList()
    @Volatile var enabled = false
        private set
    @Volatile var isReady = false
    @Volatile var statusMessage = "Initializing..."

    var onReady: ((Boolean, String, List<BandInfo>) -> Unit)? = null

    data class BandInfo(val index: Int, val freq: Int, val minLevel: Short, val maxLevel: Short)

    fun attachToGlobalSession() {
        audioExecutor.execute {
            releaseInternal()
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioSessionId = am.generateAudioSessionId()
                Log.d(TAG, "Session: $audioSessionId")

                val sampleRate = 44100
                val bufSize = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(64)

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
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setSessionId(audioSessionId)
                    .build()

                silenceTrack?.write(ShortArray(bufSize), 0, bufSize)
                silenceTrack?.play()
                Log.d(TAG, "Silence track playing")

                setupInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Global session failed", e)
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
                val usable = minOf(numBands, MAX_BANDS)
                bands = pickBands(eq, bandCount, usable)
                statusMessage = "Ready: $numBands bands"
                isReady = true
                enabled = true
            }
            try { bassBoost = BassBoost(0, audioSessionId).also { it.enabled = false } } catch (e: Exception) { Log.w(TAG, "BassBoost N/A", e) }
            try { virtualizer = Virtualizer(0, audioSessionId).also { it.enabled = false } } catch (e: Exception) { Log.w(TAG, "Virtualizer N/A", e) }
            try { loudnessEnhancer = LoudnessEnhancer(audioSessionId) } catch (e: Exception) { Log.w(TAG, "Loudness N/A", e) }
            try { reverb = PresetReverb(0, audioSessionId).also { it.enabled = false } } catch (e: Exception) { Log.w(TAG, "Reverb N/A", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Setup failed", e)
            statusMessage = "Failed: ${e.message}"
            isReady = false
        }
        mainHandler.post { onReady?.invoke(isReady, statusMessage, bands) }
    }

    private fun pickBands(eq: Equalizer, count: Int, usable: Int): List<BandInfo> {
        val indices = if (usable <= count) (0 until usable).toList()
        else (0 until count).map { it * usable / count }
        return indices.map { i ->
            BandInfo(i, eq.getCenterFreq(i.toShort()) / 1000, eq.bandLevelRange[0], eq.bandLevelRange[1])
        }
    }

    fun setBandLevel(band: Int, level: Short) {
        audioExecutor.execute {
            try { bands.getOrNull(band)?.let { equalizer?.setBandLevel(it.index.toShort(), level) } }
            catch (e: Exception) { Log.e(TAG, "setBandLevel", e) }
        }
    }

    fun setBandCount(count: Int) {
        bandCount = count.coerceIn(MIN_BANDS, MAX_BANDS)
        audioExecutor.execute {
            try {
                equalizer?.let { eq ->
                    val usable = minOf(eq.numberOfBands.toInt(), MAX_BANDS)
                    bands = pickBands(eq, bandCount, usable)
                }
            } catch (e: Exception) { Log.e(TAG, "setBandCount", e) }
            mainHandler.post { onReady?.invoke(isReady, statusMessage, bands) }
        }
    }

    fun setBassBoost(strength: Int) = audioExecutor.execute {
        try { bassBoost?.apply { setStrength(strength.coerceIn(0, BASS_BOOST_STRENGTH_MAX).toShort()); enabled = strength > 0 } }
        catch (e: Exception) { Log.e(TAG, "BassBoost", e) }
    }

    fun setVirtualizer(strength: Int) = audioExecutor.execute {
        try { virtualizer?.apply { setStrength(strength.coerceIn(0, VIRTUALIZER_STRENGTH_MAX).toShort()); enabled = strength > 0 } }
        catch (e: Exception) { Log.e(TAG, "Virtualizer", e) }
    }

    fun setLoudness(gain: Int) = audioExecutor.execute {
        try { loudnessEnhancer?.setTargetGain(gain.coerceIn(0, 4000)) }
        catch (e: Exception) { Log.e(TAG, "Loudness", e) }
    }

    fun setReverb(preset: Short) = audioExecutor.execute {
        try { reverb?.apply { setPreset(preset); enabled = preset != PresetReverb.PRESET_NONE } }
        catch (e: Exception) { Log.e(TAG, "Reverb", e) }
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        audioExecutor.execute {
            try { equalizer?.enabled = on } catch (_: Exception) {}
            try { bassBoost?.enabled = on && (bassBoost?.roundedStrength ?: 0) > 0 } catch (_: Exception) {}
            try { virtualizer?.enabled = on && (virtualizer?.roundedStrength ?: 0) > 0 } catch (_: Exception) {}
        }
    }

    fun release() = audioExecutor.execute { releaseInternal() }

    private fun releaseInternal() {
        equalizer?.runCatching { release() }; equalizer = null
        bassBoost?.runCatching { release() }; bassBoost = null
        virtualizer?.runCatching { release() }; virtualizer = null
        loudnessEnhancer?.runCatching { release() }; loudnessEnhancer = null
        reverb?.runCatching { release() }; reverb = null
        silenceTrack?.runCatching { stop(); release() }; silenceTrack = null
        enabled = false; isReady = false
    }
}
