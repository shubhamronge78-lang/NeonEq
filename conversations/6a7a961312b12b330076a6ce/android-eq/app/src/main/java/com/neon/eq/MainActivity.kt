package com.neon.eq

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neon.eq.engine.EqualizerEngine
import com.neon.eq.engine.Presets

class MainActivity : ComponentActivity() {

    private val engine = EqualizerEngine(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.MODIFY_AUDIO_SETTINGS), 100)
        }

        // Use global session with silent audio track to keep EQ alive
        engine.attachToGlobalSession()

        setContent {
            NeonEQTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EqualizerScreen(engine)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }
}

@Composable
fun NeonEQTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF00E5FF),
        secondary = Color(0xFF7C4DFF),
        tertiary = Color(0xFFFF4081),
        background = Color(0xFF050508),
        surface = Color(0xFF0D0D14),
        onPrimary = Color.Black,
        onSurface = Color(0xFFE0E0FF)
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

@Composable
fun EqualizerScreen(engine: EqualizerEngine) {
    var enabled by remember { mutableStateOf(true) }
    var bandCount by remember { mutableStateOf(10) }
    var bassBoost by remember { mutableStateOf(0) }
    var virtualizer by remember { mutableStateOf(0) }
    var loudness by remember { mutableStateOf(0) }
    var selectedPreset by remember { mutableStateOf("Flat") }
    var bands by remember { mutableStateOf(engine.bands) }
    var statusMsg by remember { mutableStateOf(engine.statusMessage) }

    // Use a SnapshotStateMap for band levels — each entry is independently observable
    val bandLevels = remember { mutableStateMapOf<Int, Float>() }

    // Initialize band levels
    LaunchedEffect(bandCount) {
        bands = engine.bands
        statusMsg = engine.statusMessage
        repeat(bandCount) { i ->
            if (bandLevels[i] == null) bandLevels[i] = 0f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color(0xFF050508))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NEON EQ", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF00E5FF))
            Switch(checked = enabled, onCheckedChange = {
                enabled = it; engine.setEnabled(it)
            }, colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF00E5FF),
                checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.3f)
            ))
        }

        Text("System-Wide Audio Equalizer", fontSize = 12.sp, color = Color.Gray)
        Text(statusMsg, fontSize = 10.sp, color = if (engine.isReady) Color(0xFF00E5FF) else Color(0xFFFF4081))

        Spacer(Modifier.height(16.dp))

        // Presets
        Text("PRESETS", fontSize = 11.sp, color = Color(0xFF7C4DFF), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Presets.presets, key = { it.name }) { preset ->
                PresetChip(preset.name, selectedPreset == preset.name) {
                    selectedPreset = preset.name
                    val levels = Presets.levelsForCount(preset, bandCount)
                    levels.forEachIndexed { i, lvl ->
                        engine.setBandLevel(i, lvl)
                        bandLevels[i] = lvl.toFloat()
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Band count selector
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(5, 10, 15, 31).forEach { count ->
                FilterChip(
                    selected = bandCount == count,
                    onClick = {
                        bandCount = count
                        engine.setBandCount(count)
                        bands = engine.bands
                        statusMsg = engine.statusMessage
                    },
                    label = { Text("$count", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.2f),
                        selectedLabelColor = Color(0xFF00E5FF)
                    )
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // EQ Bands — each band is its own composable to minimize recomposition
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            bands.take(bandCount).forEachIndexed { i, band ->
                key(band.index, bandCount) {
                    val level = bandLevels[i] ?: 0f
                    BandSlider(
                        bandIndex = i,
                        freq = band.freq,
                        level = level,
                        onLevelChange = { newLevel ->
                            bandLevels[i] = newLevel
                            engine.setBandLevel(i, newLevel.toInt().toShort())
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Effect sliders
        EffectSlider("BASS BOOST", bassBoost, 0..1000) {
            bassBoost = it; engine.setBassBoost(it)
        }
        EffectSlider("3D SOUND", virtualizer, 0..1000) {
            virtualizer = it; engine.setVirtualizer(it)
        }
        EffectSlider("LOUDNESS", loudness, 0..4000) {
            loudness = it; engine.setLoudness(it)
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun BandSlider(
    bandIndex: Int,
    freq: Int,
    level: Float,
    onLevelChange: (Float) -> Unit
) {
    val normLevel = (level + 15) / 30f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(160.dp)
                .background(Color(0xFF1A1A2E), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((160 * normLevel).coerceAtLeast(2f).dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF7C4DFF), Color(0xFF00E5FF))
                        ),
                        RoundedCornerShape(4.dp)
                    )
            )
        }
        Text(
            if (freq >= 1000) "${freq / 1000}k" else "$freq",
            fontSize = 8.sp,
            color = Color.Gray
        )
        Slider(
            value = level,
            onValueChange = onLevelChange,
            valueRange = -15f..15f,
            modifier = Modifier
                .height(40.dp)
                .width(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF00E5FF),
                activeTrackColor = Color(0xFF00E5FF).copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
fun PresetChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF1A1A2E),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(name, fontSize = 12.sp, color = if (selected) Color(0xFF00E5FF) else Color.Gray)
    }
}

@Composable
fun EffectSlider(label: String, value: Int, range: IntRange, onValueChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(100.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFF4081),
                activeTrackColor = Color(0xFFFF4081).copy(alpha = 0.4f)
            )
        )
        Text("$value", fontSize = 11.sp, color = Color(0xFFFF4081), modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
    }
}
