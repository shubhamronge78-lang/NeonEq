package com.neon.eq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neon.eq.engine.EqualizerEngine
import com.neon.eq.engine.EQService
import com.neon.eq.engine.Presets
import kotlin.math.round

class MainActivity : ComponentActivity() {

    private val engine = EqualizerEngine(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.MODIFY_AUDIO_SETTINGS), 100)
        }

        engine.attachToGlobalSession()
        startService(Intent(this, EQService::class.java))

        setContent {
            NeonEQTheme {
                EqualizerScreen(engine)
            }
        }
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }
}

@Composable
fun NeonEQTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00E5FF),
            secondary = Color(0xFF7C4DFF),
            tertiary = Color(0xFFFF4081),
            background = Color(0xFF050508),
            surface = Color(0xFF0D0D14),
            onPrimary = Color.Black,
            onSurface = Color(0xFFE0E0FF)
        ),
        content = content
    )
}

// ── Colors ──
private val NeonCyan = Color(0xFF00E5FF)
private val NeonPurple = Color(0xFF7C4DFF)
private val NeonPink = Color(0xFFFF4081)
private val DarkBg = Color(0xFF050508)
private val BarBg = Color(0xFF1A1A2E)
private val GrayText = Color(0xFF888899)

@Composable
fun EqualizerScreen(engine: EqualizerEngine) {
    var enabled by remember { mutableStateOf(true) }
    var bandCount by remember { mutableStateOf(5) }
    var bassBoost by remember { mutableStateOf(0) }
    var virtualizer by remember { mutableStateOf(0) }
    var loudness by remember { mutableStateOf(0) }
    var selectedPreset by remember { mutableStateOf("Flat") }

    var isReady by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("Loading...") }
    var bands by remember { mutableStateOf(engine.bands) }

    var bandLevels by remember { mutableStateOf(FloatArray(31) { 0f }) }
    var pendingAudio by remember { mutableStateOf<Pair<Int, Float>?>(null) }

    // Smooth enable/disable transition
    val enabledAmt by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(500, easing = FastOutSlowInEasing)
    )

    LaunchedEffect(Unit) {
        engine.onReady = { ready, msg, bandList ->
            isReady = ready; statusMsg = msg; bands = bandList
        }
    }

    LaunchedEffect(pendingAudio) {
        if (pendingAudio != null) {
            kotlinx.coroutines.delay(120)
            pendingAudio?.let { (band, level) ->
                engine.setBandLevel(band, round(level).toInt().toShort())
            }
            pendingAudio = null
        }
    }

    if (!isReady) {
        Box(Modifier.fillMaxSize().background(DarkBg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = NeonCyan, strokeWidth = 3.dp)
                Spacer(Modifier.height(20.dp))
                Text(statusMsg, fontSize = 12.sp, color = GrayText)
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(DarkBg)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Header ──
        Text("NEON EQ", fontSize = 32.sp, fontWeight = FontWeight.Black, color = NeonCyan)
        Text("System-Wide Audio Equalizer", fontSize = 11.sp, color = GrayText)
        Text(statusMsg, fontSize = 8.sp, color = NeonPurple.copy(alpha = 0.7f))

        Spacer(Modifier.height(16.dp))

        // ── Power Switch ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "POWER",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = lerp(GrayText, NeonCyan, enabledAmt)
            )
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it; engine.setEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NeonCyan,
                    checkedTrackColor = NeonCyan.copy(alpha = 0.3f),
                    uncheckedThumbColor = GrayText,
                    uncheckedTrackColor = BarBg
                )
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Presets ──
        Text("PRESETS", fontSize = 10.sp, color = NeonPurple, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Presets.presets, key = { it.name }) { preset ->
                PresetChip(preset.name, selectedPreset == preset.name) {
                    selectedPreset = preset.name
                    val levels = Presets.levelsForCount(preset, bandCount)
                    val newLevels = FloatArray(31) { 0f }
                    levels.forEachIndexed { i, lvl ->
                        newLevels[i] = lvl.toFloat()
                        engine.setBandLevel(i, lvl)
                    }
                    bandLevels = newLevels
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Band Count ──
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(5, 10, 15, 31).forEach { count ->
                FilterChip(
                    selected = bandCount == count,
                    onClick = {
                        bandCount = count
                        engine.setBandCount(count)
                    },
                    label = { Text("$count", fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                        selectedLabelColor = NeonCyan
                    )
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Canvas EQ ──
        CanvasEQ(
            bandCount = bandCount,
            levels = bandLevels,
            enabled = enabled,
            enabledAmt = enabledAmt,
            onLevelChange = { band, level ->
                val newLevels = bandLevels.copyOf()
                newLevels[band] = level
                bandLevels = newLevels
                pendingAudio = band to level
            }
        )

        // ── Frequency Labels ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            bands.take(bandCount).forEach { band ->
                Text(
                    if (band.freq >= 1000) "${band.freq / 1000}k" else "${band.freq}",
                    fontSize = 7.sp, color = GrayText,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Effect Sliders ──
        EffectSlider("BASS", bassBoost, 0..1000) { v -> bassBoost = v; engine.setBassBoost(v) }
        EffectSlider("3D SOUND", virtualizer, 0..1000) { v -> virtualizer = v; engine.setVirtualizer(v) }
        EffectSlider("LOUDNESS", loudness, 0..4000) { v -> loudness = v; engine.setLoudness(v) }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun CanvasEQ(
    bandCount: Int,
    levels: FloatArray,
    enabled: Boolean,
    enabledAmt: Float,
    onLevelChange: (Int, Float) -> Unit
) {
    // ── Smooth per-band height animation ──
    val animatedLevels = (0 until 31).map { i ->
        animateFloatAsState(
            targetValue = if (i < bandCount) levels.getOrElse(i) { 0f } else 0f,
            animationSpec = tween(350, easing = FastOutSlowInEasing)
        )
    }

    // ── Subtle breathing pulse for glow ──
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(bandCount) {
                detectTapGestures { offset ->
                    val slot = size.width / bandCount
                    val band = (offset.x / slot).toInt().coerceIn(0, bandCount - 1)
                    val normY = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                    val level = (normY * 30f - 15f).coerceIn(-15f, 15f)
                    onLevelChange(band, level)
                }
            }
            .pointerInput(bandCount) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        val slot = size.width / bandCount
                        val band = (change.position.x / slot).toInt().coerceIn(0, bandCount - 1)
                        val normY = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        val level = (normY * 30f - 15f).coerceIn(-15f, 15f)
                        onLevelChange(band, level)
                        change.consume()
                    }
                )
            }
    ) {
        val slotW = size.width / bandCount
        val barW = slotW * 0.42f
        val barMaxH = size.height - 20f
        val glowAlpha = 0.15f * pulse * enabledAmt

        // Blend colors smoothly between disabled and enabled
        val barColor = lerp(GrayText.copy(alpha = 0.5f), NeonCyan, enabledAmt)
        val topColor = lerp(GrayText.copy(alpha = 0.3f), NeonPurple, enabledAmt)
        val centerLineColor = Color.White.copy(alpha = 0.06f + 0.04f * enabledAmt)

        // Center line (0 dB reference)
        val centerY = 10f + barMaxH * 0.5f
        drawLine(
            color = centerLineColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1f
        )

        for (i in 0 until bandCount) {
            val level = animatedLevels[i].value
            val normLevel = (level + 15f) / 30f
            val x = i * slotW + (slotW - barW) / 2f
            val barH = (barMaxH * normLevel).coerceAtLeast(3f)
            val y = 10f + barMaxH - barH

            // Background track
            drawRoundRect(
                color = BarBg,
                topLeft = Offset(x, 10f),
                size = Size(barW, barMaxH),
                cornerRadius = CornerRadius(4f, 4f)
            )

            // ── Multi-layer soft glow ──
            if (enabledAmt > 0.01f && barH > 6f) {
                // Outer glow — wide, very soft
                drawRoundRect(
                    color = NeonCyan.copy(alpha = glowAlpha * 0.3f),
                    topLeft = Offset(x - 10f, y - 6f),
                    size = Size(barW + 20f, barH + 12f),
                    cornerRadius = CornerRadius(12f, 12f)
                )
                // Mid glow
                drawRoundRect(
                    color = NeonCyan.copy(alpha = glowAlpha * 0.6f),
                    topLeft = Offset(x - 5f, y - 3f),
                    size = Size(barW + 10f, barH + 6f),
                    cornerRadius = CornerRadius(8f, 8f)
                )
                // Inner glow
                drawRoundRect(
                    color = NeonCyan.copy(alpha = glowAlpha),
                    topLeft = Offset(x - 2f, y - 1f),
                    size = Size(barW + 4f, barH + 2f),
                    cornerRadius = CornerRadius(6f, 6f)
                )
            }

            // Main bar with vertical gradient
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(topColor, barColor),
                    startY = y, endY = y + barH
                ),
                topLeft = Offset(x, y),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(4f, 4f)
            )

            // Bright cap — soft white top
            if (enabledAmt > 0.01f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.5f * enabledAmt),
                    topLeft = Offset(x, y),
                    size = Size(barW, 2.5f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }

            // Faded reflection below center line
            if (enabledAmt > 0.01f && barH > 20f) {
                val reflectH = (barH * 0.25f).coerceAtMost(15f)
                val reflectY = 10f + barMaxH - barH + barH - reflectH // bottom of bar + reflect
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            NeonCyan.copy(alpha = 0.15f * enabledAmt),
                            Color.Transparent
                        ),
                        startY = reflectY,
                        endY = reflectY + reflectH
                    ),
                    topLeft = Offset(x, reflectY),
                    size = Size(barW, reflectH),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
    }
}

@Composable
fun PresetChip(name: String, selected: Boolean, onClick: () -> Unit) {
    val bgAlpha by animateFloatAsState(if (selected) 0.15f else 0f, tween(250))
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) NeonCyan.copy(alpha = bgAlpha) else BarBg,
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            name,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) NeonCyan else GrayText
        )
    }
}

@Composable
fun EffectSlider(label: String, value: Int, range: IntRange, onValueChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = GrayText, modifier = Modifier.width(80.dp), fontWeight = FontWeight.Bold)
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = NeonPink,
                activeTrackColor = NeonPink.copy(alpha = 0.4f),
                inactiveTrackColor = BarBg
            )
        )
        Text(
            if (value > 999) "${value / 1000}k" else "$value",
            fontSize = 10.sp, color = NeonPink,
            modifier = Modifier.width(40.dp), textAlign = TextAlign.End
        )
    }
}
