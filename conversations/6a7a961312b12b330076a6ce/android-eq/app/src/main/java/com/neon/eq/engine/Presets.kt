package com.neon.eq.engine

object Presets {

    data class Preset(val name: String, val levels: ShortArray)

    // Extended preset that includes effect settings (bass, virtualizer, loudness)
    // alongside band levels — used for user-saved custom presets.
    data class CustomPreset(
        val name: String,
        val levels: ShortArray,
        val bassBoost: Int = 0,
        val virtualizer: Int = 0,
        val loudness: Int = 0
    )

    // Build #93: EQ is fixed at 10 bands — every preset is now a dedicated
    // 10-slot curve (was: 31-slot index math downsampled to the band count).
    // Values use the raised ceiling: boosts go up to +15 dB where the old
    // curves topped out around +8. Slot freqs ~ 31/62/125/250/500/1k/2k/4k/8k/16k.
    val presets: List<Preset> = listOf(
        Preset("Flat", shortArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        // ── Bass family ──
        Preset("Bass Boost", shortArrayOf(15, 13, 11, 7, 3, 0, 0, 0, 0, 0)),
        Preset("Bass Extreme", shortArrayOf(15, 15, 13, 9, 4, 0, 0, 0, 0, 0)),
        Preset("Sub Bass", shortArrayOf(15, 12, 6, 0, -2, -2, 0, 0, 0, 0)),
        // ── Treble family ──
        Preset("Treble Boost", shortArrayOf(0, 0, 0, 0, 0, 0, 3, 7, 11, 15)),
        Preset("Air", shortArrayOf(0, 0, 0, 0, 0, 0, 0, 2, 6, 12)),
        // ── Genre presets ──
        Preset("Rock", shortArrayOf(10, 8, 5, 2, 0, 0, 3, 5, 7, 8)),
        Preset("Pop", shortArrayOf(-2, -1, 2, 4, 5, 4, 1, -1, -2, -3)),
        Preset("Jazz", shortArrayOf(6, 5, 3, 1, 0, 0, 2, 3, 5, 6)),
        Preset("EDM", shortArrayOf(13, 11, 6, 0, -2, -2, 0, 3, 5, 7)),
        Preset("Classical", shortArrayOf(5, 4, 2, 0, 0, 0, -1, 1, 3, 4)),
        Preset("Dance", shortArrayOf(13, 11, 5, 0, 0, 3, 5, 7, 6, 5)),
        Preset("Vocal", shortArrayOf(-3, -2, 0, 2, 4, 5, 4, 2, 0, -1)),
        Preset("Loudness", shortArrayOf(15, 12, 4, 0, -1, -1, 0, 2, 5, 8)),
        Preset("Hip Hop", shortArrayOf(14, 12, 5, 0, -1, -1, 0, 2, 3, 4)),
        Preset("Gaming", shortArrayOf(8, 6, 0, -2, -2, 0, 4, 6, 8, 9)),
        // ── New presets ──
        Preset("Acoustic", shortArrayOf(5, 4, 2, 1, 1, 2, 2, 4, 4, 3)),
        Preset("R&B", shortArrayOf(12, 10, 4, 0, 1, 2, 2, 3, 4, 4)),
        Preset("Metal", shortArrayOf(11, 8, 2, -2, -2, 0, 1, 4, 6, 7)),
        Preset("Electronic", shortArrayOf(14, 11, 4, -2, -3, -1, 0, 4, 7, 9)),
        Preset("Latin", shortArrayOf(9, 7, 4, 1, 1, 2, 3, 4, 5, 5)),
        Preset("Podcast", shortArrayOf(-3, -2, 0, 3, 5, 6, 4, 2, 0, -1)),
        Preset("Movie", shortArrayOf(14, 11, 3, 0, -1, 0, 3, 5, 8, 10)),
        Preset("Night Mode", shortArrayOf(-6, -4, -1, 0, 1, 0, -1, -3, -5, -7)),
        Preset("Concert", shortArrayOf(8, 6, 0, -1, 0, 1, 3, 4, 6, 8)),
        Preset("Phone", shortArrayOf(-4, -3, -1, 1, 4, 6, 6, 3, 0, -2))
    )

    // Build #93: with the EQ fixed at 10 bands this normally returns the
    // preset as-is. The resample path stays for user-saved custom presets
    // created under the old variable band counts (5-31 slots) so they map
    // cleanly onto the 10-band curve instead of truncating.
    fun levelsForCount(preset: Preset, count: Int): ShortArray {
        val src = preset.levels
        if (src.isEmpty()) return ShortArray(count)
        if (src.size == count) return src.copyOf()
        val result = ShortArray(count)
        val step = src.size.toFloat() / count
        for (i in 0 until count) {
            val srcIdx = (i * step).toInt().coerceAtMost(src.size - 1)
            result[i] = src[srcIdx]
        }
        return result
    }

    // Overload for CustomPreset (has the same levels array)
    fun levelsForCount(preset: CustomPreset, count: Int): ShortArray {
        if (count >= 31) return preset.levels
        val result = ShortArray(count)
        val step = 31f / count
        for (i in 0 until count) {
            val srcIdx = (i * step).toInt().coerceAtMost(30)
            result[i] = preset.levels[srcIdx]
        }
        return result
    }

    // ── Export/import helpers ──

    // Serialize ALL custom presets to a JSON string — shareable via any intent.
    fun exportToJson(customPresets: List<CustomPreset>): String {
        val arr = org.json.JSONArray()
        for (p in customPresets) {
            val obj = org.json.JSONObject()
            obj.put("name", p.name)
            val levels = org.json.JSONArray()
            for (lvl in p.levels) levels.put(lvl.toInt())
            obj.put("levels", levels)
            obj.put("bass", p.bassBoost)
            obj.put("virt", p.virtualizer)
            obj.put("loud", p.loudness)
            arr.put(obj)
        }
        val meta = org.json.JSONObject()
        meta.put("app", "NeonEQ")
        meta.put("version", 1)
        meta.put("presets", arr)
        return meta.toString(2)
    }

    // Parse an exported JSON string back into CustomPreset objects.
    // Returns empty list on any parse failure — caller handles conflict resolution.
    fun importFromJson(json: String): List<CustomPreset> {
        return try {
            val root = org.json.JSONObject(json)
            val arr = root.optJSONArray("presets") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val levelsArr = obj.getJSONArray("levels")
                val levels = ShortArray(31) { idx -> levelsArr.optInt(idx, 0).toShort() }
                CustomPreset(
                    name = name,
                    levels = levels,
                    bassBoost = obj.optInt("bass", 0),
                    virtualizer = obj.optInt("virt", 0),
                    loudness = obj.optInt("loud", 0)
                )
            }
        } catch (_: Throwable) { emptyList() }
    }
}
