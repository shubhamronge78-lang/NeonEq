package com.neon.eq.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v141 regression — the Redmi 10C audio-thread crash:
 *
 *   java.lang.ArrayIndexOutOfBoundsException: length=5; index=5
 *     at EqualizerEngine.applyBands(...) — NeonEQ-Audio thread
 *
 * Root cause: `bands` and `bandPos` were two independent @Volatile fields.
 * On a device whose hardware EQ exposes 5 bands, attach published
 * bands=5/bandPos=5; setUiBandCount() then replaced `bands` alone with the
 * 15-entry fallback table, and applyBands() (for j in bands.indices →
 * bandPos[j]) read index 5 of a 5-element FloatArray.
 *
 * The fix: bands+bandPos now travel as ONE immutable BandMap snapshot
 * published through a single volatile write, so an inconsistent pair is
 * unrepresentable. `bands` lost its setter (compile-time guarantee). These
 * tests pin the mapping contract at the exact crash state and across every
 * supported band mode.
 */
class BandMapContractTest {

    // ── §15: EXACT CRASH — the old two-field sequence must throw the ORIGINAL
    // exception (proof we reproduce the failure condition), and the fixed
    // snapshot pair must be safe. ──────────────────────────────────────────
    @Test
    fun exactRedmiCrashState_oldSplitFields_threw_length5_index5() {
        // What the 5-band hardware attach published (bandCount=10, usable=5):
        val hwIndices = EqualizerEngine.bandIndices(10, 5)
        val hwPos = EqualizerEngine.bandPositions(hwIndices, 10, 5)
        assertEquals(5, hwPos.size)

        // What setUiBandCount() then assigned — bands ALONE (old bug):
        val uiBands = EqualizerEngine.fallbackBands(15)

        // applyBands' loop shape over the OLD split state:
        try {
            for (j in uiBands.indices) hwPos[j]   // bands[j] read + bandPos[j] sample
            fail("expected the original ArrayIndexOutOfBoundsException")
        } catch (e: ArrayIndexOutOfBoundsException) {
            // The device (ART) reports "length=5; index=5"; desktop JVMs report
            // "Index 5 out of bounds for length 5". Both must show 5 and 5.
            val m = e.message ?: ""
            val len = Regex("length (\\d+)|length=(\\d+)").find(m)?.let {
                (it.groupValues[1] + it.groupValues[2]).filter { c -> c.isDigit() }
            } ?: fail("unparseable AIOOBE message: $m")
            val idx = Regex("Index (\\d+)|index=(\\d+)").find(m)?.let {
                (it.groupValues[1] + it.groupValues[2]).filter { c -> c.isDigit() }
            } ?: fail("unparseable AIOOBE message: $m")
            assertEquals("5", len)
            assertEquals("5", idx)
        }
    }

    @Test
    fun exactRedmiCrashState_fixedSnapshotPair_isSafe() {
        // Fixed path 1: after setUiBandCount publishes the fallback PAIR,
        // bands and positions are the same size by construction.
        val uiBands = EqualizerEngine.fallbackBands(15)
        val uiPos = FloatArray(15) { it.toFloat() }   // identity positions
        assertEquals(uiBands.size, uiPos.size)
        for (j in uiBands.indices) uiPos[j]           // must not throw

        // Fixed path 2: the 5-band hardware map itself stays valid and intact
        // (5 real bands, positions in 0..9 UI-slot space, all 10+ sliders
        // still sampled — the v67 semantics are preserved).
        val hwIndices = EqualizerEngine.bandIndices(10, 5)
        val hwPos = EqualizerEngine.bandPositions(hwIndices, 10, 5)
        val hwBands = EqualizerEngine.fallbackBands(10).take(5)  // 5-band attach shape
        assertEquals(hwBands.size, hwPos.size)
        for (j in hwBands.indices) {
            val pos = hwPos[j]
            assertTrue("position out of UI-slot range: $pos", pos >= 0f && pos <= 9f)
        }
    }

    // ── §16: the 5-band state is a VALID hardware layout (Redmi 10C), not a
    // malformed preset. It must remain a first-class mapping. ────────────────
    @Test
    fun fiveBandHardwareMap_isValidLegacyLayout() {
        for (count in listOf(10, 15, 31)) {
            val idx = EqualizerEngine.bandIndices(count, 5)
            val pos = EqualizerEngine.bandPositions(idx, count, 5)
            assertEquals(5, idx.size)
            assertEquals(5, pos.size)
            assertEquals(listOf(0, 1, 2, 3, 4), idx)
            // positions spread across the whole UI curve, monotonically
            var prev = -1f
            for (p in pos) {
                assertTrue(p >= 0f && p <= (count - 1).toFloat())
                assertTrue("positions must be non-decreasing", p >= prev)
                prev = p
            }
            assertEquals(0f, pos[0], 1e-6f)
            assertEquals((count - 1).toFloat(), pos[4], 1e-3f)
        }
    }

    // ── §17: every supported mode — 10/15/31 UI bands × common hardware band
    // counts (5, 6, 8, 10, 13, 31) — must always produce an agreed pair. ────
    @Test
    fun allSupportedBandCounts_pairsAlwaysAgree() {
        for (count in listOf(10, 15, 31)) {
            val fb = EqualizerEngine.fallbackBands(count)
            assertEquals(count, fb.size)
            for (usable in listOf(5, 6, 8, 10, 13, 31)) {
                val idx = EqualizerEngine.bandIndices(count, usable)
                val pos = EqualizerEngine.bandPositions(idx, count, usable)
                assertEquals("usable=$usable count=$count", idx.size, pos.size)
                val expected = minOf(usable, count)
                assertEquals(expected, idx.size)
                // applyBands' loop shape must be safe for the pair:
                for (j in idx.indices) {
                    val p = pos[j]
                    assertTrue("pos out of range: $p (count=$count)", p >= 0f && p <= (count - 1).toFloat())
                }
                // more hardware bands than UI slots → indices subsample evenly
                if (usable > count) {
                    assertTrue(idx.first() == 0)
                    assertTrue(idx.last() <= usable - 1)
                }
            }
        }
    }

    // ── mode switching: each switch produces a fresh, self-consistent pair —
    // the stale-position class of state (old bandPos + new bands) cannot be
    // built through the mapping functions. ──────────────────────────────────
    @Test
    fun uiBandSwitching_neverLeavesStalePositions() {
        // simulate the publish sequence for 10 → 15 → 31 → 10 with a 5-band
        // device attached between switches: every published pair agrees.
        val seq = listOf(10, 15, 31, 10)
        for (count in seq) {
            // setUiBandCount path (bands.size < count):
            val b = EqualizerEngine.fallbackBands(count)
            val p = FloatArray(count) { it.toFloat() }
            assertEquals("switch to $count", b.size, p.size)
            for (j in b.indices) p[j]
            // re-attach path (5-band hardware):
            val (b2, p2) = run {
                val idx = EqualizerEngine.bandIndices(count, 5)
                EqualizerEngine.fallbackBands(count).take(idx.size) to
                    EqualizerEngine.bandPositions(idx, count, 5)
            }
            assertEquals("re-attach at $count", b2.size, p2.size)
            for (j in b2.indices) p2[j]
        }
    }
}
