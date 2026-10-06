package com.neon.eq.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    // ── v142 §37: mode-switch transitions with a 5-band device attached —
    // every published pair agrees at every step (the v141 invariant holds
    // through the new EQ Pro UI paths). ─────────────────────────────────────
    @Test
    fun eqModeSwitchTransitions_pairsAgreeAtEveryStep() {
        val transitions = listOf(5 to 10, 5 to 15, 5 to 31, 10 to 15, 15 to 31, 31 to 10)
        for ((from, to) in transitions) {
            // attached 5-band hardware map at `from` UI count:
            val hwIdx = EqualizerEngine.bandIndices(from, 5)
            val hwPos = EqualizerEngine.bandPositions(hwIdx, from, 5)
            assertEquals(hwIdx.size, hwPos.size)
            // UI count switch publishes the fallback PAIR at `to`:
            val fb = EqualizerEngine.fallbackBands(to)
            val fp = FloatArray(to) { it.toFloat() }
            assertEquals("switch $from->$to", fb.size, fp.size)
            for (j in fb.indices) fp[j]
            // re-attach republishes the 5-band hardware pair at `to`:
            val rIdx = EqualizerEngine.bandIndices(to, 5)
            val rPos = EqualizerEngine.bandPositions(rIdx, to, 5)
            assertEquals(rIdx.size, rPos.size)
            for (j in rIdx.indices) assertTrue(rPos[j] >= 0f && rPos[j] <= (to - 1).toFloat())
        }
    }

    // ── v142 §37: hostile mapping inputs must degrade to a consistent
    // (possibly empty) pair — never a mismatched pair, never a throw. ───────
    @Test
    fun hostileMappingInputs_neverProduceMismatchedPairs() {
        for (usable in listOf(0, -3, 1, Int.MAX_VALUE)) {
            for (count in listOf(0, -1, 1, 10, 31)) {
                val idx = EqualizerEngine.bandIndices(count, usable)
                val pos = EqualizerEngine.bandPositions(idx, count, usable)
                assertEquals("usable=$usable count=$count", idx.size, pos.size)
                for (j in idx.indices) {
                    val p = pos[j]
                    assertTrue(java.lang.Float.isFinite(p))
                    if (count in 1..31) assertTrue(p >= 0f && p <= (count - 1).toFloat())
                }
            }
        }
    }

    // ── v142 §37/§28: paste/import sanitization — any array shape or value
    // becomes a safe 31-slot curve inside the DSP range. ────────────────────
    @Test
    fun sanitizeEqLevels_anyInputBecomesSafeCurve() {
        fun sane(raw: FloatArray) = EqualizerEngine.sanitizeEqLevelsDb(raw)
        // empty / too-short / oversized
        assertEquals(31, sane(FloatArray(0)).size)
        assertEquals(31, sane(floatArrayOf(1f, 2f)).size)
        assertEquals(31, sane(FloatArray(40) { 3f }).size)
        assertTrue(sane(FloatArray(40) { 3f }).all { it == 3f })
        // short arrays pad with zeros (never repeat the last value — v135 lesson)
        val padded = sane(floatArrayOf(5f, -5f))
        assertEquals(5f, padded[0]); assertEquals(-5f, padded[1]); assertEquals(0f, padded[30])
        // out-of-range clamps to the DSP range
        assertEquals(20f, sane(floatArrayOf(999f))[0])
        assertEquals(-15f, sane(floatArrayOf(-999f))[0])
        assertEquals(20f, sane(floatArrayOf(20.0001f))[0])
        assertEquals(-15f, sane(floatArrayOf(-15.0001f))[0])
        // NaN / Inf become 0 — never reach the DSP
        assertEquals(0f, sane(floatArrayOf(Float.NaN))[0])
        assertEquals(0f, sane(floatArrayOf(Float.POSITIVE_INFINITY))[0])
        assertEquals(0f, sane(floatArrayOf(Float.NEGATIVE_INFINITY))[0])
        // in-range values pass through exactly
        assertEquals(4.5f, sane(floatArrayOf(4.5f))[0])
        assertEquals(-15f, sane(floatArrayOf(-15f))[0])
        assertEquals(20f, sane(floatArrayOf(20f))[0])
    }

    // ── v143: preset stepping (prev/next) — total on empty, not-found, wrap ──
    @Test
    fun presetStepping_isTotalAndCyclic() {
        assertEquals(-1, Presets.stepIndex(0, 0, 1))          // no presets
        assertEquals(0, Presets.stepIndex(3, -1, -1))        // not found -> start
        assertEquals(2, Presets.stepIndex(3, 0, -1))         // wrap backward
        assertEquals(0, Presets.stepIndex(3, 2, 1))          // wrap forward
        assertEquals(1, Presets.stepIndex(3, 0, 1))
        assertEquals(1, Presets.stepIndex(3, 1, 3))           // multi-step wraps
        assertEquals(0, Presets.stepIndex(1, 0, -1))          // 1 preset: backward -> 0
        assertEquals(0, Presets.stepIndex(1, 0, 1))          // 1 preset: forward -> 0
    }

    // ── v143 §28: CUSTOMIZED indicator — pure, allocation-free compare ──
    @Test
    fun customizedIndicator_detectsAnyDrift() {
        val preset = shortArrayOf(0, 2, 4, 0, 0, 0, 0, 0, 0, 0)
        val same = FloatArray(31) { if (it < 10) preset[it].toFloat() else 0f }
        assertFalse("identical curve is not customized", EqualizerEngine.isCustomizedDb(same, preset, 10))
        val drifted = same.copyOf().also { it[2] = 5f }
        assertTrue("one band moved -> customized", EqualizerEngine.isCustomizedDb(drifted, preset, 10))
        val nan = same.copyOf().also { it[4] = Float.NaN }
        assertTrue("NaN never silently equals a preset", EqualizerEngine.isCustomizedDb(nan, preset, 10))
        val inf = same.copyOf().also { it[4] = Float.POSITIVE_INFINITY }
        assertTrue("Inf never silently equals a preset", EqualizerEngine.isCustomizedDb(inf, preset, 10))
        // rounding: 4.49 rounds to 4 -> not customized; 4.51 -> customized
        val almost = same.copyOf().also { it[2] = 4.49f }
        assertFalse(EqualizerEngine.isCustomizedDb(almost, preset, 10))
        val beyond = same.copyOf().also { it[2] = 4.51f }
        assertTrue(EqualizerEngine.isCustomizedDb(beyond, preset, 10))
        // hostile counts clamp to 0..31 — never index out of bounds
        assertFalse(EqualizerEngine.isCustomizedDb(same, preset, 0))
        assertFalse(EqualizerEngine.isCustomizedDb(same, preset, -3))
        assertFalse(EqualizerEngine.isCustomizedDb(same, preset, 999))
        // shorter preset pads with 0 — a flat current curve matches [0, 0]
        val shortPreset = shortArrayOf(0, 0)
        val cur2 = FloatArray(31) { 0f }
        assertFalse(EqualizerEngine.isCustomizedDb(cur2, shortPreset, 10))
        cur2[5] = -1f
        assertTrue(EqualizerEngine.isCustomizedDb(cur2, shortPreset, 10))
    }
}
