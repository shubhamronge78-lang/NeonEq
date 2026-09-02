package com.neon.eq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neon.eq.engine.EQService
import com.neon.eq.engine.EqualizerEngine
import com.neon.eq.engine.Presets
import kotlinx.coroutines.launch
import kotlin.math.round
import android.content.Context
import android.os.Process
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

class MainActivity : ComponentActivity() {

    // Shared singleton — same instance the background EQService uses, so the UI is
    // always reflecting/controlling the actual running effects, not a stale copy.
    private val engine by lazy { EqualizerEngine.getInstance(this) }

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

        val perms = mutableListOf(Manifest.permission.MODIFY_AUDIO_SETTINGS, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) {
            requestPermissions(toRequest.toTypedArray(), 100)
        }

        val lastCrash = prefs.getString(CRASH_KEY, null)

        if (lastCrash != null) {
            setContent {
                NeonEQTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        CrashScreen(lastCrash) {
                            prefs.edit().remove(CRASH_KEY).apply()
                            startEqService()
                            engine.attachToGlobalSession()
                            recreate()
                        }
                    }
                }
            }
            return
        }

        // Start the background foreground service so the equalizer keeps running
        // system-wide even after this screen is closed — this is what makes it an
        // actual "system-wide" EQ instead of one that only works while the app is open.
        if (engine.isBoot()) {
            startEqService()
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

    private fun startEqService() {
        try {
            val intent = Intent(this, EQService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Throwable) { }
    }

    private fun stopEqService() {
        try {
            startService(Intent(this, EQService::class.java).setAction(EQService.ACTION_STOP))
        } catch (_: Throwable) { }
    }

    // Deliberately NOT calling engine.release() here anymore. The engine is a shared
    // singleton kept alive by the background EQService — closing this screen should
    // not kill the system-wide effects. Only the "Off" switch (which stops the
    // service) actually releases them.
    override fun onDestroy() {
        super.onDestroy()
    }

    fun onEnabledToggled(on: Boolean) {
        if (on) startEqService() else stopEqService()
    }

    // The RECORD_AUDIO prompt is async — attachToGlobalSession() already ran by the
    // time the user answers it, so the visualizer's first attach attempt very likely
    // failed silently (permission not yet granted) and never retried on its own.
    // Nudge it back to life the moment permission actually comes through.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val idx = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            if (idx >= 0 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED) {
                engine.retryVisualizerIfNeeded()
            }
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(engine.isBoot()) }
    var bandCount by remember { mutableStateOf(engine.bandCount) }
    var bassBoost by remember { mutableStateOf(engine.currentBassBoostValue()) }
    var virtualizer by remember { mutableStateOf(engine.currentVirtualizerValue()) }
    var loudness by remember { mutableStateOf(engine.currentLoudnessValue()) }
    var selectedPreset by remember { mutableStateOf(engine.selectedPresetName) }
    var customPresets by remember { mutableStateOf(engine.listCustomPresets()) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showOverwriteDialog by remember { mutableStateOf(false) }
    var pendingPresetName by remember { mutableStateOf("") }
    var presetNameInput by remember { mutableStateOf("") }
    var menuPreset by remember { mutableStateOf<Presets.CustomPreset?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var renamingFrom by remember { mutableStateOf("") }

    var isReady by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("Loading...") }
    var bands by remember { mutableStateOf(engine.bands) }

    // Single float array for band levels — ONE state, ONE recomposition
    var bandLevels by remember {
        mutableStateOf(FloatArray(31) { i -> engine.currentLevelsSnapshot().getOrElse(i) { 0 }.toFloat() })
    }

    var waveform by remember { mutableStateOf(ByteArray(0)) }
    val snackbarHost = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val scope2 = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        engine.onReady = { ready, msg, bandList ->
            isReady = ready
            statusMsg = msg
            bands = bandList
        }
        if (engine.isReady) {
            isReady = true
            statusMsg = engine.statusMessage
            bands = engine.bands
        }
        engine.onWaveform = { data -> waveform = data }
    }

    // Clear callbacks on dispose (rotation, back press) — without this the old
    // lambdas keep firing into dead Compose state and the visualizer keeps posting
    // waveform data to nobody, leaking memory + wasting audio-thread CPU.
    DisposableEffect(Unit) {
        onDispose {
            engine.onReady = null
            engine.onWaveform = null
            engine.onSessionUpdate = null
        }
    }

    // Smoothly animate the whole band curve toward a new target (preset switch,
    // band-count change) instead of an instant jump — much nicer to watch.
    fun animateLevelsTo(target: FloatArray) {
        val start = bandLevels.copyOf()
        scope.launch {
            val steps = 12
            for (s in 1..steps) {
                val t = s / steps.toFloat()
                val eased = 1f - (1f - t) * (1f - t) // ease-out
                val frame = FloatArray(31) { i ->
                    val from = start.getOrElse(i) { 0f }
                    val to = target.getOrElse(i) { 0f }
                    from + (to - from) * eased
                }
                bandLevels = frame
                engine.setBandLevels(ShortArray(31) { i -> round(frame.getOrElse(i) { 0f }).toInt().toShort() })
                kotlinx.coroutines.delay(16L)
            }
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

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color(0xFF050508))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Header with breathing glow ──
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            BreathingGlow(active = enabled)
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
                        if (context is MainActivity) context.onEnabledToggled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00E5FF),
                        checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.3f)
                    )
                )
            }
        }
        Text("System-Wide Audio Equalizer", fontSize = 12.sp, color = Color.Gray)
        Text(statusMsg, fontSize = 9.sp, color = Color(0xFF7C4DFF))

        Spacer(Modifier.height(16.dp))

        // ── Live spectrum visualizer ──
        VisualizerBars(waveform = waveform, active = enabled)

        Spacer(Modifier.height(16.dp))

        // ── Presets ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("PRESETS", fontSize = 11.sp, color = Color(0xFF7C4DFF), fontWeight = FontWeight.Bold)
            Row {
                Text(
                    "↺ Reset All",
                    fontSize = 11.sp,
                    color = Color(0xFFFF4081),
                    modifier = Modifier.clickable {
                        animateLevelsTo(FloatArray(31) { 0f })
                        selectedPreset = "Flat"
                        engine.setSelectedPresetName("Flat")
                        bassBoost = 0; engine.setBassBoost(0)
                        virtualizer = 0; engine.setVirtualizer(0)
                        loudness = 0; engine.setLoudness(0)
                    }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "+ Save current",
                    fontSize = 11.sp,
                    color = Color(0xFF00E5FF),
                    modifier = Modifier.clickable {
                        presetNameInput = ""
                        showSaveDialog = true
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Presets.presets, key = { "b_" + it.name }) { preset ->
                PresetChip(preset.name, selectedPreset == preset.name) {
                    selectedPreset = preset.name
                    engine.setSelectedPresetName(preset.name)
                    val levels = Presets.levelsForCount(preset, bandCount)
                    val newLevels = FloatArray(31) { 0f }
                    levels.forEachIndexed { i, lvl -> newLevels[i] = lvl.toFloat() }
                    animateLevelsTo(newLevels)
                }
            }
            items(customPresets, key = { "c_" + it.name }) { preset ->
                CustomPresetChip(
                    name = preset.name,
                    selected = selectedPreset == preset.name,
                    onClick = {
                        selectedPreset = preset.name
                        engine.setSelectedPresetName(preset.name)
                        val levels = Presets.levelsForCount(preset, bandCount)
                        val newLevels = FloatArray(31) { 0f }
                        levels.forEachIndexed { i, lvl -> newLevels[i] = lvl.toFloat() }
                        animateLevelsTo(newLevels)
                        bassBoost = preset.bassBoost; engine.setBassBoost(preset.bassBoost)
                        virtualizer = preset.virtualizer; engine.setVirtualizer(preset.virtualizer)
                        loudness = preset.loudness; engine.setLoudness(preset.loudness)
                    },
                    onLongPress = { menuPreset = preset },
                    onDelete = {
                        engine.deleteCustomPreset(preset.name)
                        customPresets = engine.listCustomPresets()
                        if (selectedPreset == preset.name) {
                            selectedPreset = "Flat"
                            engine.setSelectedPresetName("Flat")
                        }
                        scope2.launch { snackbarHost.showSnackbar("Deleted '${'$'}{preset.name}'") }
                    }
                )
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
                        animateLevelsTo(FloatArray(31) { 0f })
                        selectedPreset = "Flat"
                        engine.setSelectedPresetName("Flat")
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
                val newLevels = bandLevels.copyOf()
                newLevels[band] = level
                bandLevels = newLevels
                engine.setBandLevel(band, round(level).toInt().toShort())
                selectedPreset = "Custom"
            },
            onResetBand = { band ->
                val newLevels = bandLevels.copyOf()
                newLevels[band] = 0f
                bandLevels = newLevels
                engine.setBandLevel(band, 0)
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
        EffectSlider("LOUDNESS", loudness, 0..2000) { v ->
            loudness = v
            engine.setLoudness(v)
        }

        Spacer(Modifier.height(32.dp))
    }
    SnackbarHost(
        hostState = snackbarHost,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    } // end Box

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save current EQ as preset") },
            text = {
                OutlinedTextField(
                    value = presetNameInput,
                    onValueChange = { presetNameInput = it },
                    label = { Text("Preset name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = presetNameInput.trim()
                    if (name.isNotEmpty()) {
                        if (engine.customPresetExists(name)) {
                            pendingPresetName = name
                            showSaveDialog = false
                            showOverwriteDialog = true
                        } else {
                            val levels = ShortArray(31) { i -> round(bandLevels.getOrElse(i) { 0f }).toInt().toShort() }
                            engine.saveCustomPreset(name, levels, bassBoost, virtualizer, loudness)
                            customPresets = engine.listCustomPresets()
                            selectedPreset = name
                            engine.setSelectedPresetName(name)
                            scope2.launch { snackbarHost.showSnackbar("Preset '$name' saved") }
                            showSaveDialog = false
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showOverwriteDialog) {
        AlertDialog(
            onDismissRequest = { showOverwriteDialog = false },
            title = { Text("Overwrite preset?") },
            text = { Text("A preset named '$pendingPresetName' already exists. Overwrite it with current settings?") },
            confirmButton = {
                TextButton(onClick = {
                    val name = pendingPresetName
                    val levels = ShortArray(31) { i -> round(bandLevels.getOrElse(i) { 0f }).toInt().toShort() }
                    engine.saveCustomPreset(name, levels, bassBoost, virtualizer, loudness)
                    customPresets = engine.listCustomPresets()
                    selectedPreset = name
                    engine.setSelectedPresetName(name)
                    scope2.launch { snackbarHost.showSnackbar("Preset '$name' updated") }
                    showOverwriteDialog = false
                }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverwriteDialog = false
                    showSaveDialog = true
                }) { Text("Cancel") }
            }
        )
    }

    menuPreset?.let { preset ->
        DropdownMenu(
            expanded = menuPreset != null,
            onDismissRequest = { menuPreset = null },
        ) {
            DropdownMenuItem(
                text = { Text("Apply") },
                onClick = {
                    selectedPreset = preset.name
                    engine.setSelectedPresetName(preset.name)
                    val levels = Presets.levelsForCount(preset, bandCount)
                    val newLevels = FloatArray(31) { 0f }
                    levels.forEachIndexed { i, lvl -> newLevels[i] = lvl.toFloat() }
                    animateLevelsTo(newLevels)
                    bassBoost = preset.bassBoost; engine.setBassBoost(preset.bassBoost)
                    virtualizer = preset.virtualizer; engine.setVirtualizer(preset.virtualizer)
                    loudness = preset.loudness; engine.setLoudness(preset.loudness)
                    menuPreset = null
                }
            )
            DropdownMenuItem(
                text = { Text("Update with current") },
                onClick = {
                    val levels = ShortArray(31) { i -> round(bandLevels.getOrElse(i) { 0f }).toInt().toShort() }
                    engine.updateCustomPreset(preset.name, levels, bassBoost, virtualizer, loudness)
                    customPresets = engine.listCustomPresets()
                    scope2.launch { snackbarHost.showSnackbar("Updated '${'$'}{preset.name}'") }
                    menuPreset = null
                }
            )
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    renameInput = preset.name
                    renamingFrom = preset.name
                    showRenameDialog = true
                    menuPreset = null
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = Color(0xFFFF4081)) },
                onClick = {
                    engine.deleteCustomPreset(preset.name)
                    customPresets = engine.listCustomPresets()
                    if (selectedPreset == preset.name) {
                        selectedPreset = "Flat"
                        engine.setSelectedPresetName("Flat")
                    }
                    scope2.launch { snackbarHost.showSnackbar("Deleted '${'$'}{preset.name}'") }
                    menuPreset = null
                }
            )
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename preset") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("New name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameInput.trim()
                    if (newName.isNotEmpty() && newName != renamingFrom) {
                        engine.renameCustomPreset(renamingFrom, newName)
                        customPresets = engine.listCustomPresets()
                        if (selectedPreset == renamingFrom) {
                            selectedPreset = newName
                            engine.setSelectedPresetName(newName)
                        }
                        scope2.launch { snackbarHost.showSnackbar("Renamed to '$newName'") }
                    }
                    showRenameDialog = false
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// Soft pulsing radial glow behind the header — brighter/faster when the EQ is on,
// dim and idle when off. Purely cosmetic, but this is the "alive" feeling that
// makes a neon UI actually feel neon instead of just colored.
@Composable
fun BreathingGlow(active: Boolean) {
    val infinite = rememberInfiniteTransition(label = "glow")
    val alpha by infinite.animateFloat(
        initialValue = if (active) 0.12f else 0.04f,
        targetValue = if (active) 0.35f else 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 1400 else 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    Box(
        modifier = Modifier
            .height(80.dp)
            .fillMaxWidth(0.7f)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF00E5FF).copy(alpha = alpha), Color.Transparent)
                )
            )
    )
}

// Live spectrum visualizer rendered from raw waveform bytes off the master mix.
// Degrades to a gentle idle pulse if no waveform data is available yet (permission
// denied, unsupported device, or nothing playing) instead of showing nothing.
@Composable
fun VisualizerBars(waveform: ByteArray, active: Boolean) {
    val barCount = 32
    val infinite = rememberInfiniteTransition(label = "idlePulse")
    val idlePhase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "idlePhase"
    )

    Canvas(modifier = Modifier.fillMaxWidth().height(56.dp)) {
        val slotWidth = size.width / barCount
        val barWidthPx = slotWidth * 0.6f
        val midY = size.height / 2f

        for (i in 0 until barCount) {
            val amp: Float = if (waveform.isNotEmpty()) {
                val chunk = waveform.size / barCount
                val startIdx = (i * chunk).coerceIn(0, waveform.size - 1)
                val endIdx = ((i + 1) * chunk).coerceIn(startIdx + 1, waveform.size)
                var sum = 0f
                for (j in startIdx until endIdx) {
                    val v = (waveform[j].toInt() and 0xFF) - 128
                    sum += kotlin.math.abs(v)
                }
                ((sum / (endIdx - startIdx)) / 128f).coerceIn(0.03f, 1f)
            } else {
                val wave = kotlin.math.sin((i / barCount.toFloat() + idlePhase) * Math.PI * 2).toFloat()
                (0.08f + 0.05f * wave).coerceIn(0.03f, 0.2f)
            }
            val barH = (size.height * 0.9f * amp).coerceAtLeast(3f)
            val x = i * slotWidth + (slotWidth - barWidthPx) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF7C4DFF), Color(0xFF00E5FF)),
                    startY = midY - barH / 2f,
                    endY = midY + barH / 2f
                ),
                topLeft = Offset(x, midY - barH / 2f),
                size = Size(barWidthPx, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
            )
        }
    }
}

