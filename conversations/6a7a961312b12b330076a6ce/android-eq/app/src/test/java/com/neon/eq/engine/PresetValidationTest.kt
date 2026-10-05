package com.neon.eq.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build #136 hardening — preset validation tests (spec 26).
 *
 * These are real JVM unit tests: importFromJson runs off-device, so every
 * malformed-preset rule can be verified without hardware.
 */
class PresetValidationTest {

    private fun validPresetJson(name: String = "Bass", levels: String = "[5,3,0,0,0,0,0,0,0,0]",
                                bass: String = "100", virt: String = "0", loud: String = "50"): String =
        """{"app":"SonicCore","version":1,"presets":[
            {"name":"$name","levels":$levels,"bass":$bass,"virt":$virt,"loud":$loud}]}"""

    @Test
    fun `valid preset imports with exact values`() {
        val out = Presets.importFromJson(validPresetJson())
        assertEquals(1, out.size)
        assertEquals("Bass", out[0].name)
        assertEquals(5, out[0].levels[0])
        assertEquals(3, out[0].levels[1])
        assertEquals(0, out[0].levels[9])
        assertEquals(100, out[0].bassBoost)
        assertEquals(50, out[0].loudness)
    }

    @Test
    fun `out of range band levels are clamped to the UI contract`() {
        val json = validPresetJson(name = "Wild", levels = "[999,-999,12,20,21,32767,-32768,0,0,0]")
        val out = Presets.importFromJson(json)
        assertEquals(1, out.size)
        assertEquals(20, out[0].levels[0])   // +999 dB -> +20
        assertEquals(-15, out[0].levels[1])  // -999 dB -> -15
        assertEquals(20, out[0].levels[3])
        assertEquals(20, out[0].levels[4])   // 21 -> 20
        assertEquals(20, out[0].levels[5])
        assertEquals(-15, out[0].levels[6])
        assertEquals(0, out[0].levels[9])
    }

    @Test
    fun `effect strengths are clamped`() {
        val json = validPresetJson(bass = "5000", virt = "-40", loud = "99999")
        val out = Presets.importFromJson(json)
        assertEquals(1000, out[0].bassBoost)
        assertEquals(0, out[0].virtualizer)
        assertEquals(300, out[0].loudness)
    }

    @Test
    fun `malformed preset is skipped but healthy presets import`() {
        val json = """{"presets":[
            {"name":"good","levels":[1,2,3,4,5,6,7,8,9,10]},
            {"noname":"broken","levels":[1,2,3]},
            {"name":"","levels":[0,0,0,0,0,0,0,0,0,0]},
            "not-an-object"]}"""
        val out = Presets.importFromJson(json)
        assertEquals(1, out.size)
        assertEquals("good", out[0].name)
    }

    @Test
    fun `garbage document returns empty list`() {
        assertEquals(emptyList<Presets.CustomPreset>(), Presets.importFromJson("not json at all"))
        assertEquals(emptyList<Presets.CustomPreset>(), Presets.importFromJson("{}"))
        assertEquals(emptyList<Presets.CustomPreset>(), Presets.importFromJson("""{"wrong":[]}"""))
    }

    @Test
    fun `short levels arrays pad with zero and long ones are truncated to 31 slots`() {
        val short = Presets.importFromJson(validPresetJson(name = "short", levels = "[7,8]"))
        assertEquals(31, short[0].levels.size)
        assertEquals(7, short[0].levels[0])
        assertEquals(8, short[0].levels[1])
        assertEquals(0, short[0].levels[30])

        val longLevels = (1..60).joinToString(",", "[", "]")
        val long = Presets.importFromJson(validPresetJson(name = "long", levels = longLevels))
        assertEquals(31, long[0].levels.size)
        assertEquals(1, long[0].levels[0])
        assertEquals(31, long[0].levels[30])
    }

    @Test
    fun `builtin resample never assigns sub bass gain above 16 kHz`() {
        // Build #136 regression test: resampling to 15/31 bands used to read
        // indexOfFirst == -1 (f > 16 kHz) as "sub-bass" and copy base[0].
        val curve = shortArrayOf(12, 0, 0, 0, 0, 0, 0, 0, 0, -6)
        for (count in intArrayOf(15, 31)) {
            val out = Presets.resampleBase(curve.map { it.toInt() }.toIntArray(), count)
            // The top band (~20 kHz) must hold the TREBLE value (-6), not +12.
            assertEquals(-6, out[count - 1].toInt())
            // and nothing above 16 kHz may carry the sub-bass boost
            for (i in 0 until count) {
                val f = 20.0 * Math.pow(1000.0, i.toDouble() / (count - 1).coerceAtLeast(1).toDouble())
                if (f > 16000.0) assertTrue("band $i ($f Hz) held sub-bass gain", out[i].toInt() != 12)
            }
        }
    }
}
