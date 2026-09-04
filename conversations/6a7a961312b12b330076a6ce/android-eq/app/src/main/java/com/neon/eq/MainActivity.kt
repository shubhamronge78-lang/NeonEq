package com.neon.eq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neon.eq.engine.EQService
import com.neon.eq.engine.EqualizerEngine
import com.neon.eq.engine.Presets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.round
import android.content.Context
import android.os.Process
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
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
    var bassBoost by remember { mutableStateOf(engine.currentBassBoostValue().coerceIn(0, 300)) }
    var virtualizer by remember { mutableStateOf(engine.currentVirtualizerValue().coerceIn(0, 300)) }
    var loudness by remember { mutableStateOf(engine.currentLoudnessValue().coerceIn(0, 300)) }
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
    var showSettings by remember { mutableStateOf(false) }
    // Settings preferences — read once, then kept in local state
    var startOnBoot by remember { mutableStateOf(engine.isStartOnBoot()) }
    var autoApplyPreset by remember { mutableStateOf(engine.isAutoApplyPreset()) }
    var showVisualizer by remember { mutableStateOf(engine.isShowVisualizer()) }
    var visStyle by remember { mutableStateOf(engine.getVisualizerStyle()) }
    var showGlow by remember { mutableStateOf(engine.isShowGlow()) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreJsonInput by remember { mutableStateOf("") }
    var restoreResultMsg by remember { mutableStateOf("") }
    var importJsonInput by remember { mutableStateOf("") }
    var importResultMsg by remember { mutableStateOf("") }

    var isReady by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("Loading...") }
    var bands by remember { mutableStateOf(engine.bands) }

    // Single float array for band levels — ONE state, ONE recomposition
    var bandLevels by remember {
        mutableStateOf(FloatArray(31) { i -> engine.currentLevelsSnapshot().getOrElse(i) { 0 }.toFloat() })
    }

    var waveform by remember { mutableStateOf(ByteArray(0)) }
    // Build #69: timestamp of the last capture delivery — lets the visualizer
    // detect a MIUI capture stall while the EQ is ON (the self-heal watchdog
    // needs up to ~4s to re-attach) and drop to the idle pulse instead of
    // drawing the frozen stale buffer.
    var waveformAt by remember { mutableStateOf(0L) }
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
        engine.onWaveform = { data ->
            waveform = data
            waveformAt = SystemClock.elapsedRealtime()
        }

        // Auto-apply last preset if setting is enabled
        if (engine.isAutoApplyPreset()) {
            if (engine.applyLastPreset()) {
                val snapshot = engine.currentLevelsSnapshot()
                bandLevels = FloatArray(31) { i -> snapshot.getOrElse(i) { 0 }.toFloat() }
                bassBoost = engine.currentBassBoostValue()
                virtualizer = engine.currentVirtualizerValue()
                loudness = engine.currentLoudnessValue()
            }
        }
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
                kotlinx.coroutines.delay(16L)
            }
        }
    }

    // ── Per-app profiles state ──
    var playingApp by remember { mutableStateOf(engine.playingPackage()) }
    var appProfiles by remember { mutableStateOf(engine.listAppProfiles()) }
    fun appLabel(pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }

    // The per-app auto-switch happens inside the engine's session scan (outside
    // Compose), so poll it and mirror any changes back into the UI state.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            val p = engine.playingPackage()
            if (p != playingApp) playingApp = p
            if (engine.selectedPresetName != selectedPreset) {
                selectedPreset = engine.selectedPresetName
                // Direct state copy — no engine calls here, the engine already
                // holds these values (calling setters would suppress the profile).
                val snap = engine.currentLevelsSnapshot()
                bandLevels = FloatArray(31) { i -> snap.getOrElse(i) { 0 }.toFloat() }
                bassBoost = engine.currentBassBoostValue().coerceIn(0, 300)
                virtualizer = engine.currentVirtualizerValue().coerceIn(0, 300)
                loudness = engine.currentLoudnessValue().coerceIn(0, 300)
            }
            appProfiles = engine.listAppProfiles()
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
            if (showGlow) BreathingGlow(active = enabled)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Gradient wordmark — the signature of the modernized header
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(brush = Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF))))) {
                                append("NEON EQ")
                            }
                        },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF7C4DFF).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.4f), CircleShape)
                            .clickable { showSettings = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚙", fontSize = 16.sp, color = Color(0xFFB39DFF))
                    }
                }
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
        if (showVisualizer) {
            NeonCard {
                VisualizerBars(waveform = waveform, waveformAt = waveformAt, active = enabled, style = visStyle)
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── Presets ──
        NeonCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GradientText("PRESETS", 11.sp, Brush.horizontalGradient(listOf(Color(0xFF7C4DFF), Color(0xFF00E5FF))))
            Row {
                Text(
                    "↺ Reset All",
                    fontSize = 11.sp,
                    color = Color(0xFFFF4081),
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFFFF4081).copy(alpha = 0.10f))
                        .clickable {
                        animateLevelsTo(FloatArray(31) { 0f })
                        selectedPreset = "Flat"
                        engine.setSelectedPresetName("Flat")
                        bassBoost = 0; virtualizer = 0; loudness = 0
                        engine.applyFullState(ShortArray(31) { 0 }, 0, 0, 0, smooth = true)
                    }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "+ Save",
                    fontSize = 11.sp,
                    color = Color(0xFF00E5FF),
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF00E5FF).copy(alpha = 0.10f))
                        .clickable {
                        presetNameInput = ""
                        showSaveDialog = true
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "↥ Share",
                    fontSize = 11.sp,
                    color = Color(0xFF00E5FF),
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF00E5FF).copy(alpha = 0.10f))
                        .clickable {
                        val json = engine.exportCustomPresets()
                        if (customPresets.isEmpty()) {
                            scope2.launch { snackbarHost.showSnackbar("No custom presets to share") }
                        } else {
                            try {
                                val file = File(context.cacheDir, "neoneq_presets.json")
                                file.writeText(json)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Share presets"))
                            } catch (_: Throwable) {
                                scope2.launch { snackbarHost.showSnackbar("Share failed") }
                            }
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "↧ Import",
                    fontSize = 11.sp,
                    color = Color(0xFF00E5FF),
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF00E5FF).copy(alpha = 0.10f))
                        .clickable {
                        importJsonInput = ""
                        importResultMsg = ""
                        showImportDialog = true
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Presets.presets, key = { "b_" + it.name }) { preset ->
                PresetChip(
                    preset = preset,
                    selected = selectedPreset == preset.name,
                    onClick = {
                        selectedPreset = preset.name
                        engine.setSelectedPresetName(preset.name)
                        val levels = Presets.levelsForCount(preset, bandCount)
                        val newLevels = FloatArray(31) { 0f }
                        levels.forEachIndexed { i, lvl -> newLevels[i] = lvl.toFloat() }
                        animateLevelsTo(newLevels)
                        // Build #69: ONE coordinated hardware transition; built-ins
                        // leave the effect sliders at their current values.
                        engine.applyFullState(
                            ShortArray(31) { i -> round(newLevels[i]).toInt().toShort() },
                            bassBoost, virtualizer, loudness, smooth = true)
                    }
                )
            }
            items(customPresets, key = { "c_" + it.name }) { preset ->
                CustomPresetChip(
                    preset = preset,
                    selected = selectedPreset == preset.name,
                    onClick = {
                        selectedPreset = preset.name
                        engine.setSelectedPresetName(preset.name)
                        val levels = Presets.levelsForCount(preset, bandCount)
                        val newLevels = FloatArray(31) { 0f }
                        levels.forEachIndexed { i, lvl -> newLevels[i] = lvl.toFloat() }
                        animateLevelsTo(newLevels)
                        bassBoost = preset.bassBoost
                        virtualizer = preset.virtualizer
                        loudness = preset.loudness
                        engine.applyFullState(
                            ShortArray(31) { i -> round(newLevels[i]).toInt().toShort() },
                            preset.bassBoost, preset.virtualizer, preset.loudness, smooth = true)
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
        }

        Spacer(Modifier.height(16.dp))

        // ── Band count selector ──
        NeonCard {
            GradientText("BANDS", 11.sp, Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF))))
            Spacer(Modifier.height(6.dp))
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
                        engine.applyFullState(ShortArray(31) { 0 }, bassBoost, virtualizer, loudness, smooth = true)
                    },
                    label = { Text("$count", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.2f),
                        selectedLabelColor = Color(0xFF00E5FF)
                    )
                )
            }
        }
        }

        Spacer(Modifier.height(20.dp))

        // ── Canvas-based EQ — ONE composable, no Slider widgets ──
        val bandList = bands.take(bandCount)

        NeonCard {
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
                engine.setSelectedPresetName("Custom")
            },
            onResetBand = { band ->
                val newLevels = bandLevels.copyOf()
                newLevels[band] = 0f
                bandLevels = newLevels
                engine.setBandLevel(band, 0)
                selectedPreset = "Custom"
                engine.setSelectedPresetName("Custom")
            }
        )
        }

        Spacer(Modifier.height(16.dp))

        // ── Effect sliders ──
        NeonCard {
            GradientText("EFFECTS", 11.sp, Brush.horizontalGradient(listOf(Color(0xFFFF4081), Color(0xFF7C4DFF))))
            Spacer(Modifier.height(4.dp))
        EffectSlider("BASS BOOST", bassBoost, 0..300) { v ->
            bassBoost = v
            engine.setBassBoost(v)
        }
        EffectSlider("3D SOUND", virtualizer, 0..300) { v ->
            virtualizer = v
            engine.setVirtualizer(v)
        }
            EffectSlider("LOUDNESS", loudness, 0..300) { v ->
                loudness = v
                engine.setLoudness(v)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Per-app profiles ──
        NeonCard {
            GradientText("APP PROFILES", 11.sp, Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF))))
            Spacer(Modifier.height(6.dp))
            val pkg = playingApp
            if (pkg != null) {
                Text("♪ ${appLabel(pkg)}", fontSize = 12.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, maxLines = 1)
                val assigned = appProfiles[pkg]
                Text(
                    if (assigned != null) "Profile: $assigned" else "Tap a preset to assign a profile to this app",
                    fontSize = 10.sp, color = Color.Gray
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(Presets.presets, key = { "pa_" + it.name }) { p ->
                        AppProfileChip(p.name, assigned == p.name) {
                            engine.setAppProfile(pkg, p.name)
                            appProfiles = engine.listAppProfiles()
                        }
                    }
                    items(customPresets, key = { "pc_" + it.name }) { p ->
                        AppProfileChip(p.name, assigned == p.name) {
                            engine.setAppProfile(pkg, p.name)
                            appProfiles = engine.listAppProfiles()
                        }
                    }
                    if (assigned != null) {
                        item(key = "pa_remove") {
                            AppProfileChip("Remove", false) {
                                engine.setAppProfile(pkg, null)
                                appProfiles = engine.listAppProfiles()
                            }
                        }
                    }
                }
            } else {
                Text("No app is playing audio right now — start music, then assign a profile.", fontSize = 10.sp, color = Color.Gray)
            }
            if (appProfiles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                appProfiles.forEach { (p, presetName) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${appLabel(p)} → $presetName", fontSize = 10.sp, color = Color.Gray, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(
                            "×",
                            fontSize = 14.sp,
                            color = Color(0xFFFF4081),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    engine.setAppProfile(p, null)
                                    appProfiles = engine.listAppProfiles()
                                }
                                .padding(horizontal = 6.dp)
                        )
                    }
                }
            }
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
            containerColor = Color(0xFF12121F),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save current EQ as preset", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold) },
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
            containerColor = Color(0xFF12121F),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showOverwriteDialog = false },
            title = { Text("Overwrite preset?", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold) },
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
                    bassBoost = preset.bassBoost
                    virtualizer = preset.virtualizer
                    loudness = preset.loudness
                    engine.applyFullState(
                        ShortArray(31) { i -> round(newLevels[i]).toInt().toShort() },
                        preset.bassBoost, preset.virtualizer, preset.loudness, smooth = true)
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
                text = { Text("Duplicate") },
                onClick = {
                    val dupName = engine.duplicateCustomPreset(preset.name)
                    if (dupName.isNotEmpty()) {
                        customPresets = engine.listCustomPresets()
                        scope2.launch { snackbarHost.showSnackbar("Duplicated to '$dupName'") }
                    }
                    menuPreset = null
                }
            )
            DropdownMenuItem(
                text = { Text("Move left") },
                onClick = {
                    engine.moveCustomPreset(preset.name, -1)
                    customPresets = engine.listCustomPresets()
                    menuPreset = null
                }
            )
            DropdownMenuItem(
                text = { Text("Move right") },
                onClick = {
                    engine.moveCustomPreset(preset.name, 1)
                    customPresets = engine.listCustomPresets()
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
            containerColor = Color(0xFF12121F),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename preset", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold) },
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

    // ── Settings dialog ──
    if (showSettings) {
        AlertDialog(
            containerColor = Color(0xFF12121F),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showSettings = false },
            title = { Text("Settings", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Start on boot
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Start on Boot", fontSize = 14.sp, color = Color.White)
                            Text("Auto-start EQ after device reboot", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = startOnBoot,
                            onCheckedChange = {
                                startOnBoot = it
                                engine.setStartOnBoot(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.3f)
                            )
                        )
                    }
                    // Auto-apply last preset
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-apply Preset", fontSize = 14.sp, color = Color.White)
                            Text("Restore last preset on app launch", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = autoApplyPreset,
                            onCheckedChange = {
                                autoApplyPreset = it
                                engine.setAutoApplyPreset(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.3f)
                            )
                        )
                    }
                    // Show visualizer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Visualizer", fontSize = 14.sp, color = Color.White)
                            Text("Show live spectrum bars", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = showVisualizer,
                            onCheckedChange = {
                                showVisualizer = it
                                engine.setShowVisualizer(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.3f)
                            )
                        )
                    }
                    // Visualizer style picker
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Visualizer Style", fontSize = 14.sp, color = Color.White)
                        Text("Bars, wave or circle pulse", fontSize = 11.sp, color = Color.Gray)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("bars" to "Bars", "wave" to "Wave", "circle" to "Circle").forEach { (key, label) ->
                                val sel = visStyle == key
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    color = if (sel) Color(0xFF0D0D14) else Color(0xFF00E5FF),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (sel) Color(0xFF00E5FF) else Color(0xFF00E5FF).copy(alpha = 0.12f))
                                        .clickable {
                                            visStyle = key
                                            engine.setVisualizerStyle(key)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    // Show breathing glow
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Breathing Glow", fontSize = 14.sp, color = Color.White)
                            Text("Animated glow behind header", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = showGlow,
                            onCheckedChange = {
                                showGlow = it
                                engine.setShowGlow(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.3f)
                            )
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text(
                            "⇩ Backup",
                            fontSize = 12.sp,
                            color = Color(0xFF00E5FF),
                            modifier = Modifier.clickable {
                                try {
                                    val json = engine.exportFullBackup()
                                    val file = File(context.cacheDir, "neoneq_backup.json")
                                    file.writeText(json)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(share, "Backup Neon EQ"))
                                } catch (_: Throwable) {
                                    scope2.launch { snackbarHost.showSnackbar("Backup failed") }
                                }
                            }
                        )
                        Text(
                            "⇪ Restore",
                            fontSize = 12.sp,
                            color = Color(0xFF00E5FF),
                            modifier = Modifier.clickable {
                                restoreJsonInput = ""
                                restoreResultMsg = ""
                                showRestoreDialog = true
                            }
                        )
                    }
                    Text(
                        "Backup saves bands, effects, presets & settings",
                        fontSize = 9.sp,
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    // Build #69: live engine diagnostics — the on-device window
                    // into session attach (no adb on the Redmi 10C).
                    Text(
                        "ENGINE DIAGNOSTICS",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    var diagText by remember { mutableStateOf("starting…") }
                    LaunchedEffect(Unit) {
                        while (true) {
                            diagText = try { engine.diagnostics() } catch (t: Throwable) { "diag error: ${t.message}" }
                            delay(1000)
                        }
                    }
                    Text(
                        diagText,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                        color = Color(0xFF00E5FF),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Neon EQ v1.0 · Build #69",
                        fontSize = 10.sp,
                        color = Color(0xFF7C4DFF),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("Done") }
            }
        )
    }

    // ── Import dialog ──
    if (showImportDialog) {
        AlertDialog(
            containerColor = Color(0xFF12121F),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import presets", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Paste the shared preset JSON below:", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importJsonInput,
                        onValueChange = { importJsonInput = it },
                        label = { Text("JSON") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8
                    )
                    if (importResultMsg.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(importResultMsg, fontSize = 11.sp, color = Color(0xFF00E5FF))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val count = engine.importCustomPresets(importJsonInput.trim())
                    if (count > 0) {
                        customPresets = engine.listCustomPresets()
                        importResultMsg = "Imported $count preset(s)"
                        scope2.launch { snackbarHost.showSnackbar("Imported $count preset(s)") }
                        showImportDialog = false
                    } else {
                        importResultMsg = "No new presets found or invalid JSON"
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRestoreDialog) {
        AlertDialog(
            containerColor = Color(0xFF12121F),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Restore backup", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Paste a Neon EQ backup JSON to restore all settings. This replaces current bands, effects, presets and toggles.", fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = restoreJsonInput,
                        onValueChange = { restoreJsonInput = it },
                        label = { Text("Backup JSON") },
                        minLines = 3,
                        maxLines = 8
                    )
                    if (restoreResultMsg.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(restoreResultMsg, fontSize = 11.sp, color = Color(0xFFFF4081))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = engine.importFullBackup(restoreJsonInput.trim())
                    if (ok) {
                        // Re-sync all local UI state from the restored engine config
                        selectedPreset = engine.selectedPresetName
                        customPresets = engine.listCustomPresets()
                        bassBoost = engine.currentBassBoostValue().coerceIn(0, 300)
                        virtualizer = engine.currentVirtualizerValue().coerceIn(0, 300)
                        loudness = engine.currentLoudnessValue().coerceIn(0, 300)
                        startOnBoot = engine.isStartOnBoot()
                        autoApplyPreset = engine.isAutoApplyPreset()
                        showVisualizer = engine.isShowVisualizer()
                        showGlow = engine.isShowGlow()
                        val lv = engine.currentLevelsSnapshot()
                        animateLevelsTo(FloatArray(31) { i -> lv.getOrElse(i) { 0 }.toFloat() })
                        scope2.launch { snackbarHost.showSnackbar("Backup restored") }
                        showRestoreDialog = false
                    } else {
                        restoreResultMsg = "Invalid backup JSON"
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// Soft pulsing radial glow behind the header — brighter/faster when the EQ is on,
// dim and idle when off. Purely cosmetic, but this is the "alive" feeling that
// makes a neon UI actually feel neon instead of just colored.
// Reusable "glass card" container — the backbone of the modernized UI.
// Elevated tonal surface with a subtle vertical gradient and a hair-thin
// neon border, floating on the AMOLED black background.
@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xFF12121F), Color(0xFF0C0C15)))
            )
            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .padding(12.dp),
        content = content
    )
}

// Gradient section header — matches the wordmark language.
@Composable
fun GradientText(text: String, fontSize: androidx.compose.ui.unit.TextUnit, brush: Brush) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(brush = brush)) { append(text) }
        },
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp
    )
}

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
// denied, unsupported device, or nothing playing) — and since Build #69 also when
// the EQ toggle is OFF, or (Build #69) when capture data goes stale mid-playback
// for >1.5s while the engine's self-heal watchdog re-attaches a MIUI-killed
// capture. In both cases the stale buffer would render frozen; the idle pulse
// renders instead. `active` + waveform freshness gate which mode we render.
// Includes falling peak markers that decay slowly for a more "pro audio" look.
@Composable
fun VisualizerBars(waveform: ByteArray, waveformAt: Long = 0L, active: Boolean, style: String = "bars") {
    val barCount = 32
    val infinite = rememberInfiniteTransition(label = "idlePulse")
    val idlePhase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "idlePhase"
    )

    // Per-bar peak hold — each bar remembers its own decaying peak height.
    val peaks = remember { FloatArray(barCount) { 0f } }
    var tick by remember { mutableIntStateOf(0) }

    // ---- Build #69: zero steady-state allocations in the visualizer ----
    // This composable redraws EVERY frame (idle breathing + live waveform),
    // so every object below is created once and reused. The previous version
    // allocated per frame: wave = 2 Paths + 1 FloatArray + 3 brushes,
    // circle = 41 gradient brushes, bars = 32 positioned gradient brushes.
    val waveMainPath = remember { Path() }
    val waveMirrorPath = remember { Path() }
    val waveAmps = remember { FloatArray(64) }
    val waveBrush = remember { Brush.horizontalGradient(listOf(Color(0xFF7C4DFF), Color(0xFF00E5FF))) }
    val waveGlowColor = remember { Color(0xFF00E5FF).copy(alpha = 0.25f) }
    val waveMirrorColor = remember { Color(0xFF7C4DFF).copy(alpha = 0.15f) }
    val spokeBrush = remember { Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFF00E5FF))) }
    val coreBrush = remember { Brush.radialGradient(listOf(Color(0xFF7C4DFF), Color(0x007C4DFF))) }
    val barBrush = remember { Brush.verticalGradient(listOf(Color(0xFF7C4DFF), Color(0xFF00E5FF))) }
    val peakColor = remember { Color(0xFF00E5FF).copy(alpha = 0.7f) }

    // Drive peak decay at ~30fps — cheaper than recomposing the whole tree.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(33)
            for (i in 0 until barCount) peaks[i] = (peaks[i] - 0.015f).coerceAtLeast(0f)
            tick++
        }
    }

    Canvas(modifier = Modifier.fillMaxWidth().height(64.dp)) {
        // Extract the amplitude for a single logical bar — shared by all three
        // styles so they react identically to the same waveform data.
        // `live` = EQ on AND fresh capture data. The freshness check (Build #69)
        // closes the gap while the engine's self-heal watchdog re-attaches a
        // MIUI-killed capture: without it the stale buffer renders frozen for
        // up to ~4s mid-song. The idlePulse animation invalidates this Canvas
        // every frame, so the clock check re-evaluates continuously even when
        // recomposition has stopped (no waveform updates = nothing to recompose).
        val live = active && waveform.isNotEmpty() &&
            waveformAt > 0 && SystemClock.elapsedRealtime() - waveformAt < 1500
        fun ampFor(i: Int, count: Int): Float {
            if (live) {
                val chunk = waveform.size / count
                val startIdx = (i * chunk).coerceIn(0, waveform.size - 1)
                val endIdx = ((i + 1) * chunk).coerceIn(startIdx + 1, waveform.size)
                var sum = 0f
                for (j in startIdx until endIdx) {
                    val v = (waveform[j].toInt() and 0xFF) - 128
                    sum += kotlin.math.abs(v)
                }
                return ((sum / (endIdx - startIdx)) / 128f).coerceIn(0.03f, 1f)
            }
            val wave = kotlin.math.sin((i / count.toFloat() + idlePhase) * Math.PI * 2).toFloat()
            return (0.08f + 0.05f * wave).coerceIn(0.03f, 0.2f)
        }

        when (style) {
            "wave" -> {
                // Smooth glowing line traced through 64 sample points.
                // Build #69: paths, amp buffer and brushes are hoisted and
                // reset() per frame — the wave costs zero allocations now.
                val points = 64
                val stepX = size.width / (points - 1).toFloat()
                for (i in 0 until points) waveAmps[i] = ampFor(i, points)
                waveMainPath.reset()
                waveMirrorPath.reset()
                for (i in 0 until points) {
                    val amp = waveAmps[i]
                    val x = i * stepX
                    val y = size.height / 2f - (amp - 0.03f) * size.height * 0.8f
                    val ym = size.height / 2f + (amp - 0.03f) * size.height * 0.8f * 0.5f
                    if (i == 0) { waveMainPath.moveTo(x, y); waveMirrorPath.moveTo(x, ym) }
                    else { waveMainPath.lineTo(x, y); waveMirrorPath.lineTo(x, ym) }
                }
                // Glow underlay: same path, thicker and faint
                drawPath(
                    path = waveMainPath,
                    color = waveGlowColor,
                    style = Stroke(width = 7f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = waveMainPath,
                    brush = waveBrush,
                    style = Stroke(width = 3f, cap = StrokeCap.Round)
                )
                // Mirrored faint reflection for depth
                drawPath(
                    path = waveMirrorPath,
                    color = waveMirrorColor,
                    style = Stroke(width = 2f, cap = StrokeCap.Round)
                )
            }
            "circle" -> {
                // Radial pulse ring — 40 spokes around a core.
                val spokes = 40
                val cx = size.width / 2f
                val cy = size.height / 2f
                val coreR = size.height * 0.18f
                // Unpositioned brushes size themselves to the drawn geometry,
                // so one remembered brush covers every frame and every spoke.
                drawCircle(
                    brush = coreBrush,
                    radius = coreR * 1.6f, center = Offset(cx, cy)
                )
                for (i in 0 until spokes) {
                    val amp = ampFor(i, spokes)
                    val angle = (i / spokes.toFloat()) * Math.PI * 2
                    val inner = coreR + 3f
                    val outer = coreR + 3f + (size.height * 0.3f * amp)
                    val cosA = kotlin.math.cos(angle).toFloat()
                    val sinA = kotlin.math.sin(angle).toFloat()
                    drawLine(
                        brush = spokeBrush,
                        start = Offset(cx + cosA * inner, cy + sinA * inner),
                        end = Offset(cx + cosA * outer, cy + sinA * outer),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }
            }
            else -> {
        val slotWidth = size.width / barCount
        val barWidthPx = slotWidth * 0.6f
        val midY = size.height / 2f
        for (i in 0 until barCount) {
            val amp: Float = if (live) {
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

            // Update peak — only rises, decays over time
            if (barH > peaks[i]) peaks[i] = barH
            val peakH = peaks[i]

            // Main bar with gradient — one remembered unpositioned brush maps
            // to each bar's own rect, so the gradient still spans barH exactly.
            drawRoundRect(
                brush = barBrush,
                topLeft = Offset(x, midY - barH / 2f),
                size = Size(barWidthPx, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
            )

            // Peak marker — thin bright line at the decaying peak height
            if (peakH > barH + 4f) {
                drawRoundRect(
                    color = peakColor,
                    topLeft = Offset(x, midY - peakH / 2f),
                    size = Size(barWidthPx, 3f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                )
            }
        }
            }
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
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    // ---- Build #69: allocation-free hot path ----
    // The previous version created a new android.graphics.Paint for EVERY band
    // on EVERY frame (up to 31/frame at 60fps in 31-band mode) plus a fresh
    // gradient brush per band. Everything below is hoisted and reused, so the
    // steady-state draw allocates nothing measurable.
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val curvePath = remember { Path() }
    val centerLinePath = remember { Path() }
    val centerDash = remember(density) { with(density) { PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())) } }
    val bgColor = Color(0xFF1A1A2E)
    val bgDim = bgColor.copy(alpha = 0.45f)
    val barColors = remember { listOf(Color(0xFF7C4DFF), Color(0xFF00E5FF)) }
    val curveColors = remember { listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF)) }
    val curveBrush = remember { Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF))) }
    val curveGlow = Color(0xFF00E5FF).copy(alpha = 0.20f)
    val centerColor = Color(0xFF00E5FF).copy(alpha = 0.16f)
    val bubbleBg = Color(0xFF00E5FF).copy(alpha = 0.16f)
    val bubbleBorder = Color(0xFF00E5FF).copy(alpha = 0.55f)
    val bubbleText = android.graphics.Color.rgb(220, 248, 255)
    val grayLabel = android.graphics.Color.rgb(140, 140, 158)
    val grayDim = android.graphics.Color.rgb(88, 88, 102)
    val cyanLabel = android.graphics.Color.rgb(0, 229, 255)
    val cyanDim = android.graphics.Color.rgb(0, 145, 158)
    val topsX = remember { FloatArray(31) }
    val topsY = remember { FloatArray(31) }
    var activeBand by remember { mutableIntStateOf(-1) }

    // Frequency labels are static per band set — cache the strings once instead
    // of building them for every band on every frame.
    val freqLabels = remember(bands, bandCount) {
        Array(bandCount) { i ->
            val f = bands.getOrNull(i)?.freq
            when {
                f == null -> ""
                f >= 1000 -> "${f / 1000}k"
                else -> "$f"
            }
        }
    }

    val barWidthPx = with(density) { 10.dp.toPx() }
    val minHeightPx = with(density) { 3.dp.toPx() }
    val labelAreaPx = with(density) { 46.dp.toPx() }
    val cornerPx = with(density) { 3.dp.toPx() }
    val freqSizePx = with(density) { 11.sp.toPx() }
    val lvlSizePx = with(density) { 10.sp.toPx() }
    val curveGlowPx = with(density) { 6.dp.toPx() }
    val curvePx = with(density) { 2.dp.toPx() }
    val centerPx = with(density) { 1.dp.toPx() }
    val handlePx = with(density) { 4.dp.toPx() }

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
                var lastTick = Int.MIN_VALUE
                detectDragGestures(
                    onDragStart = { offset ->
                        val slotWidth = size.width / bandCount
                        val band = (offset.x / slotWidth).toInt().coerceIn(0, bandCount - 1)
                        val lvl = levelFromY(offset.y, trackHeight)
                        onLevelChange(band, lvl)
                        activeBand = band
                        lastTick = round(lvl).toInt()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = { activeBand = -1 },
                    onDragCancel = { activeBand = -1 },
                    onDrag = { change, _ ->
                        val slotWidth = size.width / bandCount
                        val band = (change.position.x / slotWidth).toInt().coerceIn(0, bandCount - 1)
                        val lvl = levelFromY(change.position.y, trackHeight)
                        onLevelChange(band, lvl)
                        activeBand = band
                        // Fine-tuning haptic: a subtle tick as the band crosses
                        // each integer dB step — you can feel the steps without looking.
                        val tickAt = round(lvl).toInt()
                        if (tickAt != lastTick) {
                            lastTick = tickAt
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
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

        // 0 dB dashed reference line across the track.
        val centerY = trackHeight / 2f
        centerLinePath.reset()
        centerLinePath.moveTo(0f, centerY)
        centerLinePath.lineTo(size.width, centerY)
        drawPath(centerLinePath, color = centerColor, style = Stroke(width = centerPx, pathEffect = centerDash))

        // ONE gradient brush spans the full track for all bars — purple at the
        // top, cyan at the bottom — instead of a fresh brush per band per frame.
        val barBrush = Brush.verticalGradient(barColors, 0f, trackHeight)

        for (i in 0 until bandCount) {
            val level = levels.getOrElse(i) { 0f }
            val normLevel = (level + 15f) / 30f
            val x = i * slotWidth + (slotWidth - barWidthPx) / 2f
            val barH = (trackHeight * normLevel).coerceAtLeast(minHeightPx)
            val y = trackHeight - barH

            topsX[i] = x + barWidthPx / 2f
            topsY[i] = y

            val dimmed = activeBand >= 0 && i != activeBand

            // Background bar — spans the FULL track, same rect touch mapping uses.
            drawRoundRect(
                color = if (dimmed) bgDim else bgColor,
                topLeft = Offset(x, 0f),
                size = Size(barWidthPx, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx)
            )

            // Gradient fill bar
            drawRoundRect(
                brush = barBrush,
                topLeft = Offset(x, y),
                size = Size(barWidthPx, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx)
            )

            // Frequency + level labels, drawn in the reserved label area below the track.
            drawIntoCanvas {
                labelPaint.textSize = freqSizePx
                labelPaint.color = if (dimmed) grayDim else grayLabel
                it.nativeCanvas.drawText(
                    freqLabels.getOrElse(i) { "" },
                    x + barWidthPx / 2f,
                    trackHeight + labelAreaPx * 0.45f,
                    labelPaint
                )
                labelPaint.textSize = lvlSizePx
                labelPaint.color = if (dimmed) cyanDim else cyanLabel
                it.nativeCanvas.drawText(
                    "${round(level).toInt()}",
                    x + barWidthPx / 2f,
                    trackHeight + labelAreaPx * 0.85f,
                    labelPaint
                )
            }
        }

        // Smooth neon curve traced through the band tops — quadratic beziers with
        // band peaks as control points, glowing wide underlay + crisp gradient on top.
        if (bandCount > 1) {
            curvePath.reset()
            curvePath.moveTo(topsX[0], topsY[0])
            for (i in 1 until bandCount - 1) {
                val midX = (topsX[i] + topsX[i + 1]) / 2f
                val midY = (topsY[i] + topsY[i + 1]) / 2f
                curvePath.quadraticBezierTo(topsX[i], topsY[i], midX, midY)
            }
            curvePath.lineTo(topsX[bandCount - 1], topsY[bandCount - 1])
            drawPath(curvePath, color = curveGlow, style = Stroke(width = curveGlowPx, cap = StrokeCap.Round))
            drawPath(curvePath, brush = curveBrush, style = Stroke(width = curvePx, cap = StrokeCap.Round))
        }

        // Active-band emphasis: glow halo + handle dot + floating value bubble.
        if (activeBand in 0 until bandCount) {
            val cx = topsX[activeBand]
            val topY = topsY[activeBand]
            val glowR = barWidthPx * 2.2f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF00E5FF).copy(alpha = 0.35f), Color(0x0000E5FF)),
                    center = Offset(cx, topY),
                    radius = glowR
                ),
                radius = glowR,
                center = Offset(cx, topY)
            )
            drawCircle(color = Color(0xFF00E5FF), radius = handlePx, center = Offset(cx, topY))

            val lvl = round(levels.getOrElse(activeBand) { 0f }).toInt()
            val text = (if (lvl > 0) "+$lvl" else "$lvl") + " dB"
            labelPaint.textSize = lvlSizePx
            labelPaint.color = bubbleText
            val textW = labelPaint.measureText(text)
            val bubbleH = lvlSizePx * 1.9f
            val bubbleW = textW + bubbleH
            val bx = (cx - bubbleW / 2f).coerceIn(0f, size.width - bubbleW)
            val by = (topY - bubbleH - handlePx - 6f).coerceAtLeast(0f)
            drawRoundRect(
                color = bubbleBg,
                topLeft = Offset(bx, by),
                size = Size(bubbleW, bubbleH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bubbleH / 2f, bubbleH / 2f)
            )
            drawRoundRect(
                color = bubbleBorder,
                topLeft = Offset(bx, by),
                size = Size(bubbleW, bubbleH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bubbleH / 2f, bubbleH / 2f),
                style = Stroke(width = 1f)
            )
            drawIntoCanvas {
                it.nativeCanvas.drawText(
                    text,
                    bx + bubbleW / 2f,
                    by + bubbleH / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f,
                    labelPaint
                )
            }
        }
    }
}

