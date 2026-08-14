package com.neon.eq.engine

object Presets {

    data class Preset(val name: String, val levels: ShortArray)

    val presets: List<Preset> = listOf(
        Preset("Flat", ShortArray(31) { 0 }),
        Preset("Bass Boost", ShortArray(31) { i ->
            when { i < 5 -> (8 - i).toShort(); i < 8 -> 3; else -> 0 }
        }),
        Preset("Bass Extreme", ShortArray(31) { i ->
            when { i < 4 -> 12; i < 7 -> 6; else -> 0 }
        }),
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
            when { i < 5 -> 7; i in 5..10 -> -2; i in 11..18 -> 1; else -> 4 }
        }),
        Preset("Classical", ShortArray(31) { i ->
            when { i < 3 -> 3; i in 3..10 -> 0; i in 11..20 -> -1; else -> 2 }
        }),
        Preset("Dance", ShortArray(31) { i ->
            when { i < 4 -> 6; i in 4..9 -> 0; i in 10..16 -> 4; else -> 3 }
        }),
        Preset("Treble Boost", ShortArray(31) { i ->
            when { i < 20 -> 0; i < 25 -> 5; else -> 8 }
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
}
