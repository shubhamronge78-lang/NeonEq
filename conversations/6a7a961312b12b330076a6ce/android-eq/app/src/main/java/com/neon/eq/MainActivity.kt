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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neon.eq.engine.EqualizerEngine
import com.neon.eq.engine.Presets
import kotlin.math.round
import android.content.Context
import android.os.Process
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

class MainActivity : ComponentActivity() {

    private val engine = EqualizerEngine(this)

    companion object {
        private const val CRASH_PREFS = "neon_crash"
        private const val CRASH_KEY = "last_crash"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install a global crash catcher FIRST, before anything else can throw.
        // If something we haven't anticipated crashes the app (on any thread), we
        // save the real stack trace and show it directly in-app on next launch
        // instead of leaving you staring at a dead/looping loading screen with
        // zero information about what actually went wrong.
        val prefs = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = throwable.stackTraceToString()
                prefs.edit().putString(CRASH_KEY, "Thread: ${thread.name}\n\n$trace").apply()
            } catch (_: Throwable) { }
            defaultHandler?.uncaughtException(thread, throwable)
                ?: Process.killProcess(Process.myPid())
        }

        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.MODIFY_AUDIO_SETTINGS), 100)
        }

        val lastCrash = prefs.getString(CRASH_KEY, null)

        if (lastCrash != null) {
            setContent {
                NeonEQTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        CrashScreen(lastCrash) {
                            prefs.edit().remove(CRASH_KEY).apply()
                            engine.attachToGlobalSession()
                            recreate()
                        }
                    }
                }
            }
            return
        }

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
        engine.release()
        super.onDestroy()
    }
}

@Composable
fun CrashScreen(trace: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050508))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("NEON EQ CRASHED", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF4081))
        Spacer(Modifier.height(8.dp))
        Text(
            "Screenshot this and send it back — this is the real error, not a guess.",
            fontSize = 12.sp, color = Color.Gray
        )
        Spacer(Modifier.height(16.dp))
        Text(
            trace,
            fontSize = 10.sp,
            color = Color(0xFF00E5FF),
            modifier = Modifier
                .background(Color(0xFF0D0D14))
                .padding(12.dp)
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDismiss) {
            Text("Dismiss & Retry")
        }
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

    // Single float array for band levels — ONE state, ONE recomposition
    var bandLevels by remember { mutableStateOf(FloatArray(31) { 0f }) }

    LaunchedEffect(Unit) {
        engine.onReady = { ready, msg, bandList ->
            isReady = ready
            statusMsg = msg
            bands = bandList
        }
    }

    if (!isReady) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF050508)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF00E5FF))
                Spacer(Modifier.height(16.dp))
                Text(statusMsg, fontSize = 12.sp, color = Color.Gray)
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color(0xFF050508))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Header ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NEON EQ", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    engine.setEnabled(it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF00E5FF),
                    checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.3f)
                )
            )
        }
        Text("System-Wide Audio Equalizer", fontSize = 12.sp, color = Color.Gray)
        Text(statusMsg, fontSize = 9.sp, color = Color(0xFF7C4DFF))

        Spacer(Modifier.height(16.dp))

        // ── Presets ──
        Text("PRESETS", fontSize = 11.sp, color = Color(0xFF7C4DFF), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        // ── Band count selector ──
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(5, 10, 15, 31).forEach { count ->
                FilterChip(
                    selected = bandCount == count,
                    onClick = {
                        bandCount = count
                        engine.setBandCount(count)
                        // Reset visual levels to flat when changing band count
                        bandLevels = FloatArray(31) { 0f }
                        selectedPreset = "Flat"
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

        // ── Canvas-based EQ — ONE composable, no Slider widgets ──
        val bandList = bands.take(bandCount)

        CanvasEQ(
            bandCount = bandCount,
            bands = bandList,
            levels = bandLevels,
            onLevelChange = { band, level ->
                // Update visual instantly
                val newLevels = bandLevels.copyOf()
                newLevels[band] = level
                bandLevels = newLevels
                // Apply audio immediately (debounced in engine via single-thread executor)
                engine.setBandLevel(band, round(level).toInt().toShort())
            }
        )

        Spacer(Modifier.height(16.dp))

        // ── Effect sliders ──
        EffectSlider("BASS BOOST", bassBoost, 0..1000) { v ->
            bassBoost = v
            engine.setBassBoost(v)
        }
        EffectSlider("3D SOUND", virtualizer, 0..1000) { v ->
            virtualizer = v
            engine.setVirtualizer(v)
        }
        EffectSlider("LOUDNESS", loudness, 0..4000) { v ->
            loudness = v
            engine.setLoudness(v)
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun CanvasEQ(
    bandCount: Int,
    bands: List<EqualizerEngine.BandInfo>,
    levels: FloatArray,
    onLevelChange: (Int, Float) -> Unit
) {
    val bgColor = Color(0xFF1A1A2E)
    val gradientTop = Color(0xFF7C4DFF)
    val gradientBottom = Color(0xFF00E5FF)
    val barWidth = 12f
    val barHeight = 180f
    val minHeight = 4f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .pointerInput(bandCount) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val slotWidth = size.width / bandCount
                        val band = (offset.x / slotWidth).toInt().coerceIn(0, bandCount - 1)
                        val normY = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        val level = (normY * 30f - 15f).coerceIn(-15f, 15f)
                        onLevelChange(band, level)
                    },
                    onDrag = { change, _ ->
                        val slotWidth = size.width / bandCount
                        val band = (change.position.x / slotWidth).toInt().coerceIn(0, bandCount - 1)
                        val normY = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        val level = (normY * 30f - 15f).coerceIn(-15f, 15f)
                        onLevelChange(band, level)
                        change.consume()
                    }
                )
            }
            .pointerInput(bandCount) {
                detectTapGestures(
                    onTap = { offset ->
                        val slotWidth = size.width / bandCount
                        val band = (offset.x / slotWidth).toInt().coerceIn(0, bandCount - 1)
                        val normY = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        val level = (normY * 30f - 15f).coerceIn(-15f, 15f)
                        onLevelChange(band, level)
                    }
                )
            }
    ) {
        val slotWidth = size.width / bandCount

        for (i in 0 until bandCount) {
            val level = levels.getOrElse(i) { 0f }
            val normLevel = (level + 15f) / 30f
            val x = i * slotWidth + (slotWidth - barWidth) / 2f
            val barH = (barHeight * normLevel).coerceAtLeast(minHeight)
            val y = size.height - 60f - barH

            // Background bar
            drawRoundRect(
                color = bgColor,
                topLeft = Offset(x, size.height - 60f - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )

            // Gradient fill bar
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(gradientTop, gradientBottom),
                    startY = y,
                    endY = y + barH
                ),
                topLeft = Offset(x, y),
                size = Size(barWidth, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )

            // Frequency label
            val band = bands.getOrNull(i)
            val freqText = if (band != null) {
                if (band.freq >= 1000) "${band.freq / 1000}k" else "${band.freq}"
            } else ""
            drawIntoCanvas {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = 18f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                it.nativeCanvas.drawText(
                    freqText,
                    x + barWidth / 2f,
                    size.height - 40f,
                    paint
                )
                // Level value
                paint.color = android.graphics.Color.rgb(0, 229, 255)
                paint.textSize = 16f
                it.nativeCanvas.drawText(
                    "${round(level).toInt()}",
                    x + barWidth / 2f,
                    size.height - 15f,
                    paint
                )
            }
        }
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
