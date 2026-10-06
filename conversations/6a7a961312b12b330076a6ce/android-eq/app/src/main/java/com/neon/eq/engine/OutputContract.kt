package com.neon.eq.engine

/**
 * v144 OUTPUT STUDIO — pure contract logic. NO Android imports: JVM-testable.
 * The Android glue (OutputRoutes) delegates here; all decisions are data-in /
 * data-out so the routing/profile/history rules can be regression-tested on
 * the host JVM without a device.
 */
object OutputContract {

    // Category constants — mapped from android.media.AudioDeviceInfo types
    // by OutputRoutes. Never read Android classes here.
    const val CAT_SPEAKER = 0
    const val CAT_EARPIECE = 1
    const val CAT_WIRED = 2
    const val CAT_USB = 3
    const val CAT_BLUETOOTH = 4
    const val CAT_LE_AUDIO = 5
    const val CAT_OTHER = 6

    /** Media-route priority: what SonicCore reports as the active output when
     *  several devices are connected. Mirrors AudioPath's honest ordering. */
    fun categoryPriority(cat: Int): Int = when (cat) {
        CAT_BLUETOOTH, CAT_LE_AUDIO -> 0
        CAT_WIRED -> 1
        CAT_USB -> 2
        CAT_EARPIECE -> 3
        CAT_SPEAKER -> 4
        else -> 5
    }

    /** Stable, case-insensitive route key. Blank product names fall back to a
     *  per-category key so profiles/favorites still bind to "any wired" etc. */
    fun routeKey(category: Int, productName: String?): String {
        val n = (productName ?: "").trim()
        return if (n.isEmpty()) "out:$category" else "out:$category:" + n.lowercase()
    }

    /** Display name: user nickname wins over the device's own product name. */
    fun displayName(routeKey: String, productName: String?, nicknames: Map<String, String>): String {
        nicknames[routeKey]?.let { if (it.isNotBlank()) return it }
        val n = (productName ?: "").trim()
        return if (n.isEmpty()) fallbackName(routeKey) else n
    }

    fun fallbackName(routeKey: String): String = when (routeKey) {
        "out:$CAT_SPEAKER" -> "Phone Speaker"
        "out:$CAT_EARPIECE" -> "Earpiece"
        "out:$CAT_WIRED" -> "Wired Headphones"
        "out:$CAT_USB" -> "USB DAC"
        "out:$CAT_BLUETOOTH" -> "Bluetooth Device"
        "out:$CAT_LE_AUDIO" -> "LE Audio Device"
        else -> routeKey
    }

    fun categoryLabel(category: Int): String = when (category) {
        CAT_SPEAKER -> "THIS PHONE"
        CAT_EARPIECE -> "THIS PHONE"
        CAT_WIRED -> "WIRED"
        CAT_USB -> "USB"
        CAT_BLUETOOTH -> "BLUETOOTH"
        CAT_LE_AUDIO -> "BLUETOOTH"
        else -> "OTHER"
    }

    fun categoryDetail(category: Int): String = when (category) {
        CAT_SPEAKER -> "Built-in speaker"
        CAT_EARPIECE -> "Built-in earpiece"
        CAT_WIRED -> "Wired headphones"
        CAT_USB -> "USB audio device"
        CAT_BLUETOOTH -> "Bluetooth (A2DP)"
        CAT_LE_AUDIO -> "Bluetooth LE Audio"
        else -> "Output device"
    }

    /** Profile lookup — blank/missing names are NOT a profile. Returns null so
     *  callers keep the live EQ untouched (never an invalid partial state). */
    fun profileFor(profiles: Map<String, String>, routeKey: String): String? {
        val p = profiles[routeKey] ?: return null
        return if (p.isNotBlank()) p else null
    }

    /** Bounded history: newest first, consecutive duplicates collapsed, cap 10. */
    fun rememberHistory(history: List<Pair<Long, String>>, entry: Pair<Long, String>, cap: Int = 10): List<Pair<Long, String>> {
        if (history.firstOrNull()?.second == entry.second) return history
        return (listOf(entry) + history).take(cap)
    }

    /** Multi-output capability: Android public media APIs expose NO
     *  simultaneous multi-route media output for third-party apps. This
     *  stays false — we never fake synchronized dual AudioTrack output. */
    fun multiOutputSupported(): Boolean = false

    /** Preferred output availability check. */
    fun preferredAvailable(preferredKey: String?, connectedKeys: List<String>): Boolean =
        preferredKey != null && connectedKeys.contains(preferredKey)

    /** Nickname sanitize: trim, cap length, reject blank (keeps device name). */
    fun sanitizeNickname(raw: String?, maxLen: Int = 32): String? {
        val n = (raw ?: "").trim()
        if (n.isEmpty()) return null
        return n.take(maxLen)
    }

    /** "Today" / "Yesterday" / date bucketing for RECENT OUTPUTS. */
    fun historyBucket(entryMs: Long, nowMs: Long): String {
        val day = 24L * 60 * 60 * 1000
        val diff = nowMs - entryMs
        return when {
            diff < day -> "Today"
            diff < 2 * day -> "Yesterday"
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.ROOT).format(java.util.Date(entryMs))
        }
    }

// ══════════ v145 PERFORMANCE: pure UI snapshot model (§6) ══════════
    // One immutable lightweight state, structural equality — Compose skips
    // emission when nothing changed (§5 dedup). All primitives: no IntArray
    // (it breaks equals), no Android types.

    data class RouteUi(
        val key: String,
        val productName: String?,
        val category: Int,
        val maxRate: Int,
        val channels: Int,
        val isActive: Boolean = false
    )

    data class OutputUiState(
        val activeName: String,
        val activeKey: String?,
        val routes: List<RouteUi>,
        val fallbackNote: String?,
        val profileName: String?,
        val followSystem: Boolean
    ) {
        companion object {
            val EMPTY = OutputUiState("WAITING FOR OUTPUT", null, emptyList(), "WAITING FOR OUTPUT", null, true)
        }
    }

    /** Index of the route Android is sending media to, by honest priority.
     *  Earpiece never wins media (comms only). Empty list → null. */
    fun activeIndex(categories: List<Int>): Int? {
        if (categories.isEmpty()) return null
        var best = -1
        var bestP = Int.MAX_VALUE
        categories.forEachIndexed { i, c ->
            if (c != CAT_EARPIECE) {
                val p = categoryPriority(c)
                if (p < bestP) { bestP = p; best = i }
            }
        }
        return if (best >= 0) best else 0
    }

    /** §20 honest fallback labels — never a fake ACTIVE state. */
    fun fallbackNote(routes: List<RouteUi>, activeIdx: Int?): String? = when {
        routes.isEmpty() -> "WAITING FOR OUTPUT"
        activeIdx == null -> "OUTPUT LOST"
        else -> null
    }

    // ── §31 PERFORMANCE MODE: UI only — DSP quality never changes ──
    enum class PerfMode { AUTO, QUALITY, PERFORMANCE }

    /** Meter/visual update interval in ms. 20 Hz max visual rate (§14):
     *  the audio DSP itself stays real-time regardless of this value. */
    fun meterIntervalMs(mode: PerfMode, lowRam: Boolean): Int = when (mode) {
        PerfMode.PERFORMANCE -> 200
        PerfMode.QUALITY -> 50
        PerfMode.AUTO -> if (lowRam) 100 else 50
    }
}
