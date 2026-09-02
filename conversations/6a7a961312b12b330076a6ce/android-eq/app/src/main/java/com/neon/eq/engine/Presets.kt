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

    val presets: List<Preset> = listOf(
        Preset("Flat", ShortArray(31) { 0 }),
        // ── Bass family ──
        Preset("Bass Boost", ShortArray(31) { i ->
            when { i < 5 -> (8 - i).toShort(); i < 8 -> 3; else -> 0 }
        }),
        Preset("Bass Extreme", ShortArray(31) { i ->
            when { i < 4 -> 8; i < 7 -> 4; else -> 0 }
        }),
        Preset("Sub Bass", ShortArray(31) { i ->
            when { i < 3 -> 7; i < 6 -> 3; i < 10 -> -2; else -> 0 }
        }),
        // ── Treble family ──
        Preset("Treble Boost", ShortArray(31) { i ->
            when { i < 20 -> 0; i < 25 -> 5; else -> 8 }
        }),
        Preset("Air", ShortArray(31) { i ->
            when { i < 22 -> 0; i < 27 -> 3; else -> 6 }
        }),
        // ── Genre presets ──
        Preset("Rock", ShortArray(31) { i ->
            when { i < 3 -> 5; i < 6 -> 4; i in 6..12 -> 0; i in 13..20 -> 2; else -> 3 }
        }),
        Preset("Pop", ShortArray(31) { i ->
            when { i < 4 -> -1; i in 4..10 -> 3; i in 11..18 -> 0; else -> -2 }
        }),
        Preset("Jazz", ShortArray(31) { i ->
            when { i < 3 -> 4; i in 3..8 -> 2; i in 9..16 -> 0; else -> 3 }
        }),
        Preset("EDM", ShortArray(31) { i ->
            when { i < 5 -> 5; i in 5..10 -> -2; i in 11..18 -> 1; else -> 3 }
        }),
        Preset("Classical", ShortArray(31) { i ->
            when { i < 3 -> 3; i in 3..10 -> 0; i in 11..20 -> -1; else -> 2 }
        }),
        Preset("Dance", ShortArray(31) { i ->
            when { i < 4 -> 6; i in 4..9 -> 0; i in 10..16 -> 4; else -> 3 }
        }),
        Preset("Vocal", ShortArray(31) { i ->
            when { i < 6 -> -2; i in 6..14 -> 4; i in 15..22 -> 1; else -> -1 }
        }),
        Preset("Loudness", ShortArray(31) { i ->
            when { i < 4 -> 6; i in 4..12 -> 0; i in 13..20 -> -1; else -> 5 }
        }),
        Preset("Hip Hop", ShortArray(31) { i ->
            when { i < 5 -> 7; i in 5..10 -> -1; i in 11..20 -> 1; else -> 2 }
        }),
        Preset("Gaming", ShortArray(31) { i ->
            when { i < 3 -> 4; i in 3..8 -> -2; i in 9..18 -> 0; else -> 3 }
        }),
        // ── New presets ──
        Preset("Acoustic", ShortArray(31) { i ->
            when { i < 3 -> 3; i in 3..8 -> 1; i in 9..18 -> 2; i in 19..25 -> 1; else -> 0 }
        }),
        Preset("R&B", ShortArray(31) { i ->
            when { i < 5 -> 5; i in 5..10 -> 0; i in 11..20 -> 2; else -> 1 }
        }),
        Preset("Metal", ShortArray(31) { i ->
            when { i < 3 -> 6; i in 3..8 -> -2; i in 9..16 -> 0; i in 17..25 -> 4; else -> 2 }
        }),
        Preset("Electronic", ShortArray(31) { i ->
            when { i < 5 -> 6; i in 5..9 -> -3; i in 10..18 -> 0; i in 19..25 -> 3; else -> 5 }
        }),
        Preset("Latin", ShortArray(31) { i ->
            when { i < 4 -> 4; i in 4..10 -> 1; i in 11..20 -> 3; else -> 2 }
        }),
        Preset("Podcast", ShortArray(31) { i ->
            when { i < 6 -> -3; i in 6..14 -> 5; i in 15..22 -> 2; else -> -1 }
        }),
        Preset("Movie", ShortArray(31) { i ->
            when { i < 4 -> 6; i in 4..8 -> 2; i in 9..16 -> -1; i in 17..25 -> 3; else -> 4 }
        }),
        Preset("Night Mode", ShortArray(31) { i ->
            when { i < 5 -> -4; i in 5..12 -> 0; i in 13..22 -> -2; else -> -5 }
        }),
        Preset("Concert", ShortArray(31) { i ->
            when { i < 3 -> 4; i in 3..10 -> -1; i in 11..20 -> 2; else -> 4 }
        }),
        Preset("Phone", ShortArray(31) { i ->
            when { i < 8 -> -5; i in 8..16 -> 6; i in 17..25 -> -3; else -> -6 }
        })
    )

    fun levelsForCount(preset: Preset, count: Int): ShortArray {
        if (count >= 31) return preset.levels
        val result = ShortArray(count)
        val step = 31f / count
        for (i in 0 until count) {
            val srcIdx = (i * step).toInt().coerceAtMost(30)
            result[i] = preset.levels[srcIdx]
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
