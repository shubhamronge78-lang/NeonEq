package com.neon.eq.capture

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Build #116: Audio Path diagnostics — honest detection of what this device
 * and OS actually permit. No pretending, no bypass claims: if a path is
 * closed, we say so.
 */
object AudioPath {

    fun androidLine(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    private fun prop(key: String): String? = try {
        (Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, key) as? String)
            ?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) { null }

    fun isVivo(): Boolean =
        Build.MANUFACTURER.equals("vivo", true) || Build.BRAND.equals("vivo", true) ||
            prop("ro.vivo.os.version") != null

    /** Vivo's skin name + version: Funtouch OS / OriginOS / Fantasy OS. */
    fun osLine(): String {
        val display = Build.DISPLAY ?: ""
        val skin = when {
            display.contains("Fantasy", true) -> "Fantasy OS"
            display.contains("Origin", true) -> "OriginOS"
            display.contains("Funtouch", true) -> "Funtouch OS"
            else -> "Vivo OS"
        }
        val ver = prop("ro.vivo.os.version") ?: prop("ro.vivo.os.build.display") ?: ""
        return if (isVivo()) ("${Build.MANUFACTURER} ${Build.MODEL} · $skin $ver").trim()
        else "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.VERSION.CODENAME ?: "AOSP"}"
    }

    fun outputDevice(ctx: Context): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.let { return "Bluetooth (A2DP)" }
            devs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }?.let { return "Wired headphones" }
            devs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }?.let { return "USB DAC / headset" }
            devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { return "Phone speaker" }
            "unknown"
        } catch (t: Throwable) { "n/a" }
    }

    fun rateLine(ctx: Context): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val rate = try { am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) } catch (t: Throwable) { null } ?: "48000"
            "$rate Hz · stereo · 16-bit"
        } catch (t: Throwable) { "n/a" }
    }

    /** AudioPlaybackCapture (the public no-root capture API) — Android 10+. */
    fun captureSupported(): Boolean = Build.VERSION.SDK_INT >= 29
}