@Composable
fun CanvasEQ(
    bandCount: Int,
    bands: List<EqualizerEngine.BandInfo>,
    levels: FloatArray,
    onLevelChange: (Int, Float) -> Unit,
    onResetBand: (Int) -> Unit = {}
) {
    val bgColor = Color(0xFF1A1A2E)
    val gradientTop = Color(0xFF7C4DFF)
    val gradientBottom = Color(0xFF00E5FF)
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    // Everything below is computed in real pixels derived from dp, and — critically —
    // touch mapping and drawing both use the exact SAME "track" rectangle. Previously
    // touch used the full canvas height while the visual bar was confined to a fixed
    // 180px strip near the bottom regardless of screen density/canvas size, so where
    // you touched and where the bar actually moved didn't match. Fixed here.
    val barWidthPx = with(density) { 10.dp.toPx() }
    val minHeightPx = with(density) { 3.dp.toPx() }
    val labelAreaPx = with(density) { 46.dp.toPx() }

    fun levelFromY(y: Float, trackHeight: Float): Float {
        val clampedY = y.coerceIn(0f, trackHeight)
        val normY = 1f - (clampedY / trackHeight)
        return (normY * 30f - 15f).coerceIn(-15f, 15f)
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .pointerInput(bandCount) {
                val trackHeight = size.height.toFloat() - labelAreaPx
                detectDragGestures(
                    onDragStart = { offset ->
                        val slotWidth = size.width / bandCount
                        val band = (offset.x / slotWidth).toInt().coerceIn(0, bandCount - 1)
                        onLevelChange(band, levelFromY(offset.y, trackHeight))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, _ ->
                        val slotWidth = size.width / bandCount
                        val band = (change.position.x / slotWidth).toInt().coerceIn(0, bandCount - 1)
                        onLevelChange(band, levelFromY(change.position.y, trackHeight))
                        change.consume()
                    }
                )
            }
            .pointerInput(bandCount) {
                val trackHeight = size.height.toFloat() - labelAreaPx
                detectTapGestures(
                    onTap = { offset ->
                        val slotWidth = size.width / bandCount
                        val band = (offset.x / slotWidth).toInt().coerceIn(0, bandCount - 1)
                        onLevelChange(band, levelFromY(offset.y, trackHeight))
                    },
                    onDoubleTap = { offset ->
                        val slotWidth = size.width / bandCount
                        val band = (offset.x / slotWidth).toInt().coerceIn(0, bandCount - 1)
                        onResetBand(band)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                )
            }
    ) {
        val slotWidth = size.width / bandCount
        val trackHeight = size.height - labelAreaPx

        for (i in 0 until bandCount) {
            val level = levels.getOrElse(i) { 0f }
            val normLevel = (level + 15f) / 30f
            val x = i * slotWidth + (slotWidth - barWidthPx) / 2f
            val barH = (trackHeight * normLevel).coerceAtLeast(minHeightPx)
            val y = trackHeight - barH

            // Background bar — spans the FULL track, same rect touch mapping uses.
            drawRoundRect(
                color = bgColor,
                topLeft = Offset(x, 0f),
                size = Size(barWidthPx, trackHeight),
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
                size = Size(barWidthPx, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )

            // Frequency + level labels, drawn in the reserved label area below the track.
            val band = bands.getOrNull(i)
            val freqText = if (band != null) {
                if (band.freq >= 1000) "${band.freq / 1000}k" else "${band.freq}"
            } else ""
            drawIntoCanvas {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = with(density) { 11.sp.toPx() }
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                it.nativeCanvas.drawText(
                    freqText,
                    x + barWidthPx / 2f,
                    trackHeight + labelAreaPx * 0.45f,
                    paint
                )
                paint.color = android.graphics.Color.rgb(0, 229, 255)
                paint.textSize = with(density) { 10.sp.toPx() }
                it.nativeCanvas.drawText(
                    "${round(level).toInt()}",
                    x + barWidthPx / 2f,
                    trackHeight + labelAreaPx * 0.85f,
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
fun CustomPresetChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                if (selected) Color(0xFF7C4DFF).copy(alpha = 0.2f) else Color(0xFF1A1A2E),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 4.dp)
    ) {
        Text(
            name,
            fontSize = 12.sp,
            color = if (selected) Color(0xFF7C4DFF) else Color.Gray,
            modifier = Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Text("×", fontSize = 14.sp, color = Color(0xFFFF4081))
        }
    }
}

@Composable
fun EffectSlider(label: String, value: Int, range: IntRange, onValueChange: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(100.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            onValueChange(0)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    )
                },
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFF4081),
                activeTrackColor = Color(0xFFFF4081).copy(alpha = 0.4f)
            )
        )
        Text("$value", fontSize = 11.sp, color = Color(0xFFFF4081), modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
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
