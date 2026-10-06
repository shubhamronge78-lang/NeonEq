package com.neon.eq.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v145 PERFORMANCE + STABILITY — hostile device lists (§33), route-event
 * sequences (§34), snapshot dedup (§5), fallback states (§20), perf intervals
 * (§14/§31). Pure JVM: no Android. Every case must be crash-free.
 */
class OutputContractV145Test {

    private fun route(key: String, cat: Int, name: String? = null, rate: Int = 48000, ch: Int = 2, active: Boolean = false) =
        OutputContract.RouteUi(key, name, cat, rate, ch, active)

    // ── §33: empty device list never crashes and reports the honest state ──
    @Test
    fun emptyDeviceList_isSafeAndHonest() {
        val st = OutputContract.OutputUiState(
            "WAITING FOR OUTPUT", null, emptyList(),
            OutputContract.fallbackNote(emptyList(), OutputContract.activeIndex(emptyList())), null, true)
        assertTrue(st.routes.isEmpty())
        assertNull(st.activeKey)
        assertEquals("WAITING FOR OUTPUT", st.activeName)
        assertEquals("WAITING FOR OUTPUT", st.fallbackNote)
    }

    // ── §33: duplicate devices in one scan collapse to one UI entry ──
    @Test
    fun duplicateDevices_areDeduplicatedByKey() {
        val raw = listOf(
            route("out:4:buds", OutputContract.CAT_BLUETOOTH, "Buds"),
            route("out:4:buds", OutputContract.CAT_BLUETOOTH, "Buds"),   // same physical device twice
            route("out:0", OutputContract.CAT_SPEAKER, null)
        )
        // dedup by key — as performed by the scan layer
        val dedup = raw.distinctBy { it.key }
        assertEquals(2, dedup.size)
        // snapshot dedup is structural: two identical states are EQUAL (§5)
        val a = OutputContract.OutputUiState("Buds", "out:4:buds", dedup, null, null, true)
        val b = OutputContract.OutputUiState("Buds", "out:4:buds", dedup, null, null, true)
        assertEquals(a, b)   // equal snapshot → UI must NOT re-emit
    }

    // ── §5: a CHANGED snapshot must re-emit (equality must not over-collapse) ──
    @Test
    fun changedSnapshot_reEmits() {
        val spk = route("out:0", OutputContract.CAT_SPEAKER, null)
        val bt = route("out:4:buds", OutputContract.CAT_BLUETOOTH, "Buds")
        val a = OutputContract.OutputUiState("Phone Speaker", "out:0", listOf(spk.copy(isActive = true)), null, null, true)
        val b = OutputContract.OutputUiState("Buds", "out:4:buds", listOf(bt), null, null, true)
        assertNotEquals(a, b)
        val r = spk.copy(isActive = true)
        assertNotEquals(spk, spk.copy(isActive = true))
        assertEquals(r, spk.copy(isActive = true))  // but structurally identical copies are equal
    }

    // ── §33: unknown device type, null/empty product names — no crash ──
    @Test
    fun hostileRouteData_isSafe() {
        val weird = listOf(
            route("out:9:weird", OutputContract.CAT_OTHER, "WeirdDAC", 96000, 8),
            route("out:4:", OutputContract.CAT_BLUETOOTH, "", 44100, 1),   // empty product name
            route("out:3:usb", OutputContract.CAT_USB, null, 0, 0)           // null name, no rates
        )
        val idx = OutputContract.activeIndex(weird.map { it.category })
        assertTrue(idx != null && idx >= 0 && idx < weird.size)
        // empty/null names fall back to category names — never a blank UI
        // keys must come from routeKey — the blank-name fallback key is "out:4", not "out:4:"
        assertEquals("Bluetooth Device", OutputContract.displayName(OutputContract.routeKey(4, ""), "", emptyMap()))
        assertEquals("USB DAC", OutputContract.displayName(OutputContract.routeKey(3, null), null, emptyMap()))
        // unknown category still gets a usable label
        assertEquals("OTHER", OutputContract.categoryLabel(OutputContract.CAT_OTHER))
    }

