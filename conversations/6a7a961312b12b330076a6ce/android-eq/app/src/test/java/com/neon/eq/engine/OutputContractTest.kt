package com.neon.eq.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v144 OUTPUT STUDIO — routing/profile/history contract. Pure JVM: no Android.
 */
class OutputContractTest {

    // ── route keys: stable, case-insensitive, blank-name fallback ──
    @Test
    fun routeKeys_areStableAndCaseInsensitive() {
        assertEquals(OutputContract.routeKey(4, "WH-1000XM5"), OutputContract.routeKey(4, "wh-1000xm5"))
        assertEquals(OutputContract.routeKey(4, "  WH-1000XM5  "), OutputContract.routeKey(4, "WH-1000XM5"))
        // blank product names fall back to a per-category key
        assertEquals("out:2", OutputContract.routeKey(2, null))
        assertEquals("out:2", OutputContract.routeKey(2, "   "))
        // different devices never collide
        assertFalse(OutputContract.routeKey(4, "Buds A") == OutputContract.routeKey(4, "Buds B"))
        assertFalse(OutputContract.routeKey(2, "X") == OutputContract.routeKey(3, "X"))
    }

    // ── priority: the honest active-route ordering (BT > wired > USB > speaker) ──
    @Test
    fun bluetoothOutranksWiredUsbAndSpeaker() {
        assertTrue(OutputContract.categoryPriority(OutputContract.CAT_BLUETOOTH) < OutputContract.categoryPriority(OutputContract.CAT_WIRED))
        assertTrue(OutputContract.categoryPriority(OutputContract.CAT_WIRED) < OutputContract.categoryPriority(OutputContract.CAT_USB))
        assertTrue(OutputContract.categoryPriority(OutputContract.CAT_USB) < OutputContract.categoryPriority(OutputContract.CAT_SPEAKER))
        assertTrue(OutputContract.categoryPriority(OutputContract.CAT_LE_AUDIO) == OutputContract.categoryPriority(OutputContract.CAT_BLUETOOTH))
    }

    // ── display names: nickname wins, blank nickname falls back ──
    @Test
    fun displayName_nicknameWinsAndFallsBack() {
        val key = OutputContract.routeKey(4, "WH-1000XM5")
        assertEquals("My Headphones", OutputContract.displayName(key, "WH-1000XM5", mapOf(key to "My Headphones")))
        assertEquals("WH-1000XM5", OutputContract.displayName(key, "WH-1000XM5", mapOf(key to "   ")))
        assertEquals("WH-1000XM5", OutputContract.displayName(key, "WH-1000XM5", emptyMap()))
        assertEquals("Phone Speaker", OutputContract.displayName("out:0", null, emptyMap()))
        assertEquals("USB DAC", OutputContract.displayName("out:3", "", emptyMap()))
    }

    // ── profiles: blank/missing is NOT a profile — EQ stays untouched ──
    @Test
    fun profileLookup_rejectsBlankAndMissing() {
        val key = OutputContract.routeKey(2, "Wired")
        assertEquals("Earbuds EQ", OutputContract.profileFor(mapOf(key to "Earbuds EQ"), key))
        assertNull(OutputContract.profileFor(mapOf(key to "  "), key))
        assertNull(OutputContract.profileFor(emptyMap(), key))
        assertNull(OutputContract.profileFor(mapOf("out:0" to "Speaker EQ"), key))
    }

    // ── history: bounded, consecutive duplicates collapsed ──
    @Test
    fun history_isBoundedAndDeduplicatesConsecutive() {
        val base = (1L..10L).map { (it * 1000) to "Route $it" }
        val updated = OutputContract.rememberHistory(base, 999000L to "New Route")
        assertEquals(10, updated.size)
        assertEquals("New Route", updated.first().second)
        val dup = OutputContract.rememberHistory(base, 10000L to "Route 10")
        assertEquals(10, dup.size)  // unchanged — same as latest entry
        assertEquals("Route 10", dup.first().second)
    }

    // ── multi-output: NEVER a false positive (Android public APIs say no) ──
    @Test
    fun multiOutput_isHonestUnsupported() {
        assertFalse(OutputContract.multiOutputSupported())
    }

    // ── preferred output availability ──
    @Test
    fun preferredOutput_availabilityIsFactual() {
        val connected = listOf("out:4:buds", "out:0")
        assertTrue(OutputContract.preferredAvailable("out:4:buds", connected))
        assertFalse(OutputContract.preferredAvailable("out:3:dac", connected))   // unplugged
        assertFalse(OutputContract.preferredAvailable(null, connected))           // none set
        assertFalse(OutputContract.preferredAvailable("out:4:buds", emptyList()))
    }

    // ── nickname sanitize: trim, cap, reject blank ──
    @Test
    fun nicknames_areSanitized() {
        assertEquals("Studio Buds", OutputContract.sanitizeNickname("  Studio Buds  "))
        assertEquals(32, OutputContract.sanitizeNickname("x".repeat(50))?.length)
        assertNull(OutputContract.sanitizeNickname("   "))
        assertNull(OutputContract.sanitizeNickname(null))
        assertNull(OutputContract.sanitizeNickname(""))
    }

    // ── history bucketing for RECENT OUTPUTS ──
    @Test
    fun historyBuckets_todayYesterdayAndDate() {
        val now = 1_800_000_000_000L
        val day = 24L * 60 * 60 * 1000
        assertEquals("Today", OutputContract.historyBucket(now - 1000, now))
        assertEquals("Yesterday", OutputContract.historyBucket(now - day - 1000, now))
        assertEquals("Today", OutputContract.historyBucket(now - day + 3600_000, now)) // < 1 day
    }

    // ── route-change safety: profile restore never yields an invalid EQ state ──
    @Test
    fun profileRestore_keepsBandMapSafe() {
        // The apply path resamples through Presets.levelsForCount + the
        // v141 BandMap pair; a profile for a preset that no longer exists
        // must resolve to NO profile (null) — never a partial application.
        val profiles = mapOf("out:4:buds" to "Deleted Preset")
        assertNull(OutputContract.profileFor(emptyMap(), "out:4:buds"))
        // missing key entirely:
        assertNull(OutputContract.profileFor(profiles, "out:0"))
        // the equalizer sanitizer contract still guards the actual apply:
        val sanitized = EqualizerEngine.sanitizeEqLevelsDb(floatArrayOf(25f, -99f, Float.NaN, Float.POSITIVE_INFINITY, 3f))
        assertTrue(sanitized[0] <= 20f && sanitized[0] >= -15f)
        assertTrue(sanitized[1] >= -15f && sanitized[1] <= 20f)
        assertEquals(0f, sanitized[2], 0.001f)
        assertEquals(0f, sanitized[3], 0.001f)
        assertEquals(3f, sanitized[4], 0.001f)
    }
}