// Built-in preset chip with a mini EQ curve preview sparkline.
// Small pill used to assign a preset to the currently playing app.
@Composable
fun AppProfileChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Text(
        label,
        fontSize = 10.sp,
        color = if (selected) Color(0xFF00E5FF) else Color.Gray,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF1A1A2E))
            .border(
                1.dp,
                if (selected) Color(0xFF00E5FF).copy(alpha = 0.5f) else Color(0xFF00E5FF).copy(alpha = 0.12f),
                RoundedCornerShape(50)
            )
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
fun PresetChip(preset: Presets.Preset, selected: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF1A1A2E)
            )
            .border(
                1.dp,
                if (selected) Color(0xFF00E5FF).copy(alpha = 0.6f) else Color(0xFF00E5FF).copy(alpha = 0.12f),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Mini sparkline preview — 24px tall, draws the EQ curve shape
        // Hoisted out of the draw loop — the old version allocated two Color
        // objects per band per frame while the row scrolled (31 bands x N chips).
        val thumbColor = if (selected) Color(0xFF00E5FF).copy(alpha = 0.8f) else Color(0xFF7C4DFF).copy(alpha = 0.5f)
        Canvas(modifier = Modifier.width(60.dp).height(24.dp)) {
            val levels = preset.levels
            val n = 31
            val slotW = size.width / n
            val midY = size.height / 2f
            val maxLevel = 15f
            for (i in 0 until n) {
                val lvl = levels.getOrElse(i) { 0 }.toFloat()
                val normY = (lvl / maxLevel).coerceIn(-1f, 1f)
                val barH = size.height * 0.45f * kotlin.math.abs(normY)
                val y = if (normY >= 0) midY - barH else midY
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(i * slotW, y),
                    size = Size(slotW * 0.7f, barH.coerceAtLeast(1f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f, 1f)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(preset.name, fontSize = 10.sp, color = if (selected) Color(0xFF00E5FF) else Color.Gray, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomPresetChip(
    preset: Presets.CustomPreset,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onDelete: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) Color(0xFF7C4DFF).copy(alpha = 0.2f) else Color(0xFF1A1A2E)
            )
            .border(
                1.dp,
                if (selected) Color(0xFF7C4DFF).copy(alpha = 0.6f) else Color(0xFF7C4DFF).copy(alpha = 0.15f),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 4.dp)
    ) {
        // Mini sparkline preview for custom preset
        Canvas(modifier = Modifier.width(40.dp).height(24.dp)) {
            val levels = preset.levels
            val n = 31
            val slotW = size.width / n
            val midY = size.height / 2f
            val maxLevel = 15f
            for (i in 0 until n) {
                val lvl = levels.getOrElse(i) { 0 }.toFloat()
                val normY = (lvl / maxLevel).coerceIn(-1f, 1f)
                val barH = size.height * 0.45f * kotlin.math.abs(normY)
                val y = if (normY >= 0) midY - barH else midY
                drawRoundRect(
                    color = if (selected) Color(0xFF7C4DFF).copy(alpha = 0.8f) else Color(0xFF7C4DFF).copy(alpha = 0.4f),
                    topLeft = Offset(i * slotW, y),
                    size = Size(slotW * 0.7f, barH.coerceAtLeast(1f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f, 1f)
                )
            }
        }
        Text(
            preset.name,
            fontSize = 10.sp,
            color = if (selected) Color(0xFF7C4DFF) else Color.Gray,
            modifier = Modifier
                .combinedClickable(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    },
                    onLongClick = onLongPress
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            maxLines = 1
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
        Text(
            "$value",
            fontSize = 11.sp,
            color = Color(0xFFFF4081),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFFF4081).copy(alpha = 0.10f))
                .border(1.dp, Color(0xFFFF4081).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .padding(vertical = 2.dp)
        )
    }
}

@Composable
fun CrashScreen(trace: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
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
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap Copy to clipboard if you can't screenshot.",
            fontSize = 11.sp, color = Color(0xFF7C4DFF)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            trace,
            fontSize = 10.sp,
            color = Color(0xFF00E5FF),
            modifier = Modifier
                .background(Color(0xFF0D0D14))
                .border(1.dp, Color(0xFFFF4081).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .padding(12.dp)
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDismiss) { Text("Dismiss & Retry") }
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NeonEQ crash log", trace))
                Toast.makeText(context, "Crash log copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
        }
    }
}