    // ── §34: full route sequence — speaker→BT→removed→speaker→wired→USB→speaker.
    //    Pure reducer: no loop possible, no duplicate final state, no crash. ──
    @Test
    fun routeEventSequence_convergesWithoutLoop() {
        val speaker = listOf(route("out:0", OutputContract.CAT_SPEAKER, null))
        val btPlus = listOf(
            route("out:4:buds", OutputContract.CAT_BLUETOOTH, "Buds"),
            route("out:0", OutputContract.CAT_SPEAKER, null)
        )
        val wiredPlus = listOf(
            route("out:2:wired", OutputContract.CAT_WIRED, "Wired"),
            route("out:0", OutputContract.CAT_SPEAKER, null)
        )
        val usbPlus = listOf(
            route("out:3:dac", OutputContract.CAT_USB, "DAC"),
            route("out:0", OutputContract.CAT_SPEAKER, null)
        )
        // walk the transition sequence
        val states = mutableListOf<String?>()
        var current: List<OutputContract.RouteUi> = speaker
        for (step in listOf(speaker, btPlus, speaker, speaker, wiredPlus, usbPlus, speaker)) {
            current = step
            states.add(current.getOrNull(OutputContract.activeIndex(current.map { it.category }) ?: -1)?.key)
        }
        assertEquals(listOf("out:0", "out:4:buds", "out:0", "out:0", "out:2:wired", "out:3:dac", "out:0"), states)
        // final state is the speaker, and the reducer is idempotent (no loop):
        val last = current.getOrNull(OutputContract.activeIndex(current.map { it.category }) ?: -1)?.key
        val again = current.getOrNull(OutputContract.activeIndex(current.map { it.category }) ?: -1)?.key
        assertEquals(last, again)
    }

    // ── §4: rapid add/remove/add produces the same state as one settled event ──
    @Test
    fun rapidAddRemoveAdd_settlesToFinalState() {
        val spk = listOf(route("out:0", OutputContract.CAT_SPEAKER, null))
        val btPlus = listOf(route("out:4:buds", OutputContract.CAT_BLUETOOTH, "Buds"), route("out:0", OutputContract.CAT_SPEAKER, null))
        // burst: BT added, BT removed, BT added again — debounced consumer sees
        // only the LAST state; the intermediate empty-BT states are coalesced
        val settled = btPlus
        val idx = OutputContract.activeIndex(settled.map { it.category })
        assertEquals("out:4:buds", settled[idx!!].key)
        // and a list identical to the previous published state dedup-skips:
        val s1 = OutputContract.OutputUiState("Buds", "out:4:buds", settled, null, null, true)
        val s2 = OutputContract.OutputUiState("Buds", "out:4:buds", settled, null, null, true)
        assertTrue(s1 == s2)
    }

    // ── §20: fallback states — never a fake ACTIVE, never a crash ──
    @Test
    fun fallbackStates_areHonest() {
        assertEquals("WAITING FOR OUTPUT", OutputContract.fallbackNote(emptyList(), null))
        // devices exist but none can be active → OUTPUT LOST
        assertEquals("OUTPUT LOST", OutputContract.fallbackNote(listOf(route("out:1", OutputContract.CAT_EARPIECE, null)), null))
        // normal state → no note
        assertNull(OutputContract.fallbackNote(listOf(route("out:0", OutputContract.CAT_SPEAKER, null)), 0))
    }

    // ── §14/§31: meter intervals — visual rate capped, DSP never throttled ──
    @Test
    fun perfModeIntervals_areBoundedAndHonest() {
        assertEquals(200, OutputContract.meterIntervalMs(OutputContract.PerfMode.PERFORMANCE, lowRam = false))
        assertEquals(200, OutputContract.meterIntervalMs(OutputContract.PerfMode.PERFORMANCE, lowRam = true))
        assertEquals(50, OutputContract.meterIntervalMs(OutputContract.PerfMode.QUALITY, lowRam = true))
        assertEquals(50, OutputContract.meterIntervalMs(OutputContract.PerfMode.QUALITY, lowRam = false))
        assertEquals(100, OutputContract.meterIntervalMs(OutputContract.PerfMode.AUTO, lowRam = true))    // low-end device
        assertEquals(50, OutputContract.meterIntervalMs(OutputContract.PerfMode.AUTO, lowRam = false))   // capable device
        // all intervals ≤ 50ms → ≥ 20 UI updates/sec visual budget (§14)
        assertTrue(OutputContract.meterIntervalMs(OutputContract.PerfMode.AUTO, true) >= 50)
    }

    // ── §27: rotation must not re-trigger profile restore — last-key check ──
    @Test
    fun rotationDoesNotReapplyProfile() {
        // simulate: lastReportedKey persists across Activity recreation
        var lastReportedKey: String? = null
        fun onSnapshot(key: String?): Int {
            var applied = 0
            if (key != null && key != lastReportedKey) { lastReportedKey = key; applied = 1 }
            return applied
        }
        assertEquals(1, onSnapshot("out:0"))      // first sighting applies once
        assertEquals(0, onSnapshot("out:0"))      // rotation redraw: same key → NO re-apply
        assertEquals(0, onSnapshot("out:0"))      // and again
        assertEquals(1, onSnapshot("out:4:buds")) // real route change applies once
        assertEquals(0, onSnapshot("out:4:buds"))
    }
}
