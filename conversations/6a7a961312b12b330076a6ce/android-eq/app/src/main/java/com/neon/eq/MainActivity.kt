package com.neon.eq

import android.Manifest
import android.content.Intent
import androidx.compose.foundation.clickable
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neon.eq.engine.EQService
import com.neon.eq.engine.EqualizerEngine
import com.neon.eq.engine.Presets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import android.content.Context
import android.os.Process
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.neon.eq.capture.AudioCapabilityManager
import com.neon.eq.capture.AudioPath
import com.neon.eq.capture.CaptureEqService
import com.neon.eq.dsp.NeonDsp
import com.neon.eq.dsp.DspParams
import com.neon.eq.dsp.PeqSlot

private const val UI_PREFS = "ui_prefs"

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
        // Build #89: restore the persisted theme before the first frame.
        try {
            appThemeState.value = Themes.byId(
                getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE).getString("theme", null))
        } catch (_: Throwable) { }
        // Build #89: restore the persisted light/dark mode before the first frame.
        try {
            appModeState.value = SurfaceModes.byId(
                getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE).getString("mode", null))
        } catch (_: Throwable) { }
        // Build #127: restore glass intensity (UI rendering only).
        try {
            appGlassState.value = (
                getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE)
                    .getString("glass", "1")?.toIntOrNull() ?: 1).coerceIn(0, 2)
        } catch (_: Throwable) { }

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

// ── Build #89: app themes ──
// Three-slot palette: primary (sliders, borders, glow), secondary (gradient
// partner / dial cores), accent (badges, highlights). appThemeState is read
// throughout composition — swapping it recomposes the whole UI with the new
// palette. Selection persists in ui_prefs.
data class NeonTheme(
    val id: String,
    val label: String,
    val primary: Color,
    val secondary: Color,
    val accent: Color
)

object Themes {
    // Build #132: SonicCore default palette — electric cyan primary, violet
    // secondary, blue (not pink) as the third/supporting accent. Status
    // colors (green/yellow/red) live separately in StatusColors below so the
    // brand palette never gets reused to mean "error" or "warning".
    val CLASSIC = NeonTheme("classic", "SonicCore", Color(0xFF00D9FF), Color(0xFF8B5CF6), Color(0xFF3B82F6))
    val SYNTHWAVE = NeonTheme("synthwave", "Synthwave", Color(0xFFFF4FD8), Color(0xFF7C4DFF), Color(0xFF00E5FF))
    val EMBER = NeonTheme("ember", "Ember", Color(0xFFFF9500), Color(0xFFFF3D5A), Color(0xFFFFD54F))
    val EMERALD = NeonTheme("emerald", "Emerald", Color(0xFF00E676), Color(0xFF00BFA5), Color(0xFFB2FF59))
    val GLACIER = NeonTheme("glacier", "Glacier", Color(0xFF40C4FF), Color(0xFF5C6BC0), Color(0xFF80D8FF))
    val ALL = listOf(CLASSIC, SYNTHWAVE, EMBER, EMERALD, GLACIER)
    fun byId(id: String?): NeonTheme = ALL.firstOrNull { it.id == id } ?: CLASSIC
}

val appThemeState = mutableStateOf(Themes.CLASSIC)
private val T: NeonTheme get() = appThemeState.value

// Build #132: fixed status semantics — independent of the selectable brand
// palette, so "active/warning/error" always reads the same regardless of
// which NeonTheme the user picked.
object StatusColors {
    val success = Color(0xFF2ED573)   // active / verified
    val warn = Color(0xFFFFC107)      // limited / partial
    val error = Color(0xFFFF4757)     // error / blocked
    val inactive = Color(0xFF6B7280)  // gray / unavailable
}

// ── Build #89: light / dark mode ──
// Accent themes (NeonTheme) control the neon; this palette controls surfaces
// and text so the app can run dark (AMOLED, the classic look) or light.
// Persisted in ui_prefs as "mode"; toggled from the main screen header.
data class SurfacePalette(
    val bg: Color,
    val surface: Color,
    val card: Color,
    val cardAlt: Color,
    val cardDeep: Color,
    val borderDim: Color,
    val text: Color,
    val textSoft: Color
)

object SurfaceModes {
    // Build #132: SonicCore's background is a layered dark NAVY, not flat
    // AMOLED black — the reference's "professional console" feel comes from
    // depth (bg -> surface -> card -> cardAlt each a shade lighter), not
    // from pure black. Hue drifts toward blue (210°) instead of purple.
    val DARK = SurfacePalette(
        bg = Color(0xFF060A14), surface = Color(0xFF0A0F1D),
        card = Color(0xFF101828), cardAlt = Color(0xFF0C1220),
        cardDeep = Color(0xFF18213A), borderDim = Color(0xFF232E4A),
        text = Color.White, textSoft = Color(0xFFD8E0F5)
    )
    val LIGHT = SurfacePalette(
        bg = Color(0xFFF2F3F9), surface = Color(0xFFFCFDFF),
        card = Color(0xFFFFFFFF), cardAlt = Color(0xFFEFF1F8),
        cardDeep = Color(0xFFE4E8F2), borderDim = Color(0xFFC9CEE0),
        text = Color(0xFF151527), textSoft = Color(0xFF3C3C58)
    )
    fun byId(id: String?): SurfacePalette = if (id == "light") LIGHT else DARK
}

// ── Build #127: GLASS SYSTEM — dark glass + neon audio console ──
// Real-time blur on an AMOLED background is invisible under dark glass and
// costs GPU on low-end devices, so glass here is delivered efficiently via
// translucency, gradient hairline borders and top highlights. The glass
// intensity setting scales all three. No glass work ever runs outside the
// UI layer.
@Composable
fun GlassBackground() {
    val glow = when (appGlassState.value) { 0 -> 0.030f; 2 -> 0.085f; else -> 0.055f }
    Box(modifier = Modifier.fillMaxSize()) {
        if (appModeState.value == SurfaceModes.DARK) {
            // extremely subtle atmospheric light fields — never bright, static
            Box(Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.55f)
                .background(Brush.radialGradient(listOf(T.primary.copy(alpha = glow), Color.Transparent))))
            Box(Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.55f)
                .background(Brush.radialGradient(listOf(T.secondary.copy(alpha = glow * 0.8f), Color.Transparent))))
            Box(Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.5f)
                .background(Brush.radialGradient(listOf(T.accent.copy(alpha = glow * 0.6f), Color.Transparent))))
        }
    }
}

// Reusable glass status chip — icon + text, never color-only.
@Composable
fun StatusChip(label: String, kind: Int, dot: Boolean = true) {
    // Build #132: semantic status colors — green/amber/red/gray stay fixed
    // regardless of the selected brand palette (spec §5).
    val (col, glyph) = when (kind) {
        0 -> StatusColors.success to "●"   // ACTIVE
        2 -> StatusColors.error to "⚠"    // ERROR
        1 -> StatusColors.inactive to "○" // muted / bypass
        else -> StatusColors.inactive to "·"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(S.cardDeep.copy(alpha = 0.55f))
            .border(1.dp, col.copy(alpha = 0.25f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = label }
    ) {
        if (dot) Text(glyph, fontSize = 8.sp, color = col)
        Text(label, fontSize = 9.sp, color = col,
            modifier = if (dot) Modifier.padding(start = 4.dp) else Modifier)
    }
}

val appModeState = mutableStateOf(SurfaceModes.DARK)
// Build #127: glass intensity — UI rendering ONLY. Never touches DSP, capture,
// buffers, latency or sample rate. 0 LOW · 1 MEDIUM (default) · 2 HIGH.
val appGlassState = mutableStateOf(1)
fun glassSurfaceAlpha(): Float = when (appGlassState.value) { 0 -> 0.96f; 2 -> 0.74f; else -> 0.86f }
fun glassBorderAlpha(): Float = when (appGlassState.value) { 0 -> 0.08f; 2 -> 0.22f; else -> 0.14f }
private val S: SurfacePalette get() = appModeState.value

@Composable
fun NeonEQTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = T.primary,
            secondary = T.secondary,
            tertiary = T.accent,
            background = S.bg,
            surface = S.surface,
            onPrimary = Color.Black,
            onSurface = S.textSoft
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
    var noiseGate by remember { mutableStateOf(engine.isNoiseGate()) }
    var limiterOn by remember { mutableStateOf(engine.isLimiter()) }
    var limiterThr by remember { mutableStateOf(engine.limiterThresholdValue()) }
    var selectedPreset by remember { mutableStateOf(engine.selectedPresetName) }
    var customPresets by remember { mutableStateOf(engine.listCustomPresets()) }
    var showResetDialog by remember { mutableStateOf(false) }
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
    // Build #89: timestamp of the last capture delivery — lets the visualizer
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
    // Build #89: PackageManager label lookups are binder calls — cache them
    // instead of hitting PackageManager on every profile-row recomposition.
    val appLabelCache = remember { HashMap<String, String>() }
    fun appLabel(pkg: String): String = appLabelCache.getOrPut(pkg) {
        try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) { pkg }
    }

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
            modifier = Modifier.fillMaxSize().background(S.bg),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = T.primary)
                Spacer(Modifier.height(16.dp))
                Text(statusMsg, fontSize = 12.sp, color = Color.Gray)
            }
        }
        return
    }

    // ── Build #123: lightweight undo/redo — complete validated DSP
    // configurations only, each action submitted through the existing
    // atomic parameter-target system. Never raw audio, max 30 entries. ──
    data class DspSnap(val params: DspParams, val levels: FloatArray, val bass: Int, val virt: Int, val loud: Int, val label: String)
    val undoStack = remember { mutableStateListOf<DspSnap>() }
    val redoStack = remember { mutableStateListOf<DspSnap>() }
    var lastUndoAt by remember { mutableStateOf(0L) }
    fun captureSnap(label: String): DspSnap? = try {
        DspSnap(DspParams.load(engine), bandLevels.copyOf(), bassBoost, virtualizer, loudness, label)
    } catch (t: Throwable) { null }
    fun pushUndo(force: Boolean = false, label: String = "DSP change") {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastUndoAt < 1200L) return
        lastUndoAt = now
        captureSnap(label)?.let {
            undoStack.add(it)
            if (undoStack.size > 30) undoStack.removeAt(0)
            redoStack.clear()
        }
    }
    fun applySnap(s: DspSnap) {
        try {
            s.params.applyTo(NeonDsp)
            s.params.save(engine)
            // NOTE: do NOT assign bandLevels here — animateLevelsTo() must
            // capture the PRE-transition levels as its animation start.
            val lv = ShortArray(31) { i -> round(s.levels.getOrElse(i) { 0f }).toInt().toShort() }
            bassBoost = s.bass; virtualizer = s.virt; loudness = s.loud
            engine.applyFullState(lv, s.bass, s.virt, s.loud, smooth = true)
            animateLevelsTo(s.levels.copyOf())
        } catch (_: Throwable) { }
    }
    fun undoDsp() {
        if (undoStack.isNotEmpty()) {
            captureSnap("redo")?.let { redoStack.add(it) }
            applySnap(undoStack.removeAt(undoStack.size - 1))
        }
    }
    fun redoDsp() {
        if (redoStack.isNotEmpty()) {
            captureSnap("undo")?.let { undoStack.add(it) }
            applySnap(redoStack.removeAt(redoStack.size - 1))
        }
    }

    // ── Build #124: preset favorites / recent / search (UI-only) ──
    val favPrefs = remember { context.getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE) }
    // Build #130: analyzer settings — ONE shared source of truth. The SPECTRUM
    // card controls and the SETTINGS & PRESETS ANALYZER group both read/write
    // these. Pure UI rendering state — never the real-time audio path.
    var spFps by remember { mutableStateOf(favPrefs.getInt("an_fps", 30).coerceIn(5, 60)) }
    var spHoldMode by remember { mutableStateOf(if (favPrefs.getBoolean("an_hold", true)) 1 else 0) }
    // v142: hoisted analyzer bins — ONE poll (the visualizer's tick loop)
    // feeds both the big analyzer and the EQ-graph overlay. No second
    // observer, no second native readback.
    var spBars by remember { mutableStateOf(FloatArray(48)) }
    var spHold by remember { mutableStateOf(FloatArray(48)) }
    // v143: FOCUS EQ — immersive EQ workspace; STUDIO & SYSTEM collapsed by default.
    var focusEq by remember { mutableStateOf(false) }
    var advOpen by remember { mutableStateOf(false) }
    var spSmoothAmt by remember { mutableStateOf(when (favPrefs.getInt("an_smooth", 1)) { 0 -> 0.35f; 2 -> 0.75f; else -> 0.55f }) }
    var spFrozen by remember { mutableStateOf(false) }
    var presetFavs by remember { mutableStateOf(favPrefs.getStringSet("preset_favs", emptySet<String>()) ?: emptySet()) }
    var presetRecent by remember { mutableStateOf(favPrefs.getString("preset_recent", null)?.split("|")?.filter { it.isNotBlank() } ?: emptyList()) }
    var presetFilter by remember { mutableStateOf(0) } // 0 ALL · 1 FAVORITES · 2 RECENT · 3 CUSTOM
    var presetSearch by remember { mutableStateOf("") }
    fun toggleFav(name: String) {
        val s = presetFavs.toMutableSet()
        if (!s.remove(name)) s.add(name)
        presetFavs = s
        try { favPrefs.edit().putStringSet("preset_favs", s).apply() } catch (_: Throwable) { }
    }
    fun markRecent(name: String) {
        val r = (listOf(name) + presetRecent.filter { it != name }).take(8)
        presetRecent = r
        try { favPrefs.edit().putString("preset_recent", r.joinToString("|")).apply() } catch (_: Throwable) { }
    }

    // ── Build #123: preset import/export + compare states ──
    var showCompareDialog by remember { mutableStateOf(false) }
    // Build #129: preset-manager state is shared between the EQ tab (preview
    // dialogs) and the SETTINGS & PRESETS tab — declared once, at top level.
    var previewPresetName by remember { mutableStateOf<String?>(null) }
    // Build #130: EQ preset picker sheet (a picker, NOT a second manager)
    var showPresetPicker by remember { mutableStateOf(false) }
    var showSessionCompare by remember { mutableStateOf(false) }
    var showImportPreset by remember { mutableStateOf(false) }
    var importPresetInput by remember { mutableStateOf("") }
    var showGuide by remember { mutableStateOf(false) }
    fun buildPresetJson(): String {
        val p = try { DspParams.load(engine) } catch (_: Throwable) { DspParams() }
        val o = JSONObject()
        o.put("neoneq_preset", 1)
        o.put("preamp", p.preamp.toDouble()); o.put("bass", p.bass.toDouble()); o.put("treble", p.treble.toDouble())
        o.put("width", p.width.toDouble()); o.put("balance", p.balance.toDouble())
        o.put("mono", p.mono); o.put("swap", p.swap)
        o.put("compOn", p.compOn); o.put("compThresh", p.compThresh.toDouble())
        o.put("limiterOn", p.limiterOn); o.put("limThresh", p.limThresh.toDouble())
        val arr = JSONArray()
        for (i in 0 until 31) arr.put(round(bandLevels.getOrElse(i) { 0f }).toInt())
        o.put("levels", arr)
        o.put("bassBoost", bassBoost); o.put("virtualizer", virtualizer); o.put("loudness", loudness)
        return o.toString()
    }

    val dsp = remember { SoftwareEq() }
    val player = remember { SoftEqPlayer(dsp) }
    val tone = remember { TonePlayer(dsp) }
    var toneOn by remember { mutableStateOf(false) }

    // Build #132: universal landscape sidebar — ALL five tabs (not just
    // HOME) get Sidebar | Content on landscape/tablet, matching the
    // SonicCore console layout. mainContent is the exact same Column that
    // always rendered here; only its wrapper changes with orientation.
    // movableContentOf: when the orientation flips, the whole Column MOVES
    // between the portrait and landscape parents — every remember{} inside
    // (selected band, open sheets, scroll position) survives the move.
    val mainContent = remember { movableContentOf {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color.Transparent)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Build #100: limited-mode banner — when the device's audio policy
        // blocks the EQ engine entirely (Vivo V2553: Error -3), say it plainly
        // instead of letting the EQ silently look broken. Loudness still
        // works there; presets still save for other devices.
        var limitedNow by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                limitedNow = engine.limitedMode()
                delay(2000)
            }
        }
        if (limitedNow) {
            Spacer(Modifier.height(10.dp))
            NeonCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠", fontSize = 16.sp, color = T.accent)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "LIMITED MODE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = T.accent
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "This device's audio policy blocks the EQ engine. Loudness still works — presets still save for your other devices.",
                            fontSize = 10.sp,
                            color = T.secondary,
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }
        // ── Header with breathing glow ──
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            if (showGlow) BreathingGlow(active = enabled)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Build #132: SC monogram + wordmark — the SonicCore identity.
                    SCMark(26.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(brush = Brush.horizontalGradient(listOf(T.primary, T.secondary)))) {
                                append("SONICCORE")
                            }
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    // Build #130: live system state — always visible, all tabs,
                    // authoritative runtime data only (never a fake state).
                    val hdrSt = when {
                        !NeonDsp.available -> "DSP ERROR" to 3
                        CaptureEqService.running && CaptureEqService.bypass -> "BYPASS" to 1
                        CaptureEqService.running && CaptureEqService.noEligiblePlayback -> "CAPTURE BLOCKED" to 2
                        CaptureEqService.running -> "DSP ACTIVE" to 0
                        android.os.Build.VERSION.SDK_INT >= 29 -> "WAITING" to 1
                        else -> "DSP READY" to 1
                    }
                    GlassChip(hdrSt.first, hdrSt.second, modifier = Modifier.padding(bottom = 2.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(T.secondary.copy(alpha = 0.15f))
                            .border(1.dp, T.secondary.copy(alpha = 0.4f), CircleShape)
                            .clickable { showSettings = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚙", fontSize = 16.sp, color = T.secondary)
                    }
                    Spacer(Modifier.width(10.dp))
                    // Build #89: light/dark mode toggle on the main screen
                    val uiCtx = LocalContext.current
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(T.accent.copy(alpha = 0.15f))
                            .border(1.dp, T.accent.copy(alpha = 0.4f), CircleShape)
                            .clickable {
                                val next = if (appModeState.value == SurfaceModes.DARK) SurfaceModes.LIGHT else SurfaceModes.DARK
                                appModeState.value = next
                                try {
                                    uiCtx.getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE)
                                        .edit().putString("mode", if (next == SurfaceModes.LIGHT) "light" else "dark").apply()
                                } catch (_: Throwable) { }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (appModeState.value == SurfaceModes.DARK) "☾" else "☀",
                            fontSize = 15.sp, color = T.accent)
                    }
                }
            }
        }
        Text("Professional Audio Processing", fontSize = 11.sp, color = T.secondary)
        Text(statusMsg, fontSize = 9.sp, color = T.secondary)

        Spacer(Modifier.height(14.dp))

        // Build #91: FxSound-style big central power button — replaces the
        // small header Switch as the primary control. Same enable path
        // (engine.setEnabled + onEnabledToggled), just impossible to miss.
        Box(contentAlignment = Alignment.Center) {
            if (showGlow) BreathingGlow(active = enabled)
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(
                        brush = if (enabled)
                            Brush.radialGradient(listOf(T.primary.copy(alpha = 0.35f), T.primary.copy(alpha = 0.06f)))
                        else
                            Brush.radialGradient(listOf(Color.Gray.copy(alpha = 0.14f), Color.Transparent))
                    )
                    .border(2.dp, if (enabled) T.primary else Color.Gray.copy(alpha = 0.3f), CircleShape)
                    .clickable {
                        val next = !enabled
                        enabled = next
                        engine.setEnabled(next)
                        if (context is MainActivity) context.onEnabledToggled(next)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("⏻", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = if (enabled) T.primary else Color.Gray)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Build #127: glass status chips — honest, icon + text ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dsState = when {
                !NeonDsp.available -> "DSP ERROR"
                CaptureEqService.bypass -> "DSP BYPASS"
                CaptureEqService.running -> "DSP ACTIVE"
                SoftwareEq.lastEngineLabel?.startsWith("KOTLIN") == true -> "KOTLIN FALLBACK"
                else -> "STANDBY"
            }
            StatusChip(dsState, if (dsState == "DSP ACTIVE") 0 else if (dsState == "DSP ERROR" || dsState == "KOTLIN FALLBACK") 2 else 1)
            Spacer(Modifier.width(6.dp))
            if (CaptureEqService.running) {
                StatusChip("CAPTURE ACTIVE", 0)
                Spacer(Modifier.width(6.dp))
            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                // capture API exists (Android 10+): honest waiting state
                StatusChip("WAITING FOR PLAYBACK", 1)
                Spacer(Modifier.width(6.dp))
            }
            StatusChip((CaptureEqService.captureSampleRate / 1000).toString() + " kHz", 3, dot = false)
        }
        Spacer(Modifier.height(6.dp))
        // Build #124: landscape audio console — two-column workspace,
        // never a stretched portrait layout
        // v143 §32: FOCUS EQ hides the technical dashboard; the compact
        // power/status header above always stays.
        if (!focusEq) {
        val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
        // ── Build #121: STATUS — the professional dashboard header ──
        NeonCard {
            val recentData = CaptureEqService.running &&
                (android.os.SystemClock.elapsedRealtime() - CaptureEqService.lastDataAt) < 2500L
            val dspChip = when {
                !NeonDsp.available -> "DSP ERROR ✗"
                CaptureEqService.running && CaptureEqService.bypass -> "DSP BYPASS ○"
                SoftwareEq.lastEngineLabel?.startsWith("KOTLIN") == true -> "DSP FALLBACK !"
                CaptureEqService.running && !CaptureEqService.bypass -> "DSP ACTIVE ✓"
                else -> "DSP READY ✓"
            }
            val dspColor = when {
                !NeonDsp.available -> T.accent
                CaptureEqService.running && !CaptureEqService.bypass -> T.primary
                else -> T.secondary
            }
            // Build #130: hero status — Display typography, glyph + text
            Text(dspChip, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = dspColor)
            val capLine = when {
                !CaptureEqService.running -> "READY — no capture session running"
                CaptureEqService.noEligiblePlayback -> "CAPTURE BLOCKED — the source application does not permit playback capture"
                !recentData -> "WAITING FOR PLAYBACK — play audio in another app"
                else -> "CAPTURE ACTIVE · " + CaptureEqService.captureSampleRate + " Hz · Stereo"
            }
            Text(capLine, fontSize = 11.sp, color = if (CaptureEqService.running && recentData && !CaptureEqService.noEligiblePlayback) T.primary else T.secondary)
            if (SoftwareEq.lastEngineLabel != null) {
                Text("PLAYER ENGINE: " + SoftwareEq.lastEngineLabel, fontSize = 9.sp, color = T.secondary)
            }
            Spacer(Modifier.height(4.dp))
            TechValue(
                AudioCapabilityManager.outputDeviceLine(context) + " · latency ~" + "%.0f".format(CaptureEqService.totalLatencyMs) + " ms · buffer " + CaptureEqService.bufferMode +
                    (CaptureEqService.lastBufferChange?.let { " (auto-changed " + it + ")" } ?: ""),
                fontSize = 10.sp
            )
            if (CaptureEqService.lastError != null) {
                Text("⚠ " + CaptureEqService.lastError!!, fontSize = 9.sp, color = T.accent)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Build #123: QUICK ACTIONS ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (CaptureEqService.bypass) "DSP ○" else "DSP ●", fontSize = 10.sp,
                color = if (CaptureEqService.bypass) T.accent else T.primary,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(T.primary.copy(alpha = 0.12f))
                    .clickable { CaptureEqService.bypass = !CaptureEqService.bypass }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .semantics { contentDescription = "Toggle DSP bypass" })
            Text("A/B", fontSize = 10.sp, color = T.secondary,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(T.secondary.copy(alpha = 0.12f))
                    .clickable { CaptureEqService.bypass = !CaptureEqService.bypass }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .semantics { contentDescription = "Toggle A/B bypass" })
            Text("RESET", fontSize = 10.sp, color = T.accent,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(T.accent.copy(alpha = 0.12f))
                    .clickable { showResetDialog = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .semantics { contentDescription = "Open reset confirmation" })
        }
        Spacer(Modifier.height(16.dp))

        // ── Build #123: NOW PROCESSING — technical status, no source metadata ──
        NeonCard {
            GradientText("NOW PROCESSING", 11.sp, Brush.horizontalGradient(listOf(T.accent, T.primary)))
            Spacer(Modifier.height(4.dp))
            val nowIn = if (NeonDsp.available) runCatching { NeonDsp.inRmsMs() }.getOrDefault(0) else 0
            val nowOut = if (NeonDsp.available) runCatching { NeonDsp.outRmsMs() }.getOrDefault(0) else 0
            fun dbS(ms: Int): String = if (ms > 0) "%.1f dB".format(20 * Math.log10(ms / 1000.0)) else "-∞"
            Text(
                (if (CaptureEqService.running) "Captured playback · " + CaptureEqService.captureSampleRate + " Hz · Stereo" else "Player / idle") +
                    "\n" + (if (CaptureEqService.bypass) "DSP BYPASS" else "DSP ACTIVE") +
                    " · Input " + dbS(nowIn) + " · Output " + dbS(nowOut),
                fontSize = 11.sp, color = T.primary, lineHeight = 15.sp
            )
            Text("Technical status of SonicCore's own pipeline. SonicCore does not read or control another app's media session.", fontSize = 8.sp, color = T.secondary)
        }
        Spacer(Modifier.height(8.dp))
        // Build #124: honest, expandable capture explanation
        var capExpOpen by remember { mutableStateOf(false) }
        Text(if (capExpOpen) "HOW CAPTURE WORKS ▴" else "HOW CAPTURE WORKS ▾", fontSize = 10.sp, color = T.accent,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(T.accent.copy(alpha = 0.10f))
                .clickable { capExpOpen = !capExpOpen }.padding(horizontal = 10.dp, vertical = 5.dp)
                .semantics { contentDescription = "Expand capture explanation" })
        if (capExpOpen) {
            Text(
                "SonicCore captures eligible Android playback, processes the captured PCM, and sends the processed copy to its own AudioTrack.\nAndroid may also continue playing the source application's original output.",
                fontSize = 9.sp, color = T.secondary, lineHeight = 13.sp
            )
        }
        Spacer(Modifier.height(16.dp))

        // ── Build #123: quick preset strip on the home dashboard ──
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(com.neon.eq.engine.Presets.BUILTIN_QUICK.size) { qi ->
                val (qname, _) = com.neon.eq.engine.Presets.BUILTIN_QUICK[qi]
                Text(
                    qname,
                    fontSize = 10.sp,
                    color = if (selectedPreset == qname) T.primary else T.secondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background((if (selectedPreset == qname) T.primary else T.secondary).copy(alpha = 0.12f))
                        .clickable {
                            pushUndo(true, "Preset: " + qname)
                            markRecent(qname)
                            val lv = com.neon.eq.engine.Presets.builtinForCount(qname, bandCount)
                            animateLevelsTo(FloatArray(31) { i -> (lv.getOrNull(i)?.toInt() ?: 0).toFloat() })
                            selectedPreset = qname
                            engine.setSelectedPresetName(qname)
                            engine.applyFullState(ShortArray(31) { i -> lv.getOrNull(i) ?: 0 }, bassBoost, virtualizer, loudness, smooth = true)
                            Toast.makeText(context, "Preset applied: " + qname, Toast.LENGTH_SHORT).show()
                        }
                        .semantics { contentDescription = "Apply preset " + qname }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // ── Build #121: METERS — measured L/R input + output, dBFS, peak hold ──
        NeonCard {
            GradientText("METERS", 11.sp, Brush.horizontalGradient(listOf(T.primary, T.secondary)))
            Spacer(Modifier.height(4.dp))
            Text("Measured from the native DSP path. Not a claim about what you physically hear.", fontSize = 8.sp, color = T.secondary)
            Spacer(Modifier.height(6.dp))
            var mTick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(120); mTick++ } }
            var mPeak by remember { mutableStateOf(FloatArray(8)) }
            var mRms by remember { mutableStateOf(FloatArray(4)) }
            var mDb by remember { mutableStateOf(FloatArray(4)) }
            fun msToDb(ms: Int): Float = if (ms > 0) (20.0 * Math.log10(ms / 1000.0)).toFloat() else -60f
            fun norm(db: Float): Float = ((db + 60f) / 60f).coerceIn(0f, 1f)
            LaunchedEffect(mTick) {
                if (mTick > 0 && NeonDsp.available) {
                    val v = intArrayOf(
                        NeonDsp.inLRmsMs(), NeonDsp.inRRmsMs(), NeonDsp.outLRmsMs(), NeonDsp.outRRmsMs(),
                        NeonDsp.inLPkMs(), NeonDsp.inRPkMs(), NeonDsp.outLPkMs(), NeonDsp.outRPkMs()
                    )
                    mDb = FloatArray(4) { i -> msToDb(v[i]) }
                    val rms = FloatArray(4) { i -> norm(msToDb(v[i])) }
                    mRms = rms
                    val pk = FloatArray(8) { i ->
                        val cur = if (i < 4) rms[i] else norm(msToDb(v[i + 4]))
                        maxOf(mPeak[i] - 0.012f, cur)
                    }
                    mPeak = pk
                }
            }
            val labels = listOf("INPUT L", "INPUT R", "OUTPUT L", "OUTPUT R")
            val clip = (if (NeonDsp.available) runCatching { NeonDsp.clipCount() }.getOrDefault(0L) else 0L) > 0
            Column {
                labels.forEachIndexed { i, label ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(label, fontSize = 9.sp, color = T.secondary, modifier = Modifier.width(64.dp))
                        Canvas(modifier = Modifier.weight(1f).height(12.dp).semantics { contentDescription = label + " level" }) {
                            val w = size.width
                            val h = size.height
                            drawRoundRect(color = T.secondary.copy(alpha = 0.15f), size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f))
                            if (mRms[i] > 0.001f) {
                                drawRoundRect(color = if (i < 2) T.primary else T.accent, size = androidx.compose.ui.geometry.Size(w * mRms[i], h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f))
                            }
                            val px = w * mPeak[i + 4]   // peaks live in slots 4..7, RMS in 0..3
                            drawLine(color = Color.White.copy(alpha = 0.8f), start = androidx.compose.ui.geometry.Offset(px, 0f), end = androidx.compose.ui.geometry.Offset(px, h), strokeWidth = 2f)
                            if (mRms[i] > 0.999f) {
                                drawLine(color = T.accent, start = androidx.compose.ui.geometry.Offset(w - 3f, 0f), end = androidx.compose.ui.geometry.Offset(w - 3f, h), strokeWidth = 3f)
                            }
                        }
                        Text(
                            (if (mDb[i] <= -60f) "-∞" else "%.1f dB".format(mDb[i])),
                            fontSize = 9.sp, color = T.secondary, modifier = Modifier.width(62.dp), textAlign = TextAlign.End
                        )
                    }
                }
            }
            if (clip) {
                Text("⚠ CLIPPING DETECTED — output peak reached 0 dBFS. Consider reducing Preamp or EQ gain. Settings are never changed automatically.", fontSize = 9.sp, color = T.accent)
            }
            // Build #123: measured gain headroom from the output peak
            val outPkMsNow = if (NeonDsp.available) runCatching { NeonDsp.outPeakMs() }.getOrDefault(0) else 0
            val headDb = if (outPkMsNow > 0) (20.0 * Math.log10(outPkMsNow / 1000.0)) else -60.0
            Text(
                "GAIN HEADROOM: " + (when {
                    headDb > -3.0 -> "LOW"
                    headDb > -8.0 -> "MODERATE"
                    else -> "GOOD"
                }) + " (output peak " + (if (outPkMsNow > 0) "%.1f dBFS".format(headDb) else "silent") + ") — measured, not a loudness prediction.",
                fontSize = 9.sp, color = if (headDb > -3.0) T.accent else T.secondary
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Build #121: SPECTRUM — real-time FFT of the live signal ──
        NeonCard {
            GradientText("SPECTRUM", 11.sp, Brush.horizontalGradient(listOf(T.accent, T.primary)))
            Spacer(Modifier.height(4.dp))
            var spTick by remember { mutableStateOf(0) }
            var spPost by remember { mutableStateOf(false) }
            var spChan by remember { mutableStateOf(0) }
            // Build #136: Phase-14 FPS tiers — full user rate while the app is
            // RESUMED, 8 FPS when backgrounded (the activity keeps composing
            // off-screen; on the Redmi 10C that CPU is needed elsewhere).
            // Capture + DSP run in the service and are NEVER throttled here.
            var spForeground by remember { mutableStateOf(true) }
            val spLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(spLifecycleOwner) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
                    spForeground = ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME
                }
                spLifecycleOwner.lifecycle.addObserver(obs)
                onDispose { spLifecycleOwner.lifecycle.removeObserver(obs) }
            }
            LaunchedEffect(spFps, spForeground) { while (true) { kotlinx.coroutines.delay(1000L / (if (spForeground) spFps.coerceAtLeast(5) else 8)); spTick++ } }
            val spActive = NeonDsp.available && runCatching { NeonDsp.inRmsMs() > 0 || NeonDsp.outRmsMs() > 0 }.getOrDefault(false)
            LaunchedEffect(spTick) {
                // Build #124: FREEZE stops visualization updates only —
                // capture and DSP keep running untouched.
                if (spTick > 0 && NeonDsp.available && spActive && !spFrozen) {
                    val cur = FloatArray(48)
                    try { NeonDsp.spectrum(cur, (if (spPost) 3 else 0) + spChan) } catch (_: Throwable) { }
                    val nb = spBars.copyOf()
                    for (i in 0 until 48) nb[i] = nb[i] * (1f - spSmoothAmt) + cur[i] * spSmoothAmt
                    spBars = nb
                    val nh = spHold.copyOf()
                    val decay = when (spHoldMode) { 1 -> 0.94f; 3 -> 0.992f; 5 -> 0.997f; else -> 1f }
                    for (i in 0 until 48) nh[i] = if (spHoldMode == 0) nb[i] else maxOf(nh[i] * decay, nb[i])
                    spHold = nh
                }
            }
            Canvas(modifier = Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = "Spectrum analyzer, 20 hertz to 20 kilohertz" }) {
                val w = size.width
                val h = size.height
                val topPad = 6f
                val barW = w / 48f
                for (g in 1..3) {
                    val y = topPad + (h - topPad) * g / 4f
                    drawLine(color = T.secondary.copy(alpha = 0.15f), start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(w, y), strokeWidth = 1f)
                }
                for (i in 0 until 48) {
                    val bh = (h - topPad) * spBars[i]
                    if (bh > 1f) {
                        drawRoundRect(
                            color = T.primary.copy(alpha = 0.9f),
                            topLeft = androidx.compose.ui.geometry.Offset(i * barW + 1f, h - bh),
                            size = androidx.compose.ui.geometry.Size(barW - 2f, bh),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                        )
                    }
                    if (spHoldMode > 0 && spHold[i] > 0.02f) {
                        val y = h - (h - topPad) * spHold[i]
                        drawLine(color = T.accent, start = androidx.compose.ui.geometry.Offset(i * barW + 1f, y), end = androidx.compose.ui.geometry.Offset((i + 1) * barW - 1f, y), strokeWidth = 1.5f)
                    }
                }
            }
            if (!spActive) {
                Text("no signal — play audio to see the spectrum", fontSize = 9.sp, color = T.secondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (spPost) "PROCESSED" else "SOURCE", fontSize = 9.sp, color = if (spPost) T.accent else T.primary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background((if (spPost) T.accent else T.primary).copy(alpha = 0.12f))
                        .clickable { spPost = !spPost }.padding(horizontal = 8.dp, vertical = 3.dp)
                        .semantics { contentDescription = if (spPost) "Show processed spectrum" else "Show source spectrum" })
                Spacer(Modifier.width(6.dp))
                Text("L+R", fontSize = 9.sp, color = if (spChan == 0) T.primary else T.secondary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background((if (spChan == 0) T.primary else T.secondary).copy(alpha = 0.12f))
                        .clickable { spChan = 0 }.padding(horizontal = 8.dp, vertical = 3.dp))
                Text("L", fontSize = 9.sp, color = if (spChan == 1) T.primary else T.secondary,
                    modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(50)).background((if (spChan == 1) T.primary else T.secondary).copy(alpha = 0.12f))
                        .clickable { spChan = 1 }.padding(horizontal = 8.dp, vertical = 3.dp))
                Text("R", fontSize = 9.sp, color = if (spChan == 2) T.primary else T.secondary,
                    modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(50)).background((if (spChan == 2) T.primary else T.secondary).copy(alpha = 0.12f))
                        .clickable { spChan = 2 }.padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("SMOOTHING", fontSize = 9.sp, color = T.secondary)
                Slider(
                    value = spSmoothAmt,
                    onValueChange = { spSmoothAmt = it },
                    valueRange = 0.2f..0.9f,
                    modifier = Modifier.weight(1f).padding(start = 8.dp, end = 8.dp).height(24.dp),
                    colors = SliderDefaults.colors(thumbColor = T.primary, activeTrackColor = T.primary)
                )
                Text(if (spFrozen) "FROZEN" else "", fontSize = 9.sp, color = T.accent)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PEAK HOLD", fontSize = 9.sp, color = T.secondary)
                listOf(0 to "OFF", 1 to "1s", 3 to "3s", 5 to "5s").forEach { (hm, hl) ->
                    Text(hl, fontSize = 9.sp, color = if (spHoldMode == hm) T.accent else T.secondary,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background((if (spHoldMode == hm) T.accent else T.secondary).copy(alpha = 0.12f))
                            .clickable { spHoldMode = hm }.padding(horizontal = 7.dp, vertical = 3.dp)
                            .semantics { contentDescription = "Peak hold " + hl })
                }
                Spacer(Modifier.width(8.dp))
                Text(if (spFrozen) "RESUME" else "FREEZE", fontSize = 9.sp, color = if (spFrozen) T.accent else T.primary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background((if (spFrozen) T.accent else T.primary).copy(alpha = 0.12f))
                        .clickable { spFrozen = !spFrozen }.padding(horizontal = 8.dp, vertical = 3.dp)
                        .semantics { contentDescription = if (spFrozen) "Resume analyzer" else "Freeze analyzer — visualization only, audio continues" })
                Spacer(Modifier.width(6.dp))
                listOf(30, 15, 8).forEach { f ->
                    Text("$f", fontSize = 9.sp, color = if (spFps == f) T.primary else T.secondary,
                        modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(50)).background((if (spFps == f) T.primary else T.secondary).copy(alpha = 0.12f))
                            .clickable { spFps = f }.padding(horizontal = 6.dp, vertical = 3.dp)
                            .semantics { contentDescription = "Analyzer " + f + " frames per second" })
                }
                Text("fps", fontSize = 8.sp, color = T.secondary)
            }
            Text(
                (if (spPost) "OUTPUT SPECTRUM = processed PCM (post-DSP)" else "INPUT SPECTRUM = captured PCM (pre-DSP)") +
                    " · 20 Hz – 20 kHz log · not the final physical speaker signal",
                fontSize = 8.sp, color = T.secondary
            )
            if (spFrozen) Text("ANALYZER FROZEN — visualization only. Capture and DSP continue normally.", fontSize = 8.sp, color = T.accent)
        }

        Spacer(Modifier.height(16.dp))

        // ── Build #121: OUTPUT — route, format, latency, buffer at a glance ──
        NeonCard {
            GradientText("OUTPUT", 11.sp, Brush.horizontalGradient(listOf(T.secondary, T.primary)))
            Spacer(Modifier.height(4.dp))
            val routeIcon = when {
                AudioCapabilityManager.btConnected(context) -> "◉ Bluetooth"
                AudioCapabilityManager.usbConnected(context) -> "▤ USB Audio"
                AudioCapabilityManager.wiredConnected(context) -> "♪ Wired Headphones"
                else -> "▷ Phone Speaker"
            }
            Text(routeIcon, fontSize = 13.sp, color = T.primary)
            Text(
                AudioCapabilityManager.suggestedSampleRate(context).toString() + " Hz · " + AudioCapabilityManager.channelsLine() +
                    " · buffer " + CaptureEqService.bufferMode + " · " + "%.0f".format(CaptureEqService.captureBufferMs) + " ms",
                fontSize = 10.sp, color = T.secondary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "LATENCY (estimated)\n" +
                "  capture      " + "%.1f".format(CaptureEqService.capLatencyMs) + " ms\n" +
                "  dsp          " + "%.1f".format(CaptureEqService.dspMs) + " ms\n" +
                "  output       " + "%.1f".format(CaptureEqService.outLatencyMs) + " ms\n" +
                "  pipeline     ~" + "%.0f".format(CaptureEqService.totalLatencyMs) + " ms",
                fontSize = 10.sp, color = T.secondary, lineHeight = 14.sp
            )
            Text("Estimates from buffer sizes and measured processing time — Android provides no exact end-to-end latency API.", fontSize = 8.sp, color = T.secondary)
            if (CaptureEqService.oldRouteLine != null) {
                Text("ROUTE CHANGED: " + CaptureEqService.oldRouteLine + " → " + AudioCapabilityManager.outputDeviceLine(context), fontSize = 9.sp, color = T.accent)
            }
        }

        Spacer(Modifier.height(16.dp))

        }
            Column(modifier = Modifier.weight(1f)) {
        // ── Build #121: QUICK DSP — collapsible atomic toggles (same engine, no parallel pipelines) ──
        NeonCard {
            var qcOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { qcOpen = !qcOpen }) {
                GradientText("QUICK DSP", 11.sp, Brush.horizontalGradient(listOf(T.primary, T.accent)))
                Spacer(Modifier.weight(1f))
                Text(if (qcOpen) "▲" else "▼", fontSize = 10.sp, color = T.secondary)
            }
            if (qcOpen) {
                Spacer(Modifier.height(4.dp))
                Text("Toggles map to the same native chain through atomic commits. A toggle stores your current value and restores it exactly.", fontSize = 8.sp, color = T.secondary)
                Spacer(Modifier.height(6.dp))
                var qcBase by remember { mutableStateOf<DspParams?>(null) }
                var qcEqBands by remember { mutableStateOf<FloatArray?>(null) }
                var qcDspOn by remember { mutableStateOf(true) }
                var qcEqOn by remember { mutableStateOf(true) }
                var qcBassOn by remember { mutableStateOf(true) }
                var qcTrebleOn by remember { mutableStateOf(true) }
                var qcStereoOn by remember { mutableStateOf(true) }
                var qcLimOn by remember { mutableStateOf(true) }
                fun qcPush() {
                    val base = qcBase ?: DspParams.load(engine).also { qcBase = it }
                    val p = DspParams()
                    p.preamp = base.preamp
                    p.bass = if (qcBassOn) base.bass else 0f
                    p.treble = if (qcTrebleOn) base.treble else 0f
                    p.width = if (qcStereoOn) base.width else 1f
                    p.balance = base.balance
                    p.mono = base.mono
                    p.swap = base.swap
                    p.compOn = base.compOn
                    p.compThresh = base.compThresh
                    p.limiterOn = qcLimOn
                    p.limThresh = base.limThresh
                    p.slots = if (qcEqOn) base.slots else List(8) { PeqSlot() }
                    p.applyTo(NeonDsp)
                    if (!qcEqOn) {
                        if (qcEqBands == null) qcEqBands = bandLevels.copyOf()
                        NeonDsp.setGraphicGains(FloatArray(engine.bandCount) { 0f })
                    } else if (qcEqBands != null) {
                        NeonDsp.setGraphicGains(qcEqBands!!)
                        qcEqBands = null
                    }
                }
                Row {
                    Text(if (qcDspOn) "DSP ✓ ON" else "DSP ○ OFF (capture bypass)", fontSize = 10.sp, color = if (qcDspOn) T.primary else T.accent,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(T.primary.copy(alpha = 0.10f)).clickable { qcDspOn = !qcDspOn; CaptureEqService.bypass = !qcDspOn }.padding(horizontal = 10.dp, vertical = 5.dp).semantics { contentDescription = "Toggle DSP" })
                    Text(if (qcEqOn) "EQ ✓" else "EQ ✗", fontSize = 10.sp, color = if (qcEqOn) T.primary else T.accent,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(T.primary.copy(alpha = 0.10f)).clickable { qcEqOn = !qcEqOn; qcPush() }.padding(horizontal = 10.dp, vertical = 5.dp).semantics { contentDescription = "Toggle EQ" })
                    Text(if (qcBassOn) "BASS ✓" else "BASS ✗", fontSize = 10.sp, color = if (qcBassOn) T.primary else T.accent,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(T.primary.copy(alpha = 0.10f)).clickable { qcBassOn = !qcBassOn; qcPush() }.padding(horizontal = 10.dp, vertical = 5.dp).semantics { contentDescription = "Toggle bass" })
                }
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Text(if (qcTrebleOn) "TREBLE ✓" else "TREBLE ✗", fontSize = 10.sp, color = if (qcTrebleOn) T.primary else T.accent,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(T.primary.copy(alpha = 0.10f)).clickable { qcTrebleOn = !qcTrebleOn; qcPush() }.padding(horizontal = 10.dp, vertical = 5.dp).semantics { contentDescription = "Toggle treble" })
                    Text(if (qcStereoOn) "STEREO ✓" else "STEREO ✗", fontSize = 10.sp, color = if (qcStereoOn) T.primary else T.accent,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(T.primary.copy(alpha = 0.10f)).clickable { qcStereoOn = !qcStereoOn; qcPush() }.padding(horizontal = 10.dp, vertical = 5.dp).semantics { contentDescription = "Toggle stereo width" })
                    Text(if (qcLimOn) "LIMITER ✓" else "LIMITER ✗", fontSize = 10.sp, color = if (qcLimOn) T.primary else T.accent,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(T.accent.copy(alpha = 0.10f)).clickable { qcLimOn = !qcLimOn; qcPush() }.padding(horizontal = 10.dp, vertical = 5.dp).semantics { contentDescription = "Toggle limiter" })
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Build #121: PERFORMANCE — compact live counters ──
        NeonCard {
            var perfTick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); perfTick++ } }
            GradientText("PERFORMANCE", 11.sp, Brush.horizontalGradient(listOf(T.secondary, T.accent)))
            Spacer(Modifier.height(4.dp))
            val clipN = if (NeonDsp.available) runCatching { NeonDsp.clipCount() }.getOrDefault(0L) else 0L
            val nanN = if (NeonDsp.available) runCatching { NeonDsp.nanCount() }.getOrDefault(0L) else 0L
            val sessSec = if (CaptureEqService.running && CaptureEqService.sessionStartedAt > 0)
                (android.os.SystemClock.elapsedRealtime() - CaptureEqService.sessionStartedAt) / 1000 else 0L
            val framesIn = CaptureEqService.framesCaptured
            val framesOut = CaptureEqService.framesDone
            Text(
                "DSP CPU " + "%.1f".format(CaptureEqService.dspLoadPct) + "% · loop " + "%.1f".format(CaptureEqService.loopMs) + " ms · session " + sessSec + "s\n" +
                "frames in " + framesIn + " · out " + framesOut + "\n" +
                "underruns " + CaptureEqService.underruns + " · clips " + clipN + " · NaN events " + nanN + "\n" +
                "route changes " + CaptureEqService.sessionRouteChanges + " · buffer changes " + CaptureEqService.sessionBufferChanges,
                fontSize = 10.sp, color = T.secondary, lineHeight = 14.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Build #121: AUDIO HEALTH — plain-language pass/fail, error codes in details ──
        NeonCard {
            GradientText("AUDIO HEALTH", 11.sp, Brush.horizontalGradient(listOf(T.primary, T.secondary)))
            Spacer(Modifier.height(4.dp))
            var healthDetail by remember { mutableStateOf(false) }
            val recentData = CaptureEqService.running &&
                (android.os.SystemClock.elapsedRealtime() - CaptureEqService.lastDataAt) < 2500L
            val hCap = when {
                !CaptureEqService.running -> "—"
                CaptureEqService.noEligiblePlayback -> "⚠ CAPTURE — source application does not permit capture"
                recentData -> "✓ CAPTURE"
                else -> "○ CAPTURE — waiting"
            }
            val hDsp = when {
                !NeonDsp.available -> "✗ DSP — native library unavailable"
                CaptureEqService.bypass -> "○ DSP — bypassed (A/B or quick toggle)"
                CaptureEqService.running && recentData -> "✓ DSP"
                else -> "✓ DSP — ready"
            }
            val hOut = if (CaptureEqService.framesDone > 0) "✓ OUTPUT" else "— OUTPUT"
            val hBuf = if (CaptureEqService.underruns > 0) "⚠ BUFFER — " + CaptureEqService.underruns + " underruns" else "✓ BUFFER"
            val hRoute = if (CaptureEqService.lastError?.startsWith("OUTPUT_ROUTE_CHANGED") == true) "⚠ ROUTE — rebuilding" else "✓ ROUTE"
            Text(hCap + "\n" + hDsp + "\n" + hOut + "\n" + hBuf + "\n" + hRoute, fontSize = 11.sp, color = T.secondary, lineHeight = 16.sp)
            if (CaptureEqService.lastError != null) {
                Text(
                    if (healthDetail) "VIEW DETAILS ▲" else "VIEW DETAILS ▼",
                    fontSize = 10.sp, color = T.accent,
                    modifier = Modifier.clickable { healthDetail = !healthDetail }.padding(vertical = 2.dp)
                )
                if (healthDetail) {
                    Text("Error layer: " + CaptureEqService.lastError, fontSize = 9.sp, color = T.accent)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        } // tab guard

        } // landscape columns
            } // landscape row
        }   // v143: dashboard hidden in FOCUS EQ


        var editBand by remember { mutableStateOf(-1) }
        var selBand by remember { mutableStateOf(-1) }
        var eqScaleMode by remember { mutableStateOf(0) } // 0 AUTO · 6 · 12 · 18
        var eqPrecision by remember { mutableStateOf(false) }
        var autoScale by remember { mutableStateOf(6f) }
        var eqAnalyzerMode by remember { mutableStateOf(0) } // 0 OFF · 1 SPECTRUM · 2 SPECT+CURVE
        val eqClipboard = remember { mutableStateOf<FloatArray?>(null) }   // v142 COPY/PASTE EQ
        var eqHold by remember { mutableStateOf(false) }   // v143 §8: spectrum peak-hold trace on the EQ overlay
        var gainEditBand by remember { mutableStateOf(-1) }
        var gainEditInput by remember { mutableStateOf("") }
        var showResetEqDialog by remember { mutableStateOf(false) }
        // v143 §28: cached levels of the LOADED preset (one allocation per
        // preset/band-count change, never per drag frame) for CUSTOMIZED check.
        val eqPresetLv = remember(selectedPreset, bandCount, customPresets) {
            try {
                val bp = Presets.presets.firstOrNull { it.name == selectedPreset }
                val cp = customPresets.firstOrNull { it.name == selectedPreset }
                when {
                    selectedPreset == "Custom" -> null
                    bp != null -> Presets.levelsForCount(bp, bandCount)
                    cp != null -> Presets.levelsForCount(cp, bandCount)
                    else -> null
                }
            } catch (_: Throwable) { null }
        }
        // ── v143: EQUALIZER header — title + FOCUS EQ (§32) ──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            GradientText("EQUALIZER", 16.sp, Brush.horizontalGradient(listOf(T.secondary, T.primary)))
            Spacer(Modifier.weight(1f))
            Text(
                if (focusEq) "✕ EXIT FOCUS" else "◎ FOCUS EQ", fontSize = 10.sp,
                color = if (focusEq) T.accent else T.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background((if (focusEq) T.accent else T.primary).copy(alpha = 0.14f))
                    .clickable { focusEq = !focusEq }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics { contentDescription = if (focusEq) "Exit Focus EQ mode" else "Enter Focus EQ — immersive full-screen EQ workspace" }
            )
        }
        // ── v143 §4: PRESET BAR — star, prev/next (measured preview), browser, save ──
        val presetOrder = remember(customPresets) { Presets.presets.map { it.name } + customPresets.map { it.name } }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (selectedPreset in presetFavs) "★" else "☆", fontSize = 14.sp,
                color = if (selectedPreset in presetFavs) T.accent else T.secondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        if (selectedPreset in Presets.presets.map { it.name } + customPresets.map { it.name }) toggleFav(selectedPreset)
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .semantics { contentDescription = (if (selectedPreset in presetFavs) "Unfavorite " else "Favorite ") + selectedPreset }
            )
            Text("‹", fontSize = 15.sp, color = T.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        val ni = com.neon.eq.engine.Presets.stepIndex(presetOrder.size, presetOrder.indexOf(selectedPreset), -1)
                        if (ni >= 0) previewPresetName = presetOrder[ni]
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .semantics { contentDescription = "Previous preset — opens measured preview" })
            Text(selectedPreset + " ▾", fontSize = 11.sp, color = T.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(T.primary.copy(alpha = 0.12f))
                    .clickable { showPresetPicker = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .semantics { contentDescription = "Select a preset — opens the preset picker" })
            Text("›", fontSize = 15.sp, color = T.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        val ni = com.neon.eq.engine.Presets.stepIndex(presetOrder.size, presetOrder.indexOf(selectedPreset), +1)
                        if (ni >= 0) previewPresetName = presetOrder[ni]
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .semantics { contentDescription = "Next preset — opens measured preview" })
            Spacer(Modifier.width(4.dp))
            // v143 §28: CUSTOMIZED — current curve differs from the loaded preset
            if (eqPresetLv != null && com.neon.eq.engine.EqualizerEngine.isCustomizedDb(bandLevels, eqPresetLv!!, bandCount)) {
                Text("• CUSTOMIZED", fontSize = 8.sp, color = T.accent,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(T.accent.copy(alpha = 0.10f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .semantics { contentDescription = "Current EQ differs from the loaded preset" })
            }
            Spacer(Modifier.weight(1f))
            GlassButton("SAVE", 0) { presetNameInput = ""; showSaveDialog = true }
        }
        Spacer(Modifier.height(8.dp))

        NeonCard {

        Spacer(Modifier.height(16.dp))

        // ── Canvas-based EQ — ONE composable, no Slider widgets ──
        // Build #117: UI band selector — 10/15/31. Presets resample via
        // levelsForCount; old presets keep working at any count.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BANDS", fontSize = 10.sp, color = T.secondary)
            Spacer(Modifier.width(8.dp))
            listOf(10, 15, 31).forEach { n ->
                Button(
                    onClick = {
                        engine.setUiBandCount(n)
                        bandCount = engine.bandCount
                        bands = engine.bands
                        engine.applyFullState(
                            ShortArray(31) { i -> round(bandLevels[i]).toInt().toShort() },
                            bassBoost, virtualizer, loudness, smooth = true)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (bandCount == n) T.primary else T.accent),
                    modifier = Modifier.padding(end = 6.dp)
                ) { Text("$n", fontSize = 9.sp) }
            }
        }
        // v142 §16/§17: EQ analyzer overlay — OFF / SPECTRUM / SPECTRUM + CURVE.
        // The bins come from the shared live analyzer (real PCM); the curve is
        // the theoretical EQ response — never presented as measured output.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OVERLAY", fontSize = 9.sp, color = T.secondary)
            Spacer(Modifier.width(6.dp))
            listOf("OFF" to 0, "SPECTRUM" to 1, "SPECT + CURVE" to 2).forEach { (ml, mv) ->
                Text(
                    ml, fontSize = 9.sp,
                    color = if (eqAnalyzerMode == mv) T.primary else T.secondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background((if (eqAnalyzerMode == mv) T.primary else T.secondary).copy(alpha = 0.12f))
                        .clickable { eqAnalyzerMode = mv }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                        .semantics { contentDescription = "EQ overlay " + ml + (if (mv >= 1) " — live analyzer data" else "") }
                )
                Spacer(Modifier.width(4.dp))
            }
        }
        if (eqAnalyzerMode > 0) {
            Text(
                "spectrum = live analyzer (real PCM via native DSP) · curve = EQ response (theoretical)",
                fontSize = 7.sp, color = T.secondary, modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                if (eqHold) "HOLD ●" else "HOLD ○", fontSize = 9.sp,
                color = if (eqHold) T.accent else T.secondary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(50))
                    .background((if (eqHold) T.accent else T.secondary).copy(alpha = 0.12f))
                    .clickable { eqHold = !eqHold }
                    .padding(horizontal = 9.dp, vertical = 4.dp)
                    .semantics { contentDescription = if (eqHold) "Disable spectrum peak hold trace" else "Enable spectrum peak hold trace" }
            )
        }
        Spacer(Modifier.height(4.dp))
        // Build #121: quick preset strip — one tap applies, never interrupts playback
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(com.neon.eq.engine.Presets.BUILTIN_QUICK.size) { qi ->
                val (qname, _) = com.neon.eq.engine.Presets.BUILTIN_QUICK[qi]
                Text(
                    qname,
                    fontSize = 10.sp,
                    color = if (selectedPreset == qname) T.primary else T.secondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background((if (selectedPreset == qname) T.primary else T.secondary).copy(alpha = 0.12f))
                        .clickable {
                            pushUndo(true, "Preset: " + qname)
                            markRecent(qname)
                            val lv = com.neon.eq.engine.Presets.builtinForCount(qname, bandCount)
                            animateLevelsTo(FloatArray(31) { i -> (lv.getOrNull(i)?.toInt() ?: 0).toFloat() })
                            selectedPreset = qname
                            engine.setSelectedPresetName(qname)
                            engine.applyFullState(ShortArray(31) { i -> lv.getOrNull(i) ?: 0 }, bassBoost, virtualizer, loudness, smooth = true)
                            Toast.makeText(context, "Preset applied: " + qname, Toast.LENGTH_SHORT).show()
                        }
                        .semantics { contentDescription = "Apply preset " + qname }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val bandList = bands.take(bandCount)

        // Build #105: the EQ canvas is back on EVERY device — on limited
        // devices it drives the in-app software EQ (PLAYER card), on normal
        // devices the system engine as always.
        NeonCard {
        // Build #125: responsive EQ — landscape uses a wide graph + side panel;
        // portrait keeps the stacked layout. The y-mapping logic is identical
        // in both: AUTO/±6/±12/±18 only changes visualization, DSP stays -15..+20.
        val eqLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // Build #126: AUTO inspects configured gains, adds headroom, picks the
        // closest supported range. Hysteresis: expands immediately, shrinks
        // only well clear of the boundary — the graph never jumps mid-drag.
        val eqScale = if (eqScaleMode == 0) {
            val need = (bandLevels.take(bandCount).maxOfOrNull { kotlin.math.abs(it) } ?: 0f) * 1.15f + 0.5f
            val target = when { need <= 6f -> 6f; need <= 12f -> 12f; else -> 18f }
            if (target > autoScale) autoScale = target
            else if (need < autoScale * 0.55f) autoScale = target
            autoScale
        } else eqScaleMode.toFloat()
        if (eqLandscape) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
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
                        },
                        onBandTap = { band -> editBand = band },
                        scaleDb = eqScale,
                        selectedBand = selBand,
                        onBandSelect = { band -> selBand = if (selBand == band) -1 else band },
                        precisionMode = eqPrecision,
                        onGestureStart = { band -> pushUndo(true, "EQ band " + (band + 1)) },
                        analyzerMode = eqAnalyzerMode,
                        spectrumBins = spBars,
                        spectrumHold = spHold,
                        showHold = eqHold
                    )
                }
                Column(
                    modifier = Modifier.width(216.dp).padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("GRAPH RANGE", fontSize = 9.sp, color = T.secondary)
                    listOf("AUTO" to 0, "±6" to 6, "±12" to 12, "±18" to 18).forEach { (sl, sv) ->
                        Text(
                            sl, fontSize = 10.sp,
                            color = if (eqScaleMode == sv) T.primary else T.secondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background((if (eqScaleMode == sv) T.primary else T.secondary).copy(alpha = 0.12f))
                                .clickable { eqScaleMode = sv }
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .semantics { contentDescription = "Graph range " + sl }
                        )
                    }
                    // Build #126: PRECISION — drag becomes relative movement at
                    // 0.25x rate. A visible toggle, not an unstable multi-touch.
                    Text("PRECISION " + (if (eqPrecision) "ON" else "OFF"), fontSize = 10.sp,
                        color = if (eqPrecision) T.accent else T.secondary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background((if (eqPrecision) T.accent else T.secondary).copy(alpha = 0.12f))
                            .clickable { eqPrecision = !eqPrecision }
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .semantics { contentDescription = if (eqPrecision) "Disable precision dragging" else "Enable precision dragging" })
                    Text("OVERLAY", fontSize = 9.sp, color = T.secondary, modifier = Modifier.padding(top = 4.dp))
                    listOf("OFF" to 0, "SPECTRUM" to 1, "+CURVE" to 2).forEach { (ml, mv) ->
                        Text(
                            ml, fontSize = 10.sp,
                            color = if (eqAnalyzerMode == mv) T.primary else T.secondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background((if (eqAnalyzerMode == mv) T.primary else T.secondary).copy(alpha = 0.12f))
                                .clickable { eqAnalyzerMode = mv }
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .semantics { contentDescription = "EQ overlay " + ml }
                        )
                    }
                    if (eqAnalyzerMode > 0) {
                        Text(if (eqHold) "HOLD ●" else "HOLD ○", fontSize = 10.sp,
                            color = if (eqHold) T.accent else T.secondary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background((if (eqHold) T.accent else T.secondary).copy(alpha = 0.12f))
                                .clickable { eqHold = !eqHold }
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .semantics { contentDescription = "Spectrum peak hold trace" })
                    }
                    // Build #126: selected-band panel — only real engine values
                    if (selBand >= 0 && selBand < bandCount) {
                        val f = (bandList.getOrNull(selBand)?.freq ?: 1000) / 1000.0
                        val fq = if (f >= 1.0) "%.2f kHz".format(f) else "%.0f Hz".format(f * 1000)
                        Text("BAND " + (selBand + 1), fontSize = 12.sp, color = T.primary, fontWeight = FontWeight.Bold)
                        Text("FREQUENCY\n" + fq, fontSize = 11.sp, color = T.primary, lineHeight = 15.sp)
                        Text(
                            "GAIN\n" + "%+.1f dB".format(bandLevels.getOrElse(selBand) { 0f }),
                            fontSize = 11.sp, color = T.primary, lineHeight = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                gainEditBand = selBand
                                gainEditInput = "%.2f".format(bandLevels.getOrElse(selBand) { 0f })
                            }.semantics { contentDescription = "Band " + (selBand + 1) + " gain, tap to enter an exact value" }
                        )
                        Text("TYPE\nPeaking (graphic band)", fontSize = 11.sp, color = T.secondary, lineHeight = 15.sp)
                        Row {
                            TextButton(onClick = {
                                pushUndo(true, "Reset band " + (selBand + 1))
                                val newLevels = bandLevels.copyOf()
                                newLevels[selBand] = 0f
                                bandLevels = newLevels
                                engine.setBandLevel(selBand, 0)
                                selectedPreset = "Custom"
                                engine.setSelectedPresetName("Custom")
                            }) { Text("RESET BAND", color = T.accent) }
                            TextButton(onClick = { selBand = -1 }) { Text("DONE", color = T.secondary) }
                        }
                    }
                    Text("double-tap resets a band\nlong-press opens the editor\nDSP range stays -15..+20 dB", fontSize = 8.sp, color = T.secondary, lineHeight = 11.sp)
                }
            }
        } else {
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
                },
                onBandTap = { band -> editBand = band },
                scaleDb = eqScale,
                selectedBand = selBand,
                onBandSelect = { band -> selBand = if (selBand == band) -1 else band },
                precisionMode = eqPrecision,
                onGestureStart = { band -> pushUndo(true, "EQ band " + (band + 1)) },
                analyzerMode = eqAnalyzerMode,
                spectrumBins = spBars,
                spectrumHold = spHold,
                showHold = eqHold,
                graphHeight = if (focusEq) 460.dp else 360.dp
            )
            // Build #124: visual scale selector — visualization only, never alters DSP gains
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GRAPH RANGE", fontSize = 9.sp, color = T.secondary)
                Spacer(Modifier.width(6.dp))
                listOf("AUTO" to 0, "±6" to 6, "±12" to 12, "±18" to 18).forEach { (sl, sv) ->
                    Text(
                        sl, fontSize = 9.sp,
                        color = if (eqScaleMode == sv) T.primary else T.secondary,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background((if (eqScaleMode == sv) T.primary else T.secondary).copy(alpha = 0.12f))
                            .clickable { eqScaleMode = sv }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .semantics { contentDescription = "Graph range " + sl }
                    )
                }
                Text("PRECISION " + (if (eqPrecision) "ON" else "OFF"), fontSize = 9.sp,
                    color = if (eqPrecision) T.accent else T.secondary,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background((if (eqPrecision) T.accent else T.secondary).copy(alpha = 0.12f))
                        .clickable { eqPrecision = !eqPrecision }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .semantics { contentDescription = if (eqPrecision) "Disable precision dragging" else "Enable precision dragging" })
            }
            // Build #126: compact portrait band editor — real values only
            if (selBand >= 0 && selBand < bandCount) {
                val f = (bandList.getOrNull(selBand)?.freq ?: 1000) / 1000.0
                val fq = if (f >= 1.0) "%.2f kHz".format(f) else "%.0f Hz".format(f * 1000)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "B" + (selBand + 1) + " " + fq + "  GAIN " + "%+.1f dB".format(bandLevels.getOrElse(selBand) { 0f }),
                        fontSize = 11.sp, color = T.primary,
                        modifier = Modifier.weight(1f).clickable {
                            gainEditBand = selBand
                            gainEditInput = "%.2f".format(bandLevels.getOrElse(selBand) { 0f })
                        }.semantics { contentDescription = "Band " + (selBand + 1) + " gain, tap to enter an exact value" }
                    )
                    Text("RESET", fontSize = 9.sp, color = T.accent,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(T.accent.copy(alpha = 0.12f))
                            .clickable {
                                pushUndo(true, "Reset band " + (selBand + 1))
                                val newLevels = bandLevels.copyOf()
                                newLevels[selBand] = 0f
                                bandLevels = newLevels
                                engine.setBandLevel(selBand, 0)
                                selectedPreset = "Custom"
                                engine.setSelectedPresetName("Custom")
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Reset band " + (selBand + 1) + " only" })
                    Text("DONE", fontSize = 9.sp, color = T.secondary,
                        modifier = Modifier.padding(start = 6.dp).clickable { selBand = -1 }
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Close band editor" })
                }
                Text("double-tap resets a band · long-press opens the full editor · tap the gain value to type an exact number", fontSize = 8.sp, color = T.secondary)
            }
        }
        // ── Build #126: numeric gain entry — validated + clamped, never invalid to DSP ──
        if (gainEditBand >= 0 && gainEditBand < bandCount) {
            AlertDialog(
                containerColor = S.card.copy(alpha = 0.94f),
                shape = RoundedCornerShape(24.dp),
                onDismissRequest = { gainEditBand = -1 },
                title = { Text("BAND " + (gainEditBand + 1) + " GAIN", color = T.primary, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = gainEditInput,
                            onValueChange = { gainEditInput = it },
                            label = { Text("Exact gain (-15.0 .. +20.0)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Validated and clamped to the engine's real limits before any parameter is sent.", fontSize = 8.sp, color = T.secondary)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val parsed = gainEditInput.trim().replace("+", "").toFloatOrNull()
                        if (parsed != null) {
                            val clamped = parsed.coerceIn(-15f, 20f)
                            pushUndo(true, "Band " + (gainEditBand + 1) + " gain " + (if (clamped >= 0) "+" else "") + "%.1f dB".format(clamped))
                            val newLevels = bandLevels.copyOf()
                            newLevels[gainEditBand] = clamped
                            bandLevels = newLevels
                            engine.setBandLevel(gainEditBand, round(clamped).toInt().toShort())
                            selectedPreset = "Custom"
                            engine.setSelectedPresetName("Custom")
                            scope2.launch {
                                if (parsed < -15f || parsed > 20f) snackbarHost.showSnackbar("Clamped to " + (if (clamped >= 0) "+" else "") + "%.1f dB".format(clamped))
                                else snackbarHost.showSnackbar("Band " + (gainEditBand + 1) + " = " + (if (clamped >= 0) "+" else "") + "%.1f dB".format(clamped))
                            }
                        } else {
                            scope2.launch { snackbarHost.showSnackbar("Not a valid number — nothing changed") }
                        }
                        gainEditBand = -1
                    }, colors = ButtonDefaults.buttonColors(containerColor = T.primary)) { Text("APPLY") }
                },
                dismissButton = {
                    TextButton(onClick = { gainEditBand = -1 }) { Text("CANCEL", color = T.secondary) }
                }
            )
        }

        // ── Build #126: RESET ALL EQ — EQ section only, one undo entry ──
        if (showResetEqDialog) {
            AlertDialog(
                containerColor = S.card.copy(alpha = 0.94f),
                shape = RoundedCornerShape(24.dp),
                onDismissRequest = { showResetEqDialog = false },
                title = { Text("RESET EQ?", color = T.primary, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("Graphic EQ → Flat\nParametric EQ → Neutral", fontSize = 11.sp, color = T.secondary, lineHeight = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Preamp, shelves, compressor, stereo, limiter and effects are NOT changed. This is separate from the global RESET DSP.", fontSize = 9.sp, color = T.secondary)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        pushUndo(true, "Reset EQ")
                        try {
                            val newLevels = FloatArray(31) { 0f }
                            animateLevelsTo(newLevels)
                            engine.applyFullState(ShortArray(31) { 0 }, bassBoost, virtualizer, loudness, smooth = true)
                            val p = DspParams.load(engine)
                            p.slots = List(8) { PeqSlot() }
                            p.applyTo(NeonDsp)
                            p.save(engine)
                            selectedPreset = "Flat"
                            engine.setSelectedPresetName("Flat")
                        } catch (_: Throwable) { }
                        showResetEqDialog = false
                    }, colors = ButtonDefaults.buttonColors(containerColor = T.accent)) { Text("RESET EQ") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetEqDialog = false }) { Text("CANCEL", color = T.secondary) }
                }
            )
        }

        // ── v143 §15: GRAPH ACTION BAR — one compact row under the graph ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassButton("↶ UNDO" + if (undoStack.isEmpty()) "" else " " + undoStack.size,
                if (undoStack.isEmpty()) 1 else 0, enabled = undoStack.isNotEmpty()) { undoDsp() }
            Spacer(Modifier.width(6.dp))
            GlassButton("↷ REDO", if (redoStack.isEmpty()) 1 else 0, enabled = redoStack.isNotEmpty()) { redoDsp() }
            Spacer(Modifier.width(6.dp))
            // FLAT zeroes the graphic curve only — frequencies, Q and PEQ
            // slot states are preserved; one undo entry covers the action.
            GlassButton("FLAT", 1) {
                pushUndo(true, "Flat EQ")
                try {
                    animateLevelsTo(FloatArray(31) { 0f })
                    engine.applyFullState(ShortArray(31) { 0 }, bassBoost, virtualizer, loudness, smooth = true)
                    selectedPreset = "Flat"
                    engine.setSelectedPresetName("Flat")
                } catch (_: Throwable) { }
            }
            Spacer(Modifier.width(6.dp))
            GlassButton("RESET EQ", 2) { showResetEqDialog = true }
            Spacer(Modifier.width(6.dp))
            GlassButton("COPY", 1, enabled = true) {
                eqClipboard.value = bandLevels.copyOf()
                Toast.makeText(context, "EQ COPIED ✓", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.width(6.dp))
            GlassButton("PASTE", 1, enabled = eqClipboard.value != null) {
                eqClipboard.value?.let { clip ->
                    pushUndo(true, "Paste EQ")
                    try {
                        val safe = com.neon.eq.engine.EqualizerEngine.sanitizeEqLevelsDb(clip)
                        animateLevelsTo(safe)
                        engine.applyFullState(ShortArray(31) { i -> round(safe[i]).toInt().toShort() }, bassBoost, virtualizer, loudness, smooth = true)
                        selectedPreset = "Custom"
                        engine.setSelectedPresetName("Custom")
                        Toast.makeText(context, "EQ PASTED ✓", Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) { }
                }
            }
        }
        // ── v143 §30: mini status line — honest runtime facts only ──
        var eqRouteTick by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(2000); eqRouteTick++ } }
        val eqStatusLine = remember(eqRouteTick, CaptureEqService.running) {
            val ds = when {
                !NeonDsp.available -> "DSP ERROR"
                CaptureEqService.running && CaptureEqService.bypass -> "DSP BYPASS"
                CaptureEqService.running -> "DSP ACTIVE"
                else -> "STANDBY"
            }
            val rate = if (CaptureEqService.running && CaptureEqService.captureSampleRate > 0)
                " · " + (CaptureEqService.captureSampleRate / 1000) + " kHz" else ""
            val route = runCatching { AudioCapabilityManager.outputDeviceLine(context) }.getOrDefault("ROUTE UNKNOWN")
            ds + rate + " · " + route
        }
        Text(eqStatusLine, fontSize = 8.sp, color = T.secondary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))

        // ── Build #123: BAND EDITOR — tap a band point for details ──
        if (editBand >= 0 && editBand < bandCount) {
            val info = bandList.getOrNull(editBand)
            AlertDialog(
                containerColor = S.card.copy(alpha = 0.94f),
                shape = RoundedCornerShape(24.dp),
                onDismissRequest = { editBand = -1 },
                title = { Text("BAND " + (editBand + 1), color = T.primary, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        val f = (info?.freq ?: 1000) / 1000.0
                        Text("Frequency\n" + (if (f >= 1.0) "%.2f kHz".format(f) else "%.0f Hz".format(f * 1000)), fontSize = 11.sp, color = T.primary)
                        Spacer(Modifier.height(4.dp))
                        Text("Gain\n" + "%+.1f dB".format(bandLevels.getOrElse(editBand) { 0f }), fontSize = 11.sp, color = T.primary)
                        Spacer(Modifier.height(4.dp))
                        Text("Q\n1.00 (fixed for graphic bands)", fontSize = 11.sp, color = T.secondary)
                        Spacer(Modifier.height(4.dp))
                        Text("Type\nPeaking (graphic band)", fontSize = 11.sp, color = T.secondary)
                        Text("Accessibility: Band " + (editBand + 1) + ", " + (if (f >= 1.0) "%.2f kHz".format(f) else "%.0f Hz".format(f * 1000)) + ", " + "%+.1f dB".format(bandLevels.getOrElse(editBand) { 0f }) + ", Q 1.0", fontSize = 8.sp, color = T.secondary)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        pushUndo(true, "Reset band " + (editBand + 1))
                        val newLevels = bandLevels.copyOf()
                        newLevels[editBand] = 0f
                        bandLevels = newLevels
                        engine.setBandLevel(editBand, 0)
                        selectedPreset = "Custom"
                        engine.setSelectedPresetName("Custom")
                        editBand = -1
                    }, colors = ButtonDefaults.buttonColors(containerColor = T.accent)) { Text("RESET BAND") }
                },
                dismissButton = {
                    TextButton(onClick = { editBand = -1 }) { Text("CLOSE", color = T.secondary) }
                }
            )
        }
        }
        if (limitedNow) {
            NeonCard {
                GradientText("LOUDNESS", 11.sp, Brush.horizontalGradient(listOf(T.accent, T.secondary)))
                Spacer(Modifier.height(4.dp))
                Text(
                    // Build #104: v103's on-device test on the Vivo Y21 proved even the
                    // attached LoudnessEnhancer is audibly bypassed by the firmware. Never
                    // over-claim — tell the truth conditionally instead.
                    "Your phone's audio policy blocks every EQ engine — no app can change that. If the loudness dial makes no audible difference on this device, its firmware bypasses all third-party audio effects. Presets still save for your other devices.",
                    fontSize = 10.sp,
                    color = T.secondary,
                    lineHeight = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                val loudDbHero = if (loudness > 0)
                    String.format(java.util.Locale.US, "+%.1f dB", engine.loudnessAppliedMb(loudness) / 100f)
                else "0 dB"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularDial("LOUDNESS", loudness, 0..300, valueText = loudDbHero) { v ->
                        loudness = v
                        engine.setLoudness(v)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Spacer(Modifier.height(16.dp))

        // ── Effect dials — Build #95: back on the main screen in their old
        // spot below the EQ curve (Settings stint lasted v92-v94). ──
        // Build #103: the whole EFFECTS card is replaced by the loudness
        // hero card when the device only permits LoudnessEnhancer.
        if (!limitedNow) {
        NeonCard {
            GradientText("EFFECTS", 11.sp, Brush.horizontalGradient(listOf(T.accent, T.secondary)))
            Spacer(Modifier.height(8.dp))
            // Same honest readouts as always: TRUE hardware dB for bass
            // (engine bassBoostDb()), percent width for 3D (no dB exists for
            // stereo widening), real dB from loudnessMillibels() for loudness.
            val bassDb = if (bassBoost > 0) String.format(java.util.Locale.US, "+%.1f dB", bassBoost / 15f) else "0 dB"
            val loudDb = if (loudness > 0)
                String.format(java.util.Locale.US, "+%.1f dB", engine.loudnessAppliedMb(loudness) / 100f)
            else "0 dB"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CircularDial("BASS BOOST", bassBoost, 0..300, valueText = bassDb) { v ->
                    bassBoost = v
                    engine.setBassBoost(v)
                }
                CircularDial("3D SOUND", virtualizer, 0..300, valueText = "${virtualizer / 3}%") { v ->
                    virtualizer = v
                    engine.setVirtualizer(v)
                }
                CircularDial("LOUDNESS", loudness, 0..300, valueText = loudDb) { v ->
                    loudness = v
                    engine.setLoudness(v)
                }
            }
        }
        }

        Spacer(Modifier.height(16.dp))

        } // tab guard

        // v143 §31/§32: STUDIO & SYSTEM — every technical section stays one
        // tap away, but the EQ is the centerpiece. Collapsed by default in
        // normal mode; fully hidden in FOCUS EQ. Same composables, same state.
        if (!focusEq) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(T.secondary.copy(alpha = 0.08f))
                    .clickable { advOpen = !advOpen }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics { contentDescription = (if (advOpen) "Collapse studio and system sections" else "Expand studio and system sections") }
            ) {
                GradientText("STUDIO & SYSTEM", 11.sp, Brush.horizontalGradient(listOf(T.secondary, T.primary)))
                Spacer(Modifier.weight(1f))
                Text(if (advOpen) "▾ COLLAPSE" else "▸ EXPAND", fontSize = 10.sp, color = if (advOpen) T.primary else T.secondary)
            }
            Spacer(Modifier.height(8.dp))
            if (advOpen) {
        // ── Build #110: VISUALIZER on the main screen — system capture when
        // the device allows it, software-player capture when it doesn't ──
        NeonCard {
            var softWave by remember { mutableStateOf(ByteArray(0)) }
            var softWaveAt by remember { mutableStateOf(0L) }
            LaunchedEffect(Unit) {
                while (true) {
                    val w = SoftwareEq.sharedWaveform
                    val at = SoftwareEq.sharedWaveformAt
                    if (w != null && at != softWaveAt) { softWave = w; softWaveAt = at }
                    delay(33)
                }
            }
            val now = SystemClock.elapsedRealtime()
            val sysFresh = waveformAt > 0 && now - waveformAt < 1500
            val softFresh = softWaveAt > 0 && now - softWaveAt < 1500
            VisualizerBars(
                if (sysFresh) waveform else softWave,
                if (sysFresh) waveformAt else softWaveAt,
                active = sysFresh || softFresh,
                style = visStyle
            )
        }

        Spacer(Modifier.height(16.dp))

        // Build #117: shared audio pipeline state — the PLAYER and CAPTURE
        // paths converge on the same DSP backend (dsp/Pipeline.kt).
        // hoisted: shared by PLAYER card (HOME) and TEST SIGNALS (DSP tab)

        // ── Build #105: PLAYER — the one audio path no OEM can block ──
        NeonCard {
            GradientText("PLAYER — EQ INSIDE SONICCORE", 11.sp, Brush.horizontalGradient(listOf(T.accent, T.secondary)))
            var whyOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ENGINE: " + (SoftwareEq.lastEngineLabel ?: "—"), fontSize = 9.sp, color = T.secondary)
                Spacer(Modifier.width(8.dp))
                Text("WHY?", fontSize = 9.sp, color = T.accent,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(T.accent.copy(alpha = 0.10f))
                        .clickable { whyOpen = !whyOpen }.padding(horizontal = 8.dp, vertical = 3.dp)
                        .semantics { contentDescription = "Why this engine" })
            }
            if (whyOpen) {
                Text(
                    if (SoftwareEq.lastEngineLabel?.startsWith("KOTLIN") == true)
                        "«Native stereo DSP is unavailable for this path, so SonicCore is using the Kotlin fallback.»"
                    else "«The native C++ engine is processing the player path in stereo.»",
                    fontSize = 9.sp, color = T.secondary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Plays your music with the full 10-band EQ applied in software inside the app — works on every device, including ones that block system-wide EQ.",
                fontSize = 10.sp, color = T.secondary, lineHeight = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            val ctx = LocalContext.current
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            var hasPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
            val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }
            var diagTick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { while (true) { delay(700); diagTick++ } }
            DisposableEffect(Unit) { onDispose { player.stop(); tone.stop() } }
            // Any change to the curve — drag, preset, startup reapply — reaches the software EQ instantly
            LaunchedEffect(bandLevels) {
                dsp.setGains(bandLevels)
                // Build #119: immediate thread-safe native push for 15/31-band
                // selections — the 2s service mirror is a safety net only.
                if (com.neon.eq.dsp.NeonDsp.available && engine.bandCount > 10) {
                    try { com.neon.eq.dsp.NeonDsp.setGraphicGains(bandLevels) } catch (_: Throwable) { }
                }
            }
            LaunchedEffect(loudness) { dsp.setPreamp(engine.loudnessAppliedMb(loudness) / 100f) }
            // Build #107: one-tap pipeline proof + live player status.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (tone.isRunning) { tone.stop(); toneOn = false } else { player.stop(); tone.mode = "sweep"; tone.play(); toneOn = true }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = T.primary)
                ) { Text(if (toneOn) "STOP TEST TONE" else "PLAY TEST TONE · 30Hz-16kHz sweep", fontSize = 10.sp) }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = {
                        if (tone.isRunning && tone.mode == "lr") { tone.stop(); toneOn = false }
                        else { player.stop(); tone.mode = "lr"; tone.play(); toneOn = true }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = T.secondary)
                ) { Text("L/R TEST · 440Hz", fontSize = 10.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "player: " + (if (player.isRunning) (if (player.isPaused()) "paused" else "playing") else "idle") +
                    (if (player.isRunning && SoftEqPlayer.trackInfo.isNotEmpty()) " · " + SoftEqPlayer.trackInfo else "") +
                    (SoftEqPlayer.lastError?.let { " · ERR: " + it } ?: "") + (if (diagTick < 0) "" else ""),
                fontSize = 10.sp, color = T.secondary
            )
            Spacer(Modifier.height(6.dp))
            // Build #115: OUTPUT — Poweramp's method: direct volume control on
            // our own stream, plus explicit device routing when headphones or
            // Bluetooth are connected.
            var outVol by remember { mutableStateOf(engine.getPlayerVolume()) }
            LaunchedEffect(Unit) { player.setOutVolume(outVol) }
            val outDevices = remember {
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).filter { d ->
                        d.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                        d.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        d.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    }
                } catch (t: Throwable) { emptyList() }
            }
            var outSel by remember { mutableStateOf(-1) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OUTPUT", fontSize = 10.sp, color = T.secondary)
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { outVol = (outVol - 0.1f).coerceAtLeast(0f); player.setOutVolume(outVol); engine.setPlayerVolume(outVol) },
                    colors = ButtonDefaults.buttonColors(containerColor = T.secondary)
                ) { Text("−", fontSize = 10.sp) }
                Text("${(outVol * 100).toInt()}%", fontSize = 10.sp, color = T.secondary, modifier = Modifier.padding(horizontal = 6.dp))
                Button(
                    onClick = { outVol = (outVol + 0.1f).coerceAtMost(1.5f); player.setOutVolume(outVol); engine.setPlayerVolume(outVol) },
                    colors = ButtonDefaults.buttonColors(containerColor = T.secondary)
                ) { Text("+", fontSize = 10.sp) }
            }
            if (outDevices.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { outSel = -1; player.setPreferredOutput(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = if (outSel == -1) T.primary else T.accent)
                    ) { Text("AUTO", fontSize = 9.sp) }
                    outDevices.forEachIndexed { i, dev ->
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = { outSel = i; player.setPreferredOutput(dev) },
                            colors = ButtonDefaults.buttonColors(containerColor = if (i == outSel) T.primary else T.accent)
                        ) { Text(when (dev.type) {
                            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
                            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT"
                            else -> "PHONES"
                        }, fontSize = 9.sp) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (!hasPerm) {
                // Build #111: the permissionless path — system document picker.
                // Works on every OEM (Funtouch included) with zero grants.
                Text("Library permission is blocked or ungranted — pick any track directly instead, no permission needed:", fontSize = 10.sp, color = T.secondary, lineHeight = 13.sp)
                Spacer(Modifier.height(6.dp))
                var pickedName by remember { mutableStateOf<String?>(null) }
                var pickedPlaying by remember { mutableStateOf(false) }
                val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        // Build #112: keep read access across restarts so the
                        // same picked track replays later without re-picking.
                        try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
                        tone.stop(); toneOn = false
                        player.play(ctx, uri)
                        pickedName = (uri.lastPathSegment ?: "picked track").substringAfterLast('/')
                        pickedPlaying = true
                    }
                }
                LaunchedEffect(pickedName) {
                    while (player.isRunning) {
                        pickedPlaying = !player.isPaused()
                        delay(500)
                    }
                    pickedPlaying = false
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { pickFile.launch(arrayOf("audio/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = T.primary)
                    ) { Text("PICK A TRACK", fontSize = 10.sp) }
                    if (pickedName != null) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { player.togglePause(); pickedPlaying = player.isRunning && !player.isPaused() },
                            colors = ButtonDefaults.buttonColors(containerColor = T.secondary)
                        ) { Text(if (pickedPlaying) "PAUSE" else "RESUME", fontSize = 10.sp) }
                    }
                }
                if (pickedName != null) {
                    Text("playing: $pickedName — through the software EQ", fontSize = 10.sp, color = T.secondary)
                }
                Spacer(Modifier.height(6.dp))
                // Build #114: Poweramp-style folder library — zero permission.
                // Pick the music folder once; the tree grant persists across
                // restarts, so the list restores on the next launch.
                var folderTracks by remember { mutableStateOf<List<Pair<String, Uri>>>(emptyList()) }
                var folderName by remember { mutableStateOf<String?>(null) }
                var folderScanned by remember { mutableStateOf(false) }
                val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
                        engine.setMusicFolder(uri.toString())
                        folderName = (uri.lastPathSegment ?: "folder").substringAfterLast(':')
                        scope2.launch {
                            folderTracks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                scanAudioFolder(ctx.contentResolver, uri)
                            }
                        }
                    }
                }
                LaunchedEffect(folderScanned) {
                    if (folderScanned) return@LaunchedEffect
                    folderScanned = true
                    val saved = engine.getMusicFolder()
                    if (saved != null) {
                        folderName = "saved folder"
                        folderTracks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try { scanAudioFolder(ctx.contentResolver, Uri.parse(saved)) } catch (t: Throwable) { emptyList() }
                        }
                    }
                }
                Button(
                    onClick = { openTree.launch(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = T.accent)
                ) { Text(if (folderTracks.isEmpty()) "OPEN MUSIC FOLDER" else "SWITCH MUSIC FOLDER", fontSize = 10.sp) }
                if (folderName != null && folderTracks.isEmpty()) {
                    Text("no audio files found in $folderName", fontSize = 10.sp, color = T.secondary)
                }
                if (folderTracks.isNotEmpty()) {
                    Text("${folderTracks.size} tracks in $folderName — tap to play", fontSize = 10.sp, color = T.secondary)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                        items(folderTracks) { tr ->
                            Text(
                                tr.first,
                                fontSize = 10.sp, color = T.secondary,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    tone.stop(); toneOn = false
                                    player.play(ctx, tr.second)
                                    pickedName = tr.first
                                    pickedPlaying = true
                                }.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { permLauncher.launch(perm) },
                    colors = ButtonDefaults.buttonColors(containerColor = T.accent)
                ) { Text("GRANT MUSIC ACCESS (optional)", fontSize = 11.sp) }
            } else {
                var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
                var nowUri by remember { mutableStateOf<String?>(null) }
                var playing by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    tracks = try {
                        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
                        val list = mutableListOf<Track>()
                        ctx.contentResolver.query(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                            "${MediaStore.Audio.Media.DURATION} > 30000", null,
                            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
                        )?.use { cur ->
                            val idI = cur.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                            val tI = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                            val aI = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                            while (cur.moveToNext()) {
                                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cur.getLong(idI))
                                list.add(Track(uri, cur.getString(tI) ?: "Unknown", cur.getString(aI) ?: "Unknown"))
                            }
                        }
                        list
                    } catch (_: Throwable) { emptyList() }
                }
                LaunchedEffect(nowUri) {
                    while (player.isRunning) {
                        playing = !player.isPaused()
                        delay(500)
                    }
                    playing = false
                }
                if (tracks.isEmpty()) {
                    Text("No music found on the device.", fontSize = 10.sp, color = T.secondary)
                } else {
                    Text("${tracks.size} tracks · tap to play — the curve above is live in this player", fontSize = 10.sp, color = T.secondary)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.height(220.dp)) {
                        items(tracks) { t ->
                            val isNow = nowUri == t.uri.toString()
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (isNow) {
                                        player.togglePause()
                                        playing = player.isRunning && !player.isPaused()
                                    } else {
                                        tone.stop(); toneOn = false
                                        player.play(ctx, t.uri)
                                        nowUri = t.uri.toString()
                                        playing = true
                                    }
                                }.padding(vertical = 6.dp, horizontal = 4.dp)
                            ) {
                                Text(
                                    (if (isNow && playing) "▮▮ " else if (isNow) "▶ " else "") + t.title,
                                    fontSize = 12.sp,
                                    color = if (isNow) T.accent else T.secondary,
                                    maxLines = 1
                                )
                                Spacer(Modifier.weight(1f))
                                Text(t.artist, fontSize = 10.sp, color = T.secondary, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))


        // ── Build #123: DSP STUDIO — master control + visual processing chain ──
        NeonCard {
            GradientText("DSP ENGINE", 11.sp, Brush.horizontalGradient(listOf(T.primary, T.accent)))
            Spacer(Modifier.height(6.dp))
            // Build #124: four honest master states — never ACTIVE after a failure
            val masterState = when {
                !NeonDsp.available -> "⚠ DSP ERROR"
                SoftwareEq.lastEngineLabel?.startsWith("KOTLIN") == true -> "! KOTLIN FALLBACK"
                CaptureEqService.bypass -> "○ DSP BYPASS"
                else -> "● DSP ACTIVE"
            }
            val masterColor = when (masterState) {
                "⚠ DSP ERROR" -> T.accent
                "! KOTLIN FALLBACK" -> T.secondary
                "○ DSP BYPASS" -> T.accent
                else -> T.primary
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { CaptureEqService.bypass = !CaptureEqService.bypass }) {
                Text(masterState, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = masterColor)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text(if (NeonDsp.available) "Native DSP" else if (SoftwareEq.lastEngineLabel != null) "Kotlin" else "unavailable", fontSize = 11.sp, color = if (NeonDsp.available) T.primary else T.accent)
                    Text(CaptureEqService.captureSampleRate.toString() + " Hz · Stereo", fontSize = 9.sp, color = T.secondary)
                }
            }
            // Build #124: live activity — measured from actual frame deltas,
            // never inferred from service state; stops when counters stop.
            var dspPrev by remember { mutableStateOf(0L) }
            var dspMoving by remember { mutableStateOf(false) }
            var dspTick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); dspTick++ } }
            LaunchedEffect(dspTick) {
                val cur = CaptureEqService.framesDone
                dspMoving = cur > dspPrev && cur > 0
                dspPrev = cur
            }
            Text(if (dspMoving) "● DSP processing frames are advancing — measured, live" else "○ No measured frame advance (idle, bypassed, or no capture)", fontSize = 9.sp, color = if (dspMoving) T.primary else T.secondary)
            Text("Master bypass routes captured PCM around the native DSP (capture path only). Engine state is always read from the authoritative DSP, never assumed.", fontSize = 8.sp, color = T.secondary)
            Spacer(Modifier.height(8.dp))
            Text("PROCESSING CHAIN", fontSize = 11.sp, color = T.accent)
            Spacer(Modifier.height(2.dp))
            val stBase = remember { try { DspParams.load(engine) } catch (_: Throwable) { DspParams() } }
            var stOn by remember { mutableStateOf(mapOf(
                "preamp" to true, "graphic" to true, "parametric" to true, "bass" to true,
                "treble" to true, "comp" to stBase.compOn, "stereo" to true, "limiter" to stBase.limiterOn)) }
            fun stPush(key: String, on: Boolean) {
                try {
                    val p = DspParams()
                    p.preamp = if (key == "preamp" && !on) 0f else stBase.preamp
                    p.bass = if (key == "bass" && !on) 0f else stBase.bass
                    p.treble = if (key == "treble" && !on) 0f else stBase.treble
                    p.width = if (key == "stereo" && !on) 1f else stBase.width
                    p.balance = if (key == "stereo" && !on) 0f else stBase.balance
                    p.mono = if (key == "stereo" && !on) false else stBase.mono
                    p.swap = if (key == "stereo" && !on) false else stBase.swap
                    p.compOn = if (key == "comp") on else stBase.compOn
                    p.compThresh = stBase.compThresh
                    p.limiterOn = if (key == "limiter") on else stBase.limiterOn
                    p.limThresh = stBase.limThresh
                    p.slots = if (key == "parametric" && !on) List(8) { PeqSlot() } else stBase.slots
                    p.applyTo(NeonDsp)
                    if (key == "graphic") {
                        if (!on) NeonDsp.setGraphicGains(FloatArray(engine.bandCount) { 0f })
                        else NeonDsp.setGraphicGains(FloatArray(engine.bandCount) { i -> bandLevels.getOrNull(i) ?: 0f })
                    }
                    p.save(engine)
                    stOn = stOn.toMutableMap().also { it[key] = on }
                } catch (_: Throwable) { }
            }
            // Build #130: canonical signal order — PREAMP → PARAMETRIC →
            // GRAPHIC → BASS/TREBLE → STEREO → COMPRESSOR → CONVOLVER → LIMITER
            val stages = listOf(
                "preamp" to ("PREAMP" + " · " + "%+.1f dB".format(stBase.preamp)),
                "parametric" to "PARAMETRIC EQ · " + (stBase.slots.count { it.on }).toString() + " slots",
                "graphic" to "GRAPHIC EQ · " + if (bandLevels.any { it != 0f }) "custom curve" else "flat",
                "bass" to "BASS SHELF · " + "%+.1f dB".format(stBase.bass),
                "treble" to "TREBLE SHELF · " + "%+.1f dB".format(stBase.treble),
                "stereo" to "STEREO · " + "%.0f%%".format(stBase.width * 100),
                "comp" to "COMPRESSOR · " + if (stOn["comp"] == true) "ON" else "off",
                "convolver" to "CONVOLVER · impulse engine (API-ready, not yet active)",
                "limiter" to "LIMITER · " + if (stOn["limiter"] == true) "ON" else "off"
            )
            var stEdit by remember { mutableStateOf<String?>(null) }
            @Composable
            fun StageCard(si: Int, key: String, label: String) {
                if (si > 0) Text("↓", fontSize = 10.sp, color = T.secondary, modifier = Modifier.padding(start = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text((if (dspMoving && stOn[key] == true) "✓ " else "") + label, fontSize = 10.sp,
                        color = if (stOn[key] == true) T.primary else T.secondary)
                    Spacer(Modifier.weight(1f))
                    if (key == "convolver") {
                        // API-ready placeholder — honest label, no fake controls
                        Text("API READY", fontSize = 9.sp, color = T.secondary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(T.secondary.copy(alpha = 0.10f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .semantics { contentDescription = "Convolver: API-ready, not yet active" })
                    } else {
                        Text("EDIT", fontSize = 9.sp, color = T.primary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(T.primary.copy(alpha = 0.12f))
                                .clickable { if (stOn[key] == true) stEdit = key }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .semantics { contentDescription = "Edit stage " + key })
                        Text(if (stOn[key] == true) "BYPASS" else "ENABLE", fontSize = 9.sp,
                            color = if (stOn[key] == true) T.accent else T.primary,
                            modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background((if (stOn[key] == true) T.accent else T.primary).copy(alpha = 0.12f))
                                .clickable { pushUndo(true, "Stage: " + key); stPush(key, stOn[key] != true) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .semantics { contentDescription = (if (stOn[key] == true) "Bypass " else "Enable ") + key })
                    }
                }

            }
            // Build #127: responsive rack — 2-column console in landscape,
            // vertical rack with connectors in portrait.
            val rackLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            if (rackLandscape && stages.size > 1) {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        stages.take((stages.size + 1) / 2).forEachIndexed { i, (k, l) -> StageCard(i, k, l) }
                    }
                    Column(Modifier.weight(1f)) {
                        stages.drop((stages.size + 1) / 2).forEachIndexed { i, (k, l) -> StageCard(i, k, l) }
                    }
                }
            } else {
                stages.forEachIndexed { si, (key, label) -> StageCard(si, key, label) }
            }
            // Build #124: stage editor — only controls the native engine actually has
            if (stEdit != null) {
                val stKey = stEdit!!
                val stName = stages.firstOrNull { it.first == stKey }?.second ?: stKey
                val curParams = try { DspParams.load(engine) } catch (_: Throwable) { DspParams() }
                var stVal by remember(stKey) {
                    mutableStateOf(when (stKey) {
                        "preamp" -> curParams.preamp
                        "bass" -> curParams.bass
                        "treble" -> curParams.treble
                        "stereo" -> curParams.width
                        "comp" -> curParams.compThresh
                        "limiter" -> curParams.limThresh
                        else -> 0f
                    })
                }
                fun stApply(mutate: (DspParams) -> Unit) {
                    try {
                        val p = DspParams.load(engine)
                        mutate(p)
                        p.applyTo(NeonDsp)
                        p.save(engine)
                    } catch (_: Throwable) { }
                }
                AlertDialog(
                    containerColor = S.card.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(24.dp),
                    onDismissRequest = { stEdit = null },
                    title = { Text(stName, color = T.primary, fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            when (stKey) {
                                "preamp" -> { Text("Preamp gain", fontSize = 10.sp, color = T.secondary); Slider(value = stVal, onValueChange = { stVal = it }, valueRange = -12f..12f, onValueChangeFinished = { pushUndo(true, "Preamp"); stApply { it.preamp = stVal } }); Text("%+.1f dB".format(stVal), fontSize = 12.sp, color = T.primary) }
                                "bass" -> { Text("Bass shelf gain", fontSize = 10.sp, color = T.secondary); Slider(value = stVal, onValueChange = { stVal = it }, valueRange = -15f..20f, onValueChangeFinished = { pushUndo(true, "Bass"); stApply { it.bass = stVal } }); Text("%+.1f dB".format(stVal), fontSize = 12.sp, color = T.primary) }
                                "treble" -> { Text("Treble shelf gain", fontSize = 10.sp, color = T.secondary); Slider(value = stVal, onValueChange = { stVal = it }, valueRange = -15f..20f, onValueChangeFinished = { pushUndo(true, "Treble"); stApply { it.treble = stVal } }); Text("%+.1f dB".format(stVal), fontSize = 12.sp, color = T.primary) }
                                "stereo" -> { Text("Stereo width", fontSize = 10.sp, color = T.secondary); Slider(value = stVal, onValueChange = { stVal = it }, valueRange = 0.5f..2f, onValueChangeFinished = { pushUndo(true, "Stereo"); stApply { it.width = stVal } }); Text("%.0f%%".format(stVal * 100), fontSize = 12.sp, color = T.primary) }
                                "comp" -> { Text("Compressor threshold (the only control the native engine exposes)", fontSize = 10.sp, color = T.secondary); Slider(value = stVal, onValueChange = { stVal = it }, valueRange = -48f..0f, onValueChangeFinished = { pushUndo(true, "Compressor"); stApply { it.compThresh = stVal; it.compOn = true } }); Text("%.0f dB".format(stVal), fontSize = 12.sp, color = T.primary) }
                                "limiter" -> { Text("Limiter threshold (the only control the native engine exposes)", fontSize = 10.sp, color = T.secondary); Slider(value = stVal, onValueChange = { stVal = it }, valueRange = -18f..0f, onValueChangeFinished = { pushUndo(true, "Limiter"); stApply { it.limThresh = stVal; it.limiterOn = true } }); Text("%.1f dB".format(stVal), fontSize = 12.sp, color = T.primary) }
                                "parametric" -> Text("Parametric slots are edited in the DSP CHAIN card below — no duplicated controls here.", fontSize = 10.sp, color = T.secondary)
                                "graphic" -> Text("Graphic bands are dragged on the EQ tab — no duplicated controls here.", fontSize = 10.sp, color = T.secondary)
                            }
                        }
                    },
                    confirmButton = {
                        Row {
                            TextButton(onClick = {
                                pushUndo(true, "Reset stage " + stKey)
                                stApply {
                                    when (stKey) {
                                        "preamp" -> it.preamp = 0f
                                        "bass" -> it.bass = 0f
                                        "treble" -> it.treble = 0f
                                        "stereo" -> it.width = 1f
                                        "comp" -> { it.compOn = false }
                                        "limiter" -> { it.limThresh = -1f; it.limiterOn = true }
                                    }
                                }
                                stEdit = null
                            }) { Text("RESET STAGE", color = T.accent) }
                            TextButton(onClick = { pushUndo(true, "Stage: " + stKey); stPush(stKey, false); stEdit = null }) { Text("BYPASS", color = T.accent) }
                            TextButton(onClick = { stEdit = null }) { Text("DONE", color = T.secondary) }
                        }
                    }
                )
            }
            Text("Stage bypass modifies the existing configuration atomically — no parallel pipelines. Values snapshot at card open; fine-tune stages below.", fontSize = 8.sp, color = T.secondary)
        }
        Spacer(Modifier.height(16.dp))

        // ── Build #117: DSP CHAIN — parametric EQ + processing stages ──
        NeonCard {
            GradientText("DSP CHAIN", 11.sp, Brush.horizontalGradient(listOf(T.primary, T.accent)))
            Spacer(Modifier.height(4.dp))
            Text(
                "The shared native C++ chain: preamp → parametric EQ (8 slots) → graphic EQ → bass/treble → stereo width/balance/swap/mono → compressor → convolver → output limiter. PLAYER and CAPTURE both process through this engine (player: graphic EQ + preamp + limiter; capture: full chain). Settings persist.",
                fontSize = 10.sp, color = T.secondary, lineHeight = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            var dspP by remember { mutableStateOf(DspParams.load(engine)) }
            var dspVer by remember { mutableStateOf(0) }
            fun bump() { dspVer++ }
            @Suppress("UNUSED_EXPRESSION")
            val step = { v: Int -> if (dspVer < 0) v else v }

            @androidx.compose.runtime.Composable
            fun miniBtn(label: String, onClick: () -> Unit, active: Boolean = false) {
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) T.primary else T.secondary),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) { Text(label, fontSize = 9.sp) }
            }
            @androidx.compose.runtime.Composable
            fun parRow(name: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 10.sp, color = T.secondary, modifier = Modifier.width(64.dp))
                    miniBtn("−", onMinus)
                    Text(value, fontSize = 10.sp, color = T.secondary, modifier = Modifier.width(70.dp))
                    miniBtn("+", onPlus)
                }
            }
            // Preamp / shelves / stereo / dynamics
            parRow("PREAMP", (if (dspP.preamp >= 0) "+" else "") + "%.1f dB".format(dspP.preamp),
                { dspP.preamp = (dspP.preamp - 1f).coerceIn(-30f, 30f); dspP.applyTo(NeonDsp); dspP.save(engine); bump() },
                { dspP.preamp = (dspP.preamp + 1f).coerceIn(-30f, 30f); dspP.applyTo(NeonDsp); dspP.save(engine); bump() })
            parRow("BASS", (if (dspP.bass >= 0) "+" else "") + "%.1f dB".format(dspP.bass),
                { dspP.bass = (dspP.bass - 2f).coerceIn(-30f, 30f); dspP.applyTo(NeonDsp); dspP.save(engine); bump() },
                { dspP.bass = (dspP.bass + 2f).coerceIn(-30f, 30f); dspP.applyTo(NeonDsp); dspP.save(engine); bump() })
            parRow("TREBLE", (if (dspP.treble >= 0) "+" else "") + "%.1f dB".format(dspP.treble),
                { dspP.treble = (dspP.treble - 2f).coerceIn(-30f, 30f); dspP.applyTo(NeonDsp); dspP.save(engine); bump() },
                { dspP.treble = (dspP.treble + 2f).coerceIn(-30f, 30f); dspP.applyTo(NeonDsp); dspP.save(engine); bump() })
            parRow("WIDTH", "%.2f".format(dspP.width),
                { dspP.width = (dspP.width - 0.25f).coerceAtLeast(0f); dspP.applyTo(NeonDsp); dspP.save(engine); bump() },
                { dspP.width = (dspP.width + 0.25f).coerceAtMost(4f); dspP.applyTo(NeonDsp); dspP.save(engine); bump() })
            parRow("BALANCE", (if (dspP.balance > 0) "R " else "L ") + "%.2f".format(kotlin.math.abs(dspP.balance)),
                { dspP.balance = (dspP.balance - 0.25f).coerceIn(-1f, 1f); dspP.applyTo(NeonDsp); dspP.save(engine); bump() },
                { dspP.balance = (dspP.balance + 0.25f).coerceIn(-1f, 1f); dspP.applyTo(NeonDsp); dspP.save(engine); bump() })
            Row(verticalAlignment = Alignment.CenterVertically) {
                miniBtn("MONO", { dspP.mono = !dspP.mono; dspP.applyTo(NeonDsp); dspP.save(engine); bump() }, dspP.mono)
                miniBtn("SWAP L/R", { dspP.swap = !dspP.swap; dspP.applyTo(NeonDsp); dspP.save(engine); bump() }, dspP.swap)
                miniBtn("COMP", { dspP.compOn = !dspP.compOn; dspP.applyTo(NeonDsp); dspP.save(engine); bump() }, dspP.compOn)
                miniBtn("LIMITER", { dspP.limiterOn = !dspP.limiterOn; dspP.applyTo(NeonDsp); dspP.save(engine); bump() }, dspP.limiterOn)
            }
            Spacer(Modifier.height(8.dp))
            Text("PARAMETRIC EQ — 8 SLOTS", fontSize = 10.sp, color = T.accent)
            Spacer(Modifier.height(4.dp))
            val peqFreqs = remember { floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f) }
            dspP.slots.forEachIndexed { idx, slot ->
                val fq = slot.freq
                fun setSlot(s: com.neon.eq.dsp.PeqSlot) {
                    dspP.slots = dspP.slots.toMutableList().also { it[idx] = s }
                    dspP.applyTo(NeonDsp)
                    dspP.save(engine)
                    bump()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    miniBtn("P" + (idx + 1) + if (slot.on) " ✓" else "",
                        { setSlot(slot.copy(on = !slot.on)) },
                        slot.on)
                    miniBtn("F−", {
                        val i2 = (peqFreqs.indexOfFirst { it >= fq } - 1).coerceAtLeast(0)
                        setSlot(slot.copy(freq = peqFreqs[i2]))
                    })
                    miniBtn("F+", {
                        val i2 = (peqFreqs.indexOfFirst { it >= fq } + 1).coerceAtMost(peqFreqs.size - 1)
                        setSlot(slot.copy(freq = peqFreqs[i2]))
                    })
                    Text((if (fq >= 1000) (fq / 1000).toInt().toString() + "k" else fq.toInt().toString()) + "Hz", fontSize = 9.sp, color = T.secondary, modifier = Modifier.width(44.dp))
                    miniBtn("G−", { setSlot(slot.copy(gain = (slot.gain - 3f).coerceIn(-30f, 30f))) })
                    miniBtn("G+", { setSlot(slot.copy(gain = (slot.gain + 3f).coerceIn(-30f, 30f))) })
                    Text((if (slot.gain >= 0) "+" else "") + slot.gain.toInt() + "dB", fontSize = 9.sp, color = T.secondary, modifier = Modifier.width(44.dp))
                    miniBtn("Q−", { setSlot(slot.copy(q = (slot.q / 2f).coerceAtLeast(0.25f))) })
                    miniBtn("Q+", { setSlot(slot.copy(q = (slot.q * 2f).coerceAtMost(10f))) })
                }
            }
            Text(
                (if (dspVer < 0) "" else "") + "native: " + (if (NeonDsp.available) "loaded" else "unavailable"),
                fontSize = 9.sp, color = T.secondary
            )
        }

        Spacer(Modifier.height(16.dp))


        // ── Build #117: SYSTEM CAPTURE — CAPTURE MODE with honest A/B ──
        NeonCard {
            GradientText("SYSTEM CAPTURE", 11.sp, Brush.horizontalGradient(listOf(T.secondary, T.accent)))
            Spacer(Modifier.height(2.dp))
            Text("CAPTURE MODE", fontSize = 10.sp, color = T.accent)
            Spacer(Modifier.height(4.dp))
            Text(
                "Android 10+ public API: SonicCore captures other apps' playback with your consent (MediaProjection), processes it through the shared native DSP, and plays the result. This is the legitimate Android capture path, available identically on every brand — Samsung, Xiaomi, OnePlus, OPPO, Motorola, Pixel and all others.",
                fontSize = 10.sp, color = T.secondary, lineHeight = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Android may continue playing the original signal while SonicCore outputs the processed signal. No public API can silence or replace another app's audio — if you hear both, lower the source app's volume. DRM content, calls, and apps that opt out of capture are excluded by Android itself.",
                fontSize = 9.sp, color = T.accent, lineHeight = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            var capTick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(700); capTick++ } }
            val capCtx = LocalContext.current
            // A/B + counters
            val clipC = if (NeonDsp.available) runCatching { NeonDsp.clipCount() }.getOrDefault(0L) else 0L
            val nanC = if (NeonDsp.available) runCatching { NeonDsp.nanCount() }.getOrDefault(0L) else 0L
            Text(
                "capture: " + (if (CaptureEqService.running) (if (CaptureEqService.paused) "PAUSED" else "ACTIVE") else "off") +
                    (if (capTick < 0) "" else "") +
                    " | frames: in " + CaptureEqService.framesCaptured + " · out " + CaptureEqService.framesDone +
                    " | underruns: " + CaptureEqService.underruns +
                    " | clips: " + clipC + (if (nanC > 0) " | nan-bypass: " + nanC else "") +
                    " | " + CaptureEqService.captureSampleRate + "Hz" +
                    " | dsp load: " + "%.0f".format(CaptureEqService.dspLoadPct) + "%" +
                    " | latency: ~" + "%.0f".format(CaptureEqService.totalLatencyMs) + "ms" +
                    " (in " + "%.0f".format(CaptureEqService.capLatencyMs) + " / dsp " + "%.1f".format(CaptureEqService.dspMs) + " / out " + "%.0f".format(CaptureEqService.outLatencyMs) + ")" +
                    (CaptureEqService.routeNote?.let { " | " + it } ?: "") +
                    (CaptureEqService.lastError?.let { " | ERR: " + it } ?: ""),
                fontSize = 10.sp, color = T.secondary, lineHeight = 13.sp
            )
            if (CaptureEqService.running && CaptureEqService.noEligiblePlayback) {
                Text(
                    "Capture started but no eligible playback was detected. Play media in another app — apps may prevent capture, and DRM-protected or restricted audio is not capturable by Android design.",
                    fontSize = 9.sp, color = T.accent, lineHeight = 12.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            // A/B controls: DSP bypass, processed output volume, buffer mode
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CAPTURE MODE", fontSize = 10.sp, color = T.secondary)
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { CaptureEqService.bypass = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!CaptureEqService.bypass) T.primary else T.accent)
                ) { Text("B · DSP ACTIVE", fontSize = 9.sp) }
                Button(
                    onClick = { CaptureEqService.bypass = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (CaptureEqService.bypass) T.accent else T.secondary),
                    modifier = Modifier.padding(start = 4.dp)
                ) { Text("A · BYPASS", fontSize = 9.sp) }
            }
            Text(
                if (CaptureEqService.bypass) "RAW CAPTURE PATH — AudioPlaybackCapture → AudioRecord → AudioTrack. SonicCore DSP bypass — Android/OEM processing is unaffected."
                else "PROCESSED CAPTURE PATH — AudioPlaybackCapture → AudioRecord → NeonDspEngine → AudioTrack.",
                fontSize = 9.sp, color = T.accent, lineHeight = 12.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OUT VOL", fontSize = 10.sp, color = T.secondary)
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = {
                        CaptureEqService.outVolume = (CaptureEqService.outVolume - 0.1f).coerceAtLeast(0f)
                        engine.setCaptureOutVolume(CaptureEqService.outVolume)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = T.secondary)
                ) { Text("−", fontSize = 10.sp) }
                Text("%.0f%%".format(CaptureEqService.outVolume * 100), fontSize = 10.sp, color = T.secondary, modifier = Modifier.padding(horizontal = 4.dp))
                Button(
                    onClick = {
                        CaptureEqService.outVolume = (CaptureEqService.outVolume + 0.1f).coerceAtMost(1.5f)
                        engine.setCaptureOutVolume(CaptureEqService.outVolume)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = T.secondary)
                ) { Text("+", fontSize = 10.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BUFFER", fontSize = 10.sp, color = T.secondary)
                Spacer(Modifier.width(6.dp))
                listOf("low" to "LOW", "balanced" to "BALANCED", "stable" to "STABLE").forEach { (m, label) ->
                    Button(
                        onClick = {
                            engine.setCaptureBufferMode(m)
                            CaptureEqService.bufferMode = m
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (CaptureEqService.bufferMode == m) T.primary else T.accent),
                        modifier = Modifier.padding(end = 6.dp)
                    ) { Text(label, fontSize = 9.sp) }
                }
            }
            Text("Applies on next capture start. A single underrun never changes the mode — escalation uses a rolling threshold.", fontSize = 9.sp, color = T.secondary)
            Text(
                "buffer: " + CaptureEqService.bufferMode + " · " + "%.0f".format(CaptureEqService.captureBufferMs) + "ms · " + CaptureEqService.captureBufferFrames + " frames/chunk · underruns: " + CaptureEqService.underruns +
                    (if (CaptureEqService.framesDone > 0 && CaptureEqService.captureSampleRate > 0) " · rate: " + "%.1f".format(CaptureEqService.underruns.toDouble() / (CaptureEqService.framesDone.toDouble() / CaptureEqService.captureSampleRate / 60.0)) + "/min" else ""),
                fontSize = 9.sp, color = T.secondary
            )
            Spacer(Modifier.height(6.dp))
            val mpm = remember {
                try { capCtx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager } catch (t: Throwable) { null }
            }
            val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                if (res.resultCode == android.app.Activity.RESULT_OK && res.data != null) {
                    CaptureEqService.resultCode = res.resultCode
                    CaptureEqService.resultData = res.data
                    try { capCtx.startForegroundService(Intent(capCtx, CaptureEqService::class.java)) }
                    catch (t: Throwable) { Toast.makeText(capCtx, "Could not start capture: " + t.message, Toast.LENGTH_SHORT).show() }
                } else {
                    Toast.makeText(capCtx, "MediaProjection permission denied.", Toast.LENGTH_SHORT).show()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (CaptureEqService.running) {
                            try { capCtx.stopService(Intent(capCtx, CaptureEqService::class.java)) } catch (t: Throwable) { }
                        } else {
                            if (mpm == null || !AudioPath.captureSupported()) {
                                Toast.makeText(capCtx, "System playback capture requires Android 10 or newer.", Toast.LENGTH_SHORT).show()
                            } else {
                                CaptureEqService.framesCaptured = 0
                                CaptureEqService.framesDone = 0
                                CaptureEqService.underruns = 0
                                captureLauncher.launch(mpm.createScreenCaptureIntent())
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (CaptureEqService.running) T.accent else T.primary)
                ) { Text(if (CaptureEqService.running) "STOP CAPTURE" else "START CAPTURE", fontSize = 10.sp) }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (CaptureEqService.running) "SonicCore Audio Engine Active — notification has Pause/Stop/Open"
                    else "Asks for screen-record consent (only audio is captured)",
                    fontSize = 9.sp, color = T.secondary
                )
            }
        }

        Spacer(Modifier.height(16.dp))


        // ── Build #121: SESSIONS — local history from the recorded session log ──
        NeonCard {
            GradientText("SESSIONS", 11.sp, Brush.horizontalGradient(listOf(T.accent, T.secondary)))
            Spacer(Modifier.height(4.dp))
            Text("⇄ COMPARE TWO SESSIONS", fontSize = 9.sp, color = T.accent,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(T.accent.copy(alpha = 0.10f))
                    .clickable { showSessionCompare = true }.padding(horizontal = 10.dp, vertical = 5.dp)
                    .semantics { contentDescription = "Compare two sessions" })
            Spacer(Modifier.height(4.dp))
            var sessTick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3000); sessTick++ } }
            var sessSel by remember { mutableStateOf(-1) }
            val sessCtx = LocalContext.current
            // parse first (plain code), render second — composables never inside try/catch
            val sessRows: List<Triple<String, Int, String>> = try {
                val f = File(sessCtx.filesDir, "sessions.jsonl")
                if (f.exists()) f.readLines().takeLast(4).reversed().mapNotNull { line ->
                    try {
                        val o = JSONObject(line)
                        val verdict = when {
                            o.optLong("frames_cap") > 0 && o.optLong("frames_out") > 0 && !o.optBoolean("bypass") -> 1
                            o.optLong("frames_cap") > 0 && o.optLong("frames_out") > 0 -> 2
                            o.optLong("frames_cap") > 0 -> 3
                            else -> 4
                        }
                        val tMs = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - o.optLong("start_ms", 0L))
                        val head = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(tMs)) +
                            " · " + (o.optInt("sr") / 1000) + "kHz · " + o.optString("route") + " — " +
                            when (verdict) { 1 -> "SIGNAL PATH ACTIVE"; 2 -> "SIGNAL PATH ACTIVE (RAW)"; 3 -> "CAPTURE → DSP CONNECTION FAILURE"; else -> "NO ELIGIBLE PLAYBACK" }
                        val startAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(tMs))
                        val endAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(tMs + o.optLong("dur_ms")))
                        val detail = "VERDICT: " + when (verdict) { 1 -> "SIGNAL PATH ACTIVE"; 2 -> "SIGNAL PATH ACTIVE (RAW)"; 3 -> "CAPTURE → DSP CONNECTION FAILURE"; else -> "NO ELIGIBLE PLAYBACK" } + "\n" +
                            "duration " + o.optLong("dur_ms") / 1000 + "s · route changes " + o.optInt("route_changes") + " · buffer changes " + o.optInt("buffer_changes") + "\n" +
                            "cap " + o.optLong("frames_cap") + " · rec " + o.optLong("frames_rec") + " · jni " + o.optLong("frames_jni") + " · dsp " + o.optLong("frames_dsp") + " · out " + o.optLong("frames_out") + "\n" +
                            "underruns " + o.optLong("underruns") + " · clips " + o.optLong("clips") + " · NaN " + o.optLong("nan") + " · dsp cpu " + (o.optInt("dsp_cpu") / 10.0) + "%" + "\n" +
                            "error: " + o.optString("error", "none") + "\n" +
                            "EVENTS (recorded only):\n" +
                            startAt + "  Capture started\n" +
                            endAt + "  Capture stopped\n" +
                            "  " + o.optInt("route_changes") + " route change(s) · " + o.optInt("buffer_changes") + " buffer change(s) — per-event timestamps are not recorded"
                        Triple(head, verdict, detail)
                        Triple(head, verdict, detail)
                    } catch (t: Throwable) { null }
                } else emptyList()
            } catch (t: Throwable) { emptyList() }
            if (sessRows.isEmpty()) {
                Text("No capture sessions recorded yet — technical counters only, never audio.", fontSize = 9.sp, color = T.secondary)
            }
            sessRows.forEachIndexed { li, (head, verdict, detail) ->
                Text(
                    head,
                    fontSize = 10.sp,
                    color = if (verdict == 1 || verdict == 2) T.primary else T.secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sessSel = if (sessSel == li) -1 else li }
                        .padding(vertical = 3.dp)
                        .semantics { contentDescription = "Session, " + head }
                )
                if (sessSel == li) {
                    Text(detail, fontSize = 9.sp, color = T.secondary, lineHeight = 13.sp)
                }
            }
            Text(
                "EXPORT DIAGNOSTICS",
                fontSize = 10.sp, color = T.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(T.primary.copy(alpha = 0.10f))
                    .clickable {
                        try {
                            val file = File(sessCtx.cacheDir, "neoneq_sessions.txt")
                            file.writeText(AudioCapabilityManager.exportSessions(sessCtx))
                            val uri = FileProvider.getUriForFile(sessCtx, sessCtx.packageName + ".fileprovider", file)
                            sessCtx.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Share session diagnostics"))
                        } catch (t: Throwable) { }
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(16.dp))


        // ── Build #119: SIGNAL PATH — measured, stage-by-stage, never inferred ──
        NeonCard {
            GradientText("SIGNAL PATH", 11.sp, Brush.horizontalGradient(listOf(T.primary, T.accent)))
            Spacer(Modifier.height(4.dp))
            Text(
                "Each stage is measured from live frame counters — a running service alone never reports ACTIVE.",
                fontSize = 9.sp, color = T.secondary
            )
            Spacer(Modifier.height(6.dp))
            var spTick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); spTick++ } }
            var snap by remember { mutableStateOf(longArrayOf(0L, 0L, 0L, 0L)) }
            var deltas by remember { mutableStateOf(LongArray(4)) }
            LaunchedEffect(spTick) {
                val jni = if (NeonDsp.available) runCatching { NeonDsp.jniFrames() }.getOrDefault(0L) else 0L
                val dsp = if (NeonDsp.available) runCatching { NeonDsp.processedFrames() }.getOrDefault(0L) else 0L
                val cur = longArrayOf(CaptureEqService.framesCaptured, jni, dsp, CaptureEqService.framesDone)
                val ns = LongArray(4) { i -> cur[i] - snap[i] }
                snap = cur
                deltas = ns
            }
            val dCap = deltas[0]; val dJni = deltas[1]; val dDsp = deltas[2]; val dOut = deltas[3]
            val jniNow = snap[1]; val dspNow = snap[2]
            val recentData = CaptureEqService.running &&
                (android.os.SystemClock.elapsedRealtime() - CaptureEqService.lastDataAt) < 2500L
            val srcState = when {
                !CaptureEqService.running -> "CAPTURE STOPPED"
                CaptureEqService.noEligiblePlayback -> "SOURCE MAY BLOCK CAPTURE"
                recentData -> "ACTIVE"
                else -> "NO PLAYBACK"
            }
            val jniState = when {
                !CaptureEqService.running -> "—"
                CaptureEqService.bypass -> "BYPASSED (RAW PATH)"
                dJni > 0 -> "ACTIVE ($jniNow)"
                dCap > 0 -> "CAPTURE → DSP CONNECTION FAILURE"
                jniNow > 0 -> "ACTIVE ($jniNow)"
                else -> "NO DATA"
            }
            val dspOutState = when {
                !CaptureEqService.running -> "—"
                CaptureEqService.bypass -> "BYPASSED (RAW PATH)"
                dDsp > 0 -> "ACTIVE ($dspNow)"
                else -> "NO DATA"
            }
            val trackState = when {
                !CaptureEqService.running -> "—"
                dOut > 0 -> "ACTIVE (" + CaptureEqService.framesDone + ")"
                dDsp > 0 -> "DSP → OUTPUT CONNECTION FAILURE"
                else -> "NO DATA"
            }
            val pathVerdict = when {
                !CaptureEqService.running -> "CAPTURE STOPPED"
                CaptureEqService.noEligiblePlayback -> "NO ELIGIBLE PLAYBACK"
                !recentData -> "UNKNOWN"
                CaptureEqService.bypass && dOut > 0 -> "SIGNAL PATH ACTIVE (RAW — no DSP)"
                CaptureEqService.bypass -> "UNKNOWN"
                dCap > 0 && dJni == 0L -> "CAPTURE → DSP CONNECTION FAILURE"
                dDsp > 0 && dOut == 0L -> "DSP → OUTPUT CONNECTION FAILURE"
                dCap > 0 && dJni > 0 && dOut > 0 -> "SIGNAL PATH ACTIVE"
                else -> "UNKNOWN"
            }
            // Build #120: simple dashboard status (spec 20) — plain words here,
            // technical detail stays in AUDIO PATH below.
            val simpleStatus = when {
                !CaptureEqService.running && CaptureEqService.lastError != null -> "ERROR"
                !CaptureEqService.running -> "READY"
                CaptureEqService.noEligiblePlayback -> "SOURCE BLOCKED"
                !recentData -> "CAPTURE WAITING"
                CaptureEqService.bypass -> "CAPTURE ACTIVE"
                dDsp > 0 && dOut > 0 -> "CAPTURE ACTIVE · DSP ACTIVE · OUTPUT ACTIVE"
                else -> "CAPTURE ACTIVE"
            }
            Text(
                "SOURCE PLAYBACK: " + srcState + "\n" +
                "AUDIOPLAYBACKCAPTURE: " + (if (!CaptureEqService.running) "—" else if (dCap > 0 || CaptureEqService.framesCaptured > 0) "ACTIVE (" + CaptureEqService.framesCaptured + ")" else "NO DATA") + "\n" +
                "AUDIORECORD: " + (if (!CaptureEqService.running) "—" else if (dCap > 0 || CaptureEqService.recordFrames > 0) "ACTIVE (" + CaptureEqService.recordFrames + ")" else "NO DATA") + "\n" +
                "JNI → NATIVE DSP INPUT: " + jniState + "\n" +
                "NATIVE DSP OUTPUT: " + dspOutState + "\n" +
                "AUDIOTRACK OUTPUT: " + trackState + "\n" +
                "OUTPUT DEVICE: " + AudioCapabilityManager.outputDeviceLine(LocalContext.current),
                fontSize = 10.sp, color = T.secondary, lineHeight = 15.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "STATUS: " + simpleStatus,
                fontSize = 12.sp, color = T.primary
            )
            Text(
                "VERDICT: " + pathVerdict,
                fontSize = 11.sp, color = if (pathVerdict.startsWith("SIGNAL PATH ACTIVE")) T.primary else T.accent
            )
            Spacer(Modifier.height(4.dp))
            // Build #120: output confidence (spec 3) — three separate claims,
            // honestly separated. Processed-PCM delivery is verifiable; what
            // the user physically hears is not, when Android also plays the
            // original signal simultaneously.
            Text(
                "PROCESSING PATH: " + (if (pathVerdict == "SIGNAL PATH ACTIVE") "VERIFIED" else "not verified yet") +
                    " · OUTPUT DELIVERY: " + (if (dOut > 0) "VERIFIED" else "not verified yet") +
                    " · AUDIBLE RESULT: NOT DIRECTLY VERIFIABLE",
                fontSize = 10.sp, color = T.accent
            )
            Text(
                "SonicCore verified that processed PCM reached its AudioTrack. Android may also continue playing the original source application's output.",
                fontSize = 9.sp, color = T.secondary
            )
            Spacer(Modifier.height(6.dp))
            // DSP METERS — measured inside the native engine, never from UI settings
            val inRmsDb = if (NeonDsp.available && NeonDsp.inRmsMs() > 0) "%.1f dB".format(20 * kotlin.math.log10(NeonDsp.inRmsMs() / 1000.0)) else "-inf"
            val inPkDb = if (NeonDsp.available && NeonDsp.inPeakMs() > 0) "%.1f dB".format(20 * kotlin.math.log10(NeonDsp.inPeakMs() / 1000.0)) else "-inf"
            val outRmsDb = if (NeonDsp.available && NeonDsp.outRmsMs() > 0) "%.1f dB".format(20 * kotlin.math.log10(NeonDsp.outRmsMs() / 1000.0)) else "-inf"
            val outPkDb = if (NeonDsp.available && NeonDsp.outPeakMs() > 0) "%.1f dB".format(20 * kotlin.math.log10(NeonDsp.outPeakMs() / 1000.0)) else "-inf"
            Text(
                "A/B METERS (measured, same captured input): A · BYPASS output RMS " + outRmsDb + " (in RAW mode) | B · DSP ACTIVE output RMS " + outRmsDb + " (in PROCESSED mode)" +
                    " | INPUT RMS " + inRmsDb + " · PEAK " + inPkDb + " | OUTPUT PEAK " + outPkDb,
                fontSize = 10.sp, color = T.secondary
            )
            Text("Switch A/B mid-capture — the input is the same captured stream; safe maximum output gain 150% + limiter enforced.", fontSize = 9.sp, color = T.secondary)
            Spacer(Modifier.height(6.dp))
            // Build #120: effect verification (spec 5) — safe per-stage test
            // profiles applied inside the native engine, RESET restores the
            // user's previous settings exactly.
            var testStage by remember { mutableStateOf<String?>(null) }
            var savedDsp by remember { mutableStateOf<DspParams?>(null) }
            fun runTest(name: String, p: DspParams) {
                if (savedDsp == null) savedDsp = DspParams.load(engine)
                p.limiterOn = true; p.limThresh = -1f
                p.applyTo(NeonDsp)
                testStage = name
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { runTest("DSP TEST (preamp + bass + treble + width)", DspParams().apply { preamp = 0f; bass = 6f; treble = -6f; width = 0.5f }) },
                    colors = ButtonDefaults.buttonColors(containerColor = T.primary)) { Text("DSP TEST", fontSize = 8.sp) }
                Button(onClick = { runTest("BASS TEST (bass shelf +8dB)", DspParams().apply { bass = 8f }) },
                    colors = ButtonDefaults.buttonColors(containerColor = T.primary), modifier = Modifier.padding(start = 4.dp)) { Text("BASS", fontSize = 8.sp) }
                Button(onClick = { runTest("TREBLE TEST (treble shelf +8dB)", DspParams().apply { treble = 8f }) },
                    colors = ButtonDefaults.buttonColors(containerColor = T.primary), modifier = Modifier.padding(start = 4.dp)) { Text("TREBLE", fontSize = 8.sp) }
                Button(onClick = { runTest("STEREO TEST (width 40%)", DspParams().apply { width = 0.4f }) },
                    colors = ButtonDefaults.buttonColors(containerColor = T.primary), modifier = Modifier.padding(start = 4.dp)) { Text("STEREO", fontSize = 8.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { runTest("EQ TEST (parametric 1kHz +10dB)", DspParams().apply { slots = List(8) { if (it == 0) PeqSlot(true, 1000f, 10f, 2f) else PeqSlot() } }) },
                    colors = ButtonDefaults.buttonColors(containerColor = T.secondary)) { Text("EQ", fontSize = 8.sp) }
                Button(onClick = { runTest("LIMITER TEST (preamp +12dB, limiter -6dB)", DspParams().apply { preamp = 12f; limThresh = -6f }) },
                    colors = ButtonDefaults.buttonColors(containerColor = T.secondary), modifier = Modifier.padding(start = 4.dp)) { Text("LIMITER", fontSize = 8.sp) }
                Button(
                    onClick = {
                        savedDsp?.let { it.applyTo(NeonDsp); it.save(engine) }
                        savedDsp = null
                        testStage = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = T.accent),
                    modifier = Modifier.padding(start = 4.dp)
                ) { Text("RESET DSP TEST", fontSize = 8.sp) }
            }
            if (testStage != null) {
                Text("DSP TEST EFFECT ACTIVE — stage: " + testStage, fontSize = 9.sp, color = T.accent)
            } else {
                Text("Tests modify PCM inside the native C++ engine. RESET restores your previous settings.", fontSize = 9.sp, color = T.secondary)
            }
            Text("PLAYER ENGINE: " + (SoftwareEq.lastEngineLabel ?: "idle"), fontSize = 9.sp, color = T.secondary)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Build #120: DSP failure recovery (spec 7) — restart after a
                // NaN/Inf bypass, re-applies the user's chain from persisted state.
                Button(
                    onClick = {
                        try {
                            val srNow = if (CaptureEqService.captureSampleRate > 0) CaptureEqService.captureSampleRate else 48000
                            NeonDsp.init(srNow, engine.bandCount)
                            DspParams.load(engine).applyTo(NeonDsp)
                            val snap = engine.bandLevelsSnapshot()
                            NeonDsp.setGraphicGains(FloatArray(engine.bandCount) { i -> (snap.getOrNull(i)?.toInt() ?: 0).toFloat() })
                            CaptureEqService.bypass = false
                        } catch (t: Throwable) { }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = T.secondary)
                ) { Text("DSP RESTART", fontSize = 9.sp) }
                // Build #120: session diagnostics export (spec 1)
                val sessCtx = LocalContext.current
                Button(
                    onClick = {
                        try {
                            val file = File(sessCtx.cacheDir, "neoneq_sessions.txt")
                            file.writeText(AudioCapabilityManager.exportSessions(sessCtx))
                            val uri = FileProvider.getUriForFile(sessCtx, sessCtx.packageName + ".fileprovider", file)
                            sessCtx.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Share session diagnostics"))
                        } catch (t: Throwable) { }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = T.primary),
                    modifier = Modifier.padding(start = 4.dp)
                ) { Text("EXPORT SESSION DIAGNOSTICS", fontSize = 8.sp) }
            }
        }

        Spacer(Modifier.height(16.dp))


        // ── EQ PRESETS — the single canonical preset manager ──
        // Consolidated in Build #129: exactly one preset section exists — here.
        // State lives at top level; storage is unchanged (same prefs, same
        // JSON) — existing presets, favorites and recents keep working.
        NeonCard {
            GradientText("EQ PRESETS", 11.sp, Brush.horizontalGradient(listOf(T.secondary, T.primary)))
            Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Build #124: search + filter — UI-only, never affects audio
            OutlinedTextField(
                value = presetSearch,
                onValueChange = { presetSearch = it },
                label = { Text("🔍 Search presets...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("ALL" to 0, "FAVORITES" to 1, "RECENT" to 2, "CUSTOM" to 3).forEach { (fl, fi) ->
                    Text(
                        fl, fontSize = 9.sp,
                        color = if (presetFilter == fi) T.primary else T.secondary,
                        modifier = Modifier
                            .padding(start = if (fi > 0) 6.dp else 0.dp)
                            .clip(RoundedCornerShape(50))
                            .background((if (presetFilter == fi) T.primary else T.secondary).copy(alpha = 0.12f))
                            .clickable { presetFilter = fi }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .semantics { contentDescription = "Filter presets: " + fl }
                    )
                }
            }
            Row {
                Text(
                    "↺ Reset All",
                    fontSize = 11.sp,
                    color = T.accent,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(T.accent.copy(alpha = 0.10f))
                        .clickable { showResetDialog = true }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "+ Save",
                    fontSize = 11.sp,
                    color = T.primary,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(T.primary.copy(alpha = 0.10f))
                        .clickable {
                        presetNameInput = ""
                        showSaveDialog = true
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "⇄ Compare",
                    fontSize = 11.sp,
                    color = T.primary,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(T.primary.copy(alpha = 0.10f))
                        .clickable { showCompareDialog = true }
                        .semantics { contentDescription = "Compare current settings with a preset" }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "↥ Export",
                    fontSize = 11.sp,
                    color = T.secondary,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(T.secondary.copy(alpha = 0.10f))
                        .clickable {
                            try {
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_TEXT, buildPresetJson())
                                        putExtra(Intent.EXTRA_SUBJECT, "SonicCore preset — DSP configuration only")
                                    }, "Share preset"))
                            } catch (t: Throwable) { }
                        }
                        .semantics { contentDescription = "Export current preset as JSON" }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "↧ Import",
                    fontSize = 11.sp,
                    color = T.secondary,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(T.secondary.copy(alpha = 0.10f))
                        .clickable {
                            importPresetInput = ""
                            showImportPreset = true
                        }
                        .semantics { contentDescription = "Import a preset from JSON" }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "↥ Share",
                    fontSize = 11.sp,
                    color = T.primary,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(T.primary.copy(alpha = 0.10f))
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
                    color = T.primary,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(T.primary.copy(alpha = 0.10f))
                        .clickable {
                        importJsonInput = ""
                        importResultMsg = ""
                        showImportDialog = true
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Build #124: filtered views — favorites get a star; built-ins are never deletable
        val filteredBuiltins = Presets.presets.filter { p ->
            (presetSearch.isBlank() || p.name.contains(presetSearch, ignoreCase = true)) &&
            (presetFilter == 0 || presetFilter == 3 ||
                (presetFilter == 1 && p.name in presetFavs) ||
                (presetFilter == 2 && p.name in presetRecent))
        }.let { if (presetFilter == 1) it.sortedByDescending { p -> p.name in presetFavs } else it }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filteredBuiltins, key = { "b_" + it.name }) { preset ->
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (preset.name in presetFavs) "★" else " ", fontSize = 8.sp, color = T.accent,
                        modifier = Modifier.clickable { toggleFav(preset.name) }.semantics { contentDescription = (if (preset.name in presetFavs) "Unfavorite " else "Favorite ") + preset.name })
                    PresetChip(
                    preset = preset,
                    selected = selectedPreset == preset.name,
                    // Build #126: lightweight preview before applying
                    onClick = { previewPresetName = preset.name }
                )
                }
            }
            val filteredCustom = customPresets.filter { p ->
                (presetSearch.isBlank() || p.name.contains(presetSearch, ignoreCase = true)) &&
                (presetFilter == 0 || presetFilter == 3 ||
                    (presetFilter == 1 && p.name in presetFavs) ||
                    (presetFilter == 2 && p.name in presetRecent))
            }
            items(filteredCustom, key = { "c_" + it.name }) { preset ->
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (preset.name in presetFavs) "★" else " ", fontSize = 8.sp, color = T.accent,
                        modifier = Modifier.clickable { toggleFav(preset.name) }.semantics { contentDescription = (if (preset.name in presetFavs) "Unfavorite " else "Favorite ") + preset.name })
                    CustomPresetChip(
                    preset = preset,
                    selected = selectedPreset == preset.name,
                    onClick = { previewPresetName = preset.name },
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
        }
        Spacer(Modifier.height(16.dp))
        // ── Build #89: PRO FX — noise gate + anti-clip limiter ──
        NeonCard {
            GradientText("PRO FX", 11.sp, Brush.horizontalGradient(listOf(T.accent, T.primary)))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = noiseGate,
                    onClick = { noiseGate = !noiseGate; engine.setNoiseGate(noiseGate) },
                    label = { Text("NOISE GATE", fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = T.primary.copy(alpha = 0.2f),
                        selectedLabelColor = T.primary
                    )
                )
                FilterChip(
                    selected = limiterOn,
                    onClick = { limiterOn = !limiterOn; engine.setLimiter(limiterOn) },
                    label = { Text("LIMITER", fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = T.accent.copy(alpha = 0.2f),
                        selectedLabelColor = T.accent
                    )
                )
            }
            Text(
                if (noiseGate) "Gate active — silences background hiss during quiet passages" else "Gate off",
                fontSize = 9.sp, color = Color.Gray
            )
            if (limiterOn) {
                CircularDial("LIMIT STRENGTH", limiterThr, 0..100, valueText = "$limiterThr%") { v ->
                    limiterThr = v
                    engine.setLimiterThreshold(v)
                }
                Text(
                    "Anti-clip protection — keeps loud curves from distorting at high volume",
                    fontSize = 9.sp, color = Color.Gray
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Per-app profiles ──

        // ── Build #127: APPEARANCE — glass intensity (UI rendering only) ──
        NeonCard {
            GradientText("APPEARANCE", 11.sp, Brush.horizontalGradient(listOf(T.primary, T.secondary)))
            Spacer(Modifier.height(6.dp))
            Text("GLASS EFFECT", fontSize = 10.sp, color = T.secondary)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("LOW" to 0, "MEDIUM" to 1, "HIGH" to 2).forEach { (gl, gv) ->
                    Text(
                        gl, fontSize = 10.sp,
                        color = if (appGlassState.value == gv) T.primary else T.secondary,
                        modifier = Modifier
                            .padding(start = if (gv > 0) 6.dp else 0.dp)
                            .clip(RoundedCornerShape(50))
                            .background((if (appGlassState.value == gv) T.primary else T.secondary).copy(alpha = 0.12f))
                            .clickable {
                                appGlassState.value = gv
                                try {
                                    context.getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE)
                                        .edit().putString("glass", gv.toString()).apply()
                                } catch (_: Throwable) { }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Glass effect " + gl }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Glass effect changes UI rendering only. It never affects DSP, capture, audio buffers, latency or sample rate. If the device struggles, the glass reduces gracefully while borders and transparency remain.", fontSize = 8.sp, color = T.secondary)
        }
        Spacer(Modifier.height(16.dp))
        // ── Build #130: ANALYZER — shared state with the SPECTRUM card ──
        NeonCard {
            GradientText("ANALYZER", 11.sp, Brush.horizontalGradient(listOf(T.primary, T.accent)))
            Spacer(Modifier.height(6.dp))
            Text("FPS", fontSize = 10.sp, color = T.secondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("15" to 15, "30" to 30, "60" to 60).forEach { (fl, fv) ->
                    Text(fl, fontSize = 10.sp,
                        color = if (spFps == fv) T.primary else T.secondary,
                        modifier = Modifier
                            .padding(start = if (fv > 15) 6.dp else 0.dp)
                            .clip(RoundedCornerShape(50))
                            .background((if (spFps == fv) T.primary else T.secondary).copy(alpha = 0.12f))
                            .clickable {
                                spFps = fv
                                try { favPrefs.edit().putInt("an_fps", fv).apply() } catch (_: Throwable) { }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Analyzer " + fl + " frames per second" })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("PEAK HOLD", fontSize = 10.sp, color = T.secondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("ON" to 1, "OFF" to 0).forEach { (hl, hv) ->
                    Text(hl, fontSize = 10.sp,
                        color = if (spHoldMode == hv) T.primary else T.secondary,
                        modifier = Modifier
                            .padding(start = if (hv == 0) 6.dp else 0.dp)
                            .clip(RoundedCornerShape(50))
                            .background((if (spHoldMode == hv) T.primary else T.secondary).copy(alpha = 0.12f))
                            .clickable {
                                spHoldMode = hv
                                try { favPrefs.edit().putBoolean("an_hold", hv == 1).apply() } catch (_: Throwable) { }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Analyzer peak hold " + hl })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("SMOOTHING", fontSize = 10.sp, color = T.secondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("LOW" to 0, "MEDIUM" to 1, "HIGH" to 2).forEach { (sl, sv) ->
                    Text(sl, fontSize = 10.sp,
                        color = if (favPrefs.getInt("an_smooth", 1) == sv) T.primary else T.secondary,
                        modifier = Modifier
                            .padding(start = if (sv > 0) 6.dp else 0.dp)
                            .clip(RoundedCornerShape(50))
                            .background((if (favPrefs.getInt("an_smooth", 1) == sv) T.primary else T.secondary).copy(alpha = 0.12f))
                            .clickable {
                                spSmoothAmt = when (sv) { 0 -> 0.35f; 2 -> 0.75f; else -> 0.55f }
                                try { favPrefs.edit().putInt("an_smooth", sv).apply() } catch (_: Throwable) { }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Analyzer smoothing " + sl })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("FREEZE (this session)", fontSize = 10.sp, color = T.secondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("OFF" to false, "ON" to true).forEach { (fl, fv) ->
                    Text(fl, fontSize = 10.sp,
                        color = if (spFrozen == fv) T.primary else T.secondary,
                        modifier = Modifier
                            .padding(start = if (fv) 6.dp else 0.dp)
                            .clip(RoundedCornerShape(50))
                            .background((if (spFrozen == fv) T.primary else T.secondary).copy(alpha = 0.12f))
                            .clickable { spFrozen = fv }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Analyzer freeze " + fl })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Same controls as the SPECTRUM card — one shared state, no duplicate settings. The analyzer renders from native DSP snapshots outside the real-time audio callback.", fontSize = 8.sp, color = T.secondary)
        }
        Spacer(Modifier.height(16.dp))
        // ── Build #130: NOTIFICATIONS — authoritative state machine, informational ──
        NeonCard {
            GradientText("NOTIFICATIONS", 11.sp, Brush.horizontalGradient(listOf(T.accent, T.primary)))
            Spacer(Modifier.height(6.dp))
            Text("The foreground notification mirrors the measured DSP state machine: DSP ACTIVE, DSP BYPASS, DSP ERROR, CAPTURE BLOCKED, WAITING, AUDIO ERROR — with the actual sample rate, output route and measured level whenever available. SonicCore never invents states; per-channel control lives in Android notification settings.", fontSize = 10.sp, color = T.secondary, lineHeight = 14.sp)
        }
        Spacer(Modifier.height(16.dp))
        // ── Build #123: OUTPUT ROUTE — information, never a fake control ──
        NeonCard {
            GradientText("OUTPUT ROUTE", 11.sp, Brush.horizontalGradient(listOf(T.primary, T.secondary)))
            Spacer(Modifier.height(4.dp))
            val rBt = AudioCapabilityManager.btConnected(context)
            val rUsb = AudioCapabilityManager.usbConnected(context)
            val rWired = AudioCapabilityManager.wiredConnected(context)
            Text("● " + AudioCapabilityManager.outputDeviceLine(context) + "  (current)", fontSize = 11.sp, color = T.primary)
            if (rWired) Text("○ Wired headset connected", fontSize = 10.sp, color = T.secondary)
            if (rBt) Text("○ Bluetooth connected", fontSize = 10.sp, color = T.secondary)
            if (rUsb) Text("○ USB audio connected", fontSize = 10.sp, color = T.secondary)
            Text("System-controlled route — Android owns route selection. SonicCore reports the active route and rebuilds its own output when it changes; it cannot force a different system route.", fontSize = 8.sp, color = T.secondary)
        }
        Spacer(Modifier.height(16.dp))

        // ── Build #123: ABOUT ──
        NeonCard {
            GradientText("ABOUT", 11.sp, Brush.horizontalGradient(listOf(T.secondary, T.primary)))
            Spacer(Modifier.height(4.dp))
            Text(
                "SonicCore — Professional Audio Processing\n" +
                "Real-time Android DSP and audio enhancement\n" +
                "Engine: native C++ DSP (10/15/31-band RBJ biquads, seqlock atomic params, lock-free real-time audio)\n" +
                "Audio path: AudioPlaybackCapture → AudioRecord → JNI → native DSP → AudioTrack (public Android APIs only — no root, no OEM-specific code)\n" +
                "Limitation: Android playback capture depends on source-app policy and platform restrictions. Some apps or protected content may not be capturable, and Android public APIs cannot forcibly mute another app's original playback.\n" +
                "Honesty model: PROCESSING PATH and OUTPUT DELIVERY are verified from measured frame counters; the audible result is explicitly NOT directly verifiable while Android may also play the source app's original audio.",
                fontSize = 10.sp, color = T.secondary, lineHeight = 14.sp
            )
        }
        Spacer(Modifier.height(16.dp))

        // ── Build #118: AUDIO PATH — universal, capability-driven, brand-neutral ──
        NeonCard {
            GradientText("AUDIO PATH", 11.sp, Brush.horizontalGradient(listOf(T.primary, T.secondary)))
            Spacer(Modifier.height(6.dp))
            val apCtx = LocalContext.current
            var pathTick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); pathTick++ } }
            var compatReport by remember { mutableStateOf<String?>(null) }
            val clipC2 = if (NeonDsp.available) runCatching { NeonDsp.clipCount() }.getOrDefault(0L) else 0L
            Text(
                "DEVICE: " + AudioCapabilityManager.osLine() + "\n" +
                "MANUFACTURER: " + AudioCapabilityManager.manufacturer() + " · MODEL: " + AudioCapabilityManager.model() + "\n" +
                "ANDROID: " + AudioCapabilityManager.androidLine() + "\n" +
                "CAPTURE: " + (if (AudioCapabilityManager.playbackCapture().toString() == "SUPPORTED") "SUPPORTED" else "UNSUPPORTED") + "\n" +
                "MEDIA PROJECTION: " + (if (CaptureEqService.running) "GRANTED" else "NOT REQUESTED") + "\n" +
                "SOURCE PLAYBACK: " + (if (!CaptureEqService.running) "—" else if (CaptureEqService.noEligiblePlayback) "NOT DETECTED (source app may block capture)" else "DETECTED") + "\n" +
                "DSP: " + (if (CaptureEqService.running) "ACTIVE · " + CaptureEqService.captureSampleRate + "Hz" else if (NeonDsp.available) "READY" else "ERROR") + "\n" +
                "OUTPUT: " + (if (CaptureEqService.running && !CaptureEqService.noEligiblePlayback) "ACTIVE" else if (CaptureEqService.running) "WAITING" else "INACTIVE") + "\n" +
                "OUTPUT DEVICE: " + AudioCapabilityManager.outputDeviceLine(apCtx) + "\n" +
                "SAMPLE RATE: " + (if (CaptureEqService.running) CaptureEqService.captureSampleRate.toString() + "Hz in / " + CaptureEqService.captureSampleRate + "Hz out (same rate, no resampling)" else AudioCapabilityManager.suggestedSampleRate(apCtx).toString() + "Hz (detected)") + "\n" +
                "CHANNELS: " + AudioCapabilityManager.channelsLine() + "\n" +
                "BUFFER: " + CaptureEqService.bufferMode + " mode · " + AudioCapabilityManager.framesPerBufferLine(apCtx) + "\n" +
                "LATENCY: capture " + "%.0f".format(CaptureEqService.capLatencyMs) + "ms · dsp " + "%.1f".format(CaptureEqService.dspMs) + "ms · output " + "%.0f".format(CaptureEqService.outLatencyMs) + "ms · total ~" + "%.0f".format(CaptureEqService.totalLatencyMs) + "ms\n" +
                "UNDERRUNS: " + CaptureEqService.underruns + " · CLIPS: " + clipC2 + " · DSP CPU LOAD: " + "%.0f".format(CaptureEqService.dspLoadPct) + "%" +
                (if (pathTick < 0) "" else ""),
                fontSize = 10.sp, color = T.secondary, lineHeight = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Text("CAPTURE REPORT", fontSize = 10.sp, color = T.accent)
            Text(CaptureEqService.statusReport(), fontSize = 10.sp, color = T.secondary, lineHeight = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "RUN AUDIO COMPATIBILITY TEST",
                fontSize = 11.sp,
                color = T.primary,
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(T.primary.copy(alpha = 0.10f))
                    .clickable { compatReport = AudioCapabilityManager.runFullTest(apCtx) }
            )
            if (compatReport != null) {
                Text(compatReport!!, fontSize = 10.sp, color = T.secondary, lineHeight = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("TEST SIGNALS — safe 0.6 level into the limiter chain", fontSize = 9.sp, color = T.secondary)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { player.stop(); tone.mode = "sweep"; tone.play(); toneOn = true }, colors = ButtonDefaults.buttonColors(containerColor = T.accent), modifier = Modifier.padding(end = 4.dp)) { Text("SWEEP 30-16K", fontSize = 8.sp) }
                Button(onClick = { player.stop(); tone.mode = "sweep20"; tone.play(); toneOn = true }, colors = ButtonDefaults.buttonColors(containerColor = T.accent), modifier = Modifier.padding(end = 4.dp)) { Text("SWEEP 20-20K", fontSize = 8.sp) }
                Button(onClick = { player.stop(); tone.mode = "lr"; tone.play(); toneOn = true }, colors = ButtonDefaults.buttonColors(containerColor = T.accent), modifier = Modifier.padding(end = 4.dp)) { Text("L/R", fontSize = 8.sp) }
                Button(onClick = { player.stop(); tone.mode = "lonly"; tone.play(); toneOn = true }, colors = ButtonDefaults.buttonColors(containerColor = T.accent), modifier = Modifier.padding(end = 4.dp)) { Text("L-ONLY", fontSize = 8.sp) }
                Button(onClick = { player.stop(); tone.mode = "ronly"; tone.play(); toneOn = true }, colors = ButtonDefaults.buttonColors(containerColor = T.accent)) { Text("R-ONLY", fontSize = 8.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { player.stop(); tone.mode = "tone440"; tone.play(); toneOn = true }, colors = ButtonDefaults.buttonColors(containerColor = T.secondary), modifier = Modifier.padding(end = 4.dp)) { Text("440Hz", fontSize = 8.sp) }
                Button(onClick = { player.stop(); tone.mode = "tone1k"; tone.play(); toneOn = true }, colors = ButtonDefaults.buttonColors(containerColor = T.secondary), modifier = Modifier.padding(end = 4.dp)) { Text("1KHZ", fontSize = 8.sp) }
                Button(onClick = { player.stop(); tone.mode = "pink"; tone.play(); toneOn = true }, colors = ButtonDefaults.buttonColors(containerColor = T.secondary), modifier = Modifier.padding(end = 4.dp)) { Text("PINK", fontSize = 8.sp) }
                Button(onClick = { tone.stop(); toneOn = false }, colors = ButtonDefaults.buttonColors(containerColor = T.accent)) { Text("STOP TONE", fontSize = 8.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Export Audio Diagnostics",
                fontSize = 11.sp,
                color = T.primary,
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(T.primary.copy(alpha = 0.10f))
                    .clickable {
                        try {
                            val diag = buildString {
                                appendLine("SonicCore Audio Diagnostics — " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date()))
                                appendLine("Device: " + AudioCapabilityManager.osLine())
                                appendLine("Android: " + AudioCapabilityManager.androidLine())
                                appendLine("Capture API: " + (if (AudioCapabilityManager.playbackCapture().toString() == "SUPPORTED") "SUPPORTED" else "UNSUPPORTED"))
                                appendLine("MediaProjection: " + (if (CaptureEqService.running) "GRANTED" else if (CaptureEqService.lastError?.startsWith("CAPTURE_PERMISSION_DENIED") == true) "DENIED" else "NOT REQUESTED"))
                                appendLine("Capture: " + (if (CaptureEqService.running) "ACTIVE" else "inactive") + " · frames in " + CaptureEqService.framesCaptured + " / out " + CaptureEqService.framesDone)
                                appendLine("Input: " + (if (CaptureEqService.running) CaptureEqService.captureSampleRate.toString() + "Hz stereo 16-bit" else "n/a"))
                                appendLine("Output: " + AudioCapabilityManager.outputDeviceLine(apCtx))
                                appendLine("DSP: " + (if (NeonDsp.available) "native loaded" else "unavailable: " + NeonDsp.loadError) + " · bypass " + (if (CaptureEqService.bypass) "ON" else "OFF"))
                                appendLine("Buffer: " + CaptureEqService.bufferMode + " · underruns: " + CaptureEqService.underruns + " · clips: " + (if (NeonDsp.available) NeonDsp.clipCount() else 0) + " · nan-bypass: " + (if (NeonDsp.available) NeonDsp.nanCount() else 0))
                                appendLine("Latency: ~" + "%.0f".format(CaptureEqService.totalLatencyMs) + "ms (in " + "%.0f".format(CaptureEqService.capLatencyMs) + " / dsp " + "%.1f".format(CaptureEqService.dspMs) + " / out " + "%.0f".format(CaptureEqService.outLatencyMs) + ") · dsp load " + "%.0f".format(CaptureEqService.dspLoadPct) + "%")
                                appendLine("Route note: " + (CaptureEqService.routeNote ?: "none"))
                                appendLine("Errors: " + (CaptureEqService.lastError ?: "none"))
                                appendLine("--- Compatibility test ---")
                                appendLine(compatReport ?: "(not run)")
                                appendLine("--- Capability log (last 5, local, anonymized) ---")
                                appendLine(AudioCapabilityManager.logTail(apCtx))
                            }
                            val file = File(apCtx.cacheDir, "neoneq_diagnostics.txt")
                            file.writeText(diag)
                            val uri = FileProvider.getUriForFile(apCtx, apCtx.packageName + ".fileprovider", file)
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            apCtx.startActivity(Intent.createChooser(share, "Share audio diagnostics"))
                        } catch (t: Throwable) {
                            Toast.makeText(apCtx, "Export failed", Toast.LENGTH_SHORT).show()
                        }
                    }
            )
        }

        Spacer(Modifier.height(16.dp))



        NeonCard {
            GradientText("APP PROFILES", 11.sp, Brush.horizontalGradient(listOf(T.primary, T.secondary)))
            Spacer(Modifier.height(6.dp))
            val pkg = playingApp
            if (pkg != null) {
                Text("♪ ${appLabel(pkg)}", fontSize = 12.sp, color = T.primary, fontWeight = FontWeight.Bold, maxLines = 1)
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
                            color = T.accent,
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


        }   // v143: studio expanded
        }   // v143: hidden in FOCUS EQ

        Spacer(Modifier.height(24.dp))
    }
    } }
    Box(modifier = Modifier.fillMaxSize()) {
    GlassBackground()
    mainContent()
    SnackbarHost(
        hostState = snackbarHost,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
    )
    } // end Box

    // ── Build #124: SESSION COMPARISON — factual aggregates only ──
    if (showSessionCompare) {
        AlertDialog(
            containerColor = S.card.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showSessionCompare = false },
            title = { Text("SESSION COMPARISON", color = T.primary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    val sList = try {
                        val f = File(context.filesDir, "sessions.jsonl")
                        if (f.exists()) f.readLines().takeLast(8).reversed().mapNotNull { line ->
                            try { JSONObject(line) } catch (_: Throwable) { null }
                        } else emptyList()
                    } catch (_: Throwable) { emptyList<JSONObject>() }
                    if (sList.size < 2) {
                        Text("Need at least two recorded sessions to compare.", fontSize = 10.sp, color = T.secondary)
                    } else {
                        var idxA by remember { mutableStateOf(-1) }
                        var idxB by remember { mutableStateOf(-1) }
                        Text("Pick two sessions:", fontSize = 10.sp, color = T.secondary)
                        sList.take(6).forEachIndexed { i, o ->
                            val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
                                .format(java.util.Date(System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - o.optLong("start_ms", 0L))))
                            Text(
                                (if (idxA == i) "A " else "") + (if (idxB == i) "B " else "") + t + " · " + o.optLong("dur_ms") / 1000 + "s · " + o.optLong("underruns") + " underruns",
                                fontSize = 10.sp, color = if (idxA == i || idxB == i) T.primary else T.secondary,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { if (idxA == i) idxA = -1 else if (idxB == i) idxB = -1 else if (idxA < 0) idxA = i else if (idxB < 0 && i != idxA) idxB = i }
                                    .padding(vertical = 3.dp)
                            )
                        }
                        if (idxA >= 0 && idxB >= 0) {
                            val a = sList[idxA]; val b = sList[idxB]
                            Spacer(Modifier.height(6.dp))
                            listOf(
                                "Duration" to ((a.optLong("dur_ms") / 1000).toString() + "s") + " / " + ((b.optLong("dur_ms") / 1000).toString() + "s"),
                                "Sample rate" to (a.optInt("sr") / 1000).toString() + "k / " + (b.optInt("sr") / 1000).toString() + "k",
                                "Route" to a.optString("route") + " / " + b.optString("route"),
                                "Underruns" to a.optLong("underruns").toString() + " / " + b.optLong("underruns").toString(),
                                "Clips" to a.optLong("clips").toString() + " / " + b.optLong("clips").toString(),
                                "NaN events" to a.optLong("nan").toString() + " / " + b.optLong("nan").toString(),
                                "Route changes" to a.optInt("route_changes").toString() + " / " + b.optInt("route_changes").toString(),
                                "Buffer changes" to a.optInt("buffer_changes").toString() + " / " + b.optInt("buffer_changes").toString(),
                                "DSP CPU" to (a.optInt("dsp_cpu") / 10.0).toString() + "% / " + (b.optInt("dsp_cpu") / 10.0).toString() + "%"
                            ).forEach { (k, v) ->
                                Text(k + "    " + v, fontSize = 10.sp, color = T.secondary, lineHeight = 15.sp)
                            }
                            Text("Factual comparison — no score or winner is assigned.", fontSize = 8.sp, color = T.secondary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSessionCompare = false }) { Text("CLOSE", color = T.secondary) }
            }
        )
    }

        // ── Build #126: PRESET PREVIEW — actual preset data only ──
        if (previewPresetName != null) {
            val pname = previewPresetName!!
            val pbuiltin = Presets.presets.firstOrNull { it.name == pname }
            val pcustom = customPresets.firstOrNull { it.name == pname }
            val plv = when {
                pbuiltin != null -> Presets.levelsForCount(pbuiltin, bandCount)
                pcustom != null -> Presets.levelsForCount(pcustom, bandCount)
                else -> null
            }
            if (plv != null) {
                val cur = (0 until bandCount).map { round(bandLevels.getOrElse(it) { 0f }).toInt() }
                val tgt = (0 until bandCount).map { plv.getOrNull(it)?.toInt() ?: 0 }
                val changedBands = (0 until bandCount).count { cur[it] != tgt[it] }
                val nz = tgt.filter { it != 0 }
                AlertDialog(
                    containerColor = S.card.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(24.dp),
                    onDismissRequest = { previewPresetName = null },
                    title = { Text(pname, color = T.primary, fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text("Graphic EQ: " + bandCount + " bands · " + changedBands + " differ from current", fontSize = 11.sp, color = T.secondary)
                            Text(
                                "Gain range: " + (if (nz.isEmpty()) "flat" else "%+.1f".format(nz.min().toFloat()) + " to " + "%+.1f".format(nz.max().toFloat()) + " dB"),
                                fontSize = 11.sp, color = T.secondary
                            )
                            if (pcustom != null) {
                                Text(
                                    "Effects: bass " + (if (pcustom.bassBoost > 0) pcustom.bassBoost else "kept") +
                                        " · virt " + (if (pcustom.virtualizer > 0) pcustom.virtualizer else "kept") +
                                        " · loud " + (if (pcustom.loudness > 0) pcustom.loudness else "kept"),
                                    fontSize = 10.sp, color = T.secondary
                                )
                                Text("Stored 0 means 'not set' — live values are kept.", fontSize = 8.sp, color = T.secondary)
                            }
                            Text("Parametric EQ: not part of presets — unchanged.", fontSize = 9.sp, color = T.secondary)
                            Spacer(Modifier.height(6.dp))
                            // v143 23: mini preview graph — current curve (cyan)
                            // vs preset curve (accent). Visualization only.
                            Text("CURVE PREVIEW — current vs " + pname, fontSize = 8.sp, color = T.secondary)
                            Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)
                                .semantics { contentDescription = "Preview: current EQ curve versus " + pname }) {
                                val w = size.width
                                val h = size.height
                                val n = bandCount.coerceAtLeast(2)
                                fun yFor(lvl: Int): Float = h * (1f - ((lvl.toFloat() + 15f) / 35f))
                                fun drawLv(lv: List<Int>, color: Color, widthPx: Float) {
                                    for (i in 0 until n - 1) {
                                        drawLine(
                                            color = color,
                                            start = androidx.compose.ui.geometry.Offset(w * i / (n - 1).toFloat(), yFor(lv.getOrElse(i) { 0 })),
                                            end = androidx.compose.ui.geometry.Offset(w * (i + 1) / (n - 1).toFloat(), yFor(lv.getOrElse(i + 1) { 0 })),
                                            strokeWidth = widthPx
                                        )
                                    }
                                }
                                drawLine(color = T.secondary.copy(alpha = 0.25f),
                                    start = androidx.compose.ui.geometry.Offset(0f, yFor(0)),
                                    end = androidx.compose.ui.geometry.Offset(w, yFor(0)), strokeWidth = 1f)
                                drawLv(tgt, T.accent.copy(alpha = 0.85f), 2.5f)
                                drawLv(cur, T.primary, 2f)
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            pushUndo(true, "Preset: " + pname)
                            markRecent(pname)
                            selectedPreset = pname
                            engine.setSelectedPresetName(pname)
                            val newLevels = FloatArray(31) { 0f }
                            plv.forEachIndexed { i, lvl -> newLevels[i] = lvl.toFloat() }
                            animateLevelsTo(newLevels)
                            if (pcustom != null) {
                                bassBoost = if (pcustom.bassBoost > 0) pcustom.bassBoost else bassBoost
                                virtualizer = if (pcustom.virtualizer > 0) pcustom.virtualizer else virtualizer
                                loudness = if (pcustom.loudness > 0) pcustom.loudness else loudness
                            }
                            engine.applyFullState(
                                ShortArray(31) { i -> round(newLevels[i]).toInt().toShort() },
                                bassBoost, virtualizer, loudness, smooth = true)
                            previewPresetName = null
                        }, colors = ButtonDefaults.buttonColors(containerColor = T.primary)) { Text("APPLY") }
                    },
                    dismissButton = {
                        TextButton(onClick = { previewPresetName = null }) { Text("CANCEL", color = T.secondary) }
                    }
                )
            }
        }

    // ── Build #130: EQ preset picker — bottom sheet over the same store ──
    GlassBottomSheet(visible = showPresetPicker, onDismiss = { showPresetPicker = false }) {
        GradientText("SELECT PRESET", 13.sp, Brush.horizontalGradient(listOf(T.secondary, T.primary)))
        Spacer(Modifier.height(4.dp))
        Text("Tap a preset to open the measured preview, then apply. The full manager (favorites, search, rename, import) lives in SETTINGS & PRESETS.", fontSize = 8.sp, color = T.secondary)
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
            // v142 23/25: grouped browsing — FAVORITES first, then BUILT-IN,
            // then CUSTOM. Same preset data, no duplicate records; the star
            // favorites live directly in the EQ selector.
            val favNames = presetFavs.toList()
            val builtinNames = Presets.presets.map { it.name }
            val customNames = customPresets.map { it.name }
            val grouped = buildList {
                if (favNames.isNotEmpty()) { add("FAVORITES ★" to favNames) }
                add("BUILT-IN" to builtinNames)
                if (customNames.isNotEmpty()) { add("CUSTOM" to customNames) }
            }
            grouped.forEach { (section, names) ->
                Text(section, fontSize = 8.sp, color = T.secondary,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp))
                names.forEach { pname ->
                val sel = pname == selectedPreset
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) T.primary.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable { showPresetPicker = false; previewPresetName = pname }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .semantics { contentDescription = "Preset " + pname + ", opens measured preview" }
                ) {
                    Text(if (sel) "●" else "○", fontSize = 10.sp, color = if (sel) T.primary else T.secondary)
                    Text(pname, fontSize = 11.sp, color = if (sel) T.primary else T.secondary,
                        modifier = Modifier.padding(start = 8.dp))
                }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // ── Build #123: PRESET COMPARE — current vs saved preset (or Flat) ──
    if (showCompareDialog) {
        val target = customPresets.firstOrNull { it.name == selectedPreset }
        val cmpRows = mutableListOf<String>()
        val curP = try { DspParams.load(engine) } catch (_: Throwable) { DspParams() }
        if (target == null) {
            // vs Flat: full DSP-chain diff
            if (curP.preamp != 0f) cmpRows.add("Preamp       " + "%+.1f dB".format(curP.preamp) + "  →  0.0 dB")
            if (curP.bass != 0f) cmpRows.add("Bass         " + "%+.1f dB".format(curP.bass) + "  →  0.0 dB")
            if (curP.treble != 0f) cmpRows.add("Treble       " + "%+.1f dB".format(curP.treble) + "  →  0.0 dB")
            if (curP.width != 1f) cmpRows.add("Stereo       " + "%.0f%%".format(curP.width * 100) + "  →  100%")
            if (curP.compOn) cmpRows.add("Compressor   ON  →  off")
            if (bandLevels.any { it != 0f } || curP.slots.any { it.on }) cmpRows.add("EQ           Custom  →  Flat")
            if (bassBoost != 0 || virtualizer != 0 || loudness != 0) cmpRows.add("Effects      " + bassBoost + "/" + virtualizer + "/" + loudness + "  →  0/0/0")
        } else {
            val tl = Presets.levelsForCount(target, bandCount)
            for (i in 0 until bandCount) {
                val cur = round(bandLevels.getOrElse(i) { 0f }).toInt()
                val saved = tl.getOrNull(i)?.toInt() ?: 0
                if (cur != saved) cmpRows.add("Band " + (i + 1) + "      " + "%+d".format(cur) + "  →  " + "%+d".format(saved) + " dB")
            }
            if (bassBoost != target.bassBoost) cmpRows.add("Bass fx      " + bassBoost + "  →  " + target.bassBoost)
            if (virtualizer != target.virtualizer) cmpRows.add("Virtualizer  " + virtualizer + "  →  " + target.virtualizer)
            if (loudness != target.loudness) cmpRows.add("Loudness     " + loudness + "  →  " + target.loudness)
        }
        AlertDialog(
            containerColor = S.card.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showCompareDialog = false },
            title = { Text("CURRENT vs " + (target?.name ?: "FLAT"), color = T.primary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (cmpRows.isEmpty()) Text("No differences.", fontSize = 10.sp, color = T.secondary)
                    else cmpRows.take(14).forEach { row -> Text(row, fontSize = 10.sp, color = T.secondary, lineHeight = 15.sp) }
                    if (cmpRows.size > 14) Text("… " + (cmpRows.size - 14) + " more", fontSize = 9.sp, color = T.secondary)
                    if (target != null) Text("Custom presets store band levels and effects; DSP-chain settings are not part of them.", fontSize = 8.sp, color = T.secondary)
                }
            },
            confirmButton = {
                if (target != null) {
                    Button(onClick = {
                        pushUndo(true)
                        selectedPreset = target.name
                        engine.setSelectedPresetName(target.name)
                        val lv = Presets.levelsForCount(target, bandCount)
                        val nl = FloatArray(31) { i -> (lv.getOrNull(i)?.toInt() ?: 0).toFloat() }
                        animateLevelsTo(nl)
                        engine.applyFullState(ShortArray(31) { i -> lv.getOrNull(i) ?: 0 }, target.bassBoost, target.virtualizer, target.loudness, smooth = true)
                        showCompareDialog = false
                    }, colors = ButtonDefaults.buttonColors(containerColor = T.primary)) { Text("APPLY PRESET") }
                } else {
                    Button(onClick = { showCompareDialog = false; showResetDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = T.primary)) { Text("RESET TO FLAT") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCompareDialog = false }) { Text("KEEP CURRENT", color = T.secondary) }
            }
        )
    }

    // ── Build #124: PRESET IMPORT — validate, preview, then apply ──
    if (showImportPreset) {
        var impPhase by remember { mutableStateOf(0) }
        var impPreview by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
        AlertDialog(
            containerColor = S.card.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showImportPreset = false; impPhase = 0; impPreview = null },
            title = { Text(if (impPhase == 0) "Import preset" else "IMPORT PRESET", color = T.primary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (impPhase == 0) {
                        OutlinedTextField(
                            value = importPresetInput,
                            onValueChange = { importPresetInput = it },
                            label = { Text("Paste preset JSON") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Only DSP configuration values are read. Malformed input is rejected safely — never silently accepted.", fontSize = 8.sp, color = T.secondary)
                    } else {
                        val (rows, clamped) = impPreview ?: (emptyList<String>() to 0)
                        rows.take(12).forEach { row -> Text(row, fontSize = 10.sp, color = T.secondary, lineHeight = 15.sp) }
                        if (clamped > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text("⚠ IMPORT WARNING\n" + clamped + " value(s) were outside the safe range and were clamped.", fontSize = 10.sp, color = T.accent, lineHeight = 14.sp)
                        } else {
                            Spacer(Modifier.height(4.dp))
                            Text("✓ Valid configuration", fontSize = 10.sp, color = T.primary)
                        }
                    }
                }
            },
            confirmButton = {
                if (impPhase == 0) {
                    Button(onClick = {
                        try {
                            val o = JSONObject(importPresetInput.trim())
                            require(o.optInt("neoneq_preset", 0) == 1) { "not a SonicCore preset" }
                            val arr = o.getJSONArray("levels")
                            require(arr.length() in 10..31) { "bad band count" }
                            var clampedCount = 0
                            val lv = ShortArray(31) { i ->
                                val raw = arr.optInt(minOf(i, arr.length() - 1))
                                val cl = raw.coerceIn(-15, 20)
                                if (cl != raw) clampedCount++
                                cl.toShort()
                            }
                            fun clamp01(name: String, raw: Int, max: Int): Int {
                                val cl = raw.coerceIn(0, max)
                                if (cl != raw) clampedCount++
                                return cl
                            }
                            val bb = clamp01("bassBoost", o.optInt("bassBoost", 0), 300)
                            val vv = clamp01("virtualizer", o.optInt("virtualizer", 0), 300)
                            val ll = clamp01("loudness", o.optInt("loudness", 0), 300)
                            val rows = mutableListOf(
                                "Preamp  " + "%+.1f dB".format(o.optDouble("preamp", 0.0)),
                                "Bass    " + "%+.1f dB".format(o.optDouble("bass", 0.0)),
                                "Treble  " + "%+.1f dB".format(o.optDouble("treble", 0.0)),
                                "EQ      " + arr.length() + " bands",
                                "Limiter " + (if (o.optBoolean("limiterOn", true)) "ON" else "off")
                            )
                            impPreview = rows to clampedCount
                            impPhase = 1
                        } catch (t: Throwable) {
                            scope2.launch { snackbarHost.showSnackbar("Import failed — no settings changed") }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = T.primary)) { Text("VALIDATE") }
                } else {
                    Button(onClick = {
                        try {
                            val o = JSONObject(importPresetInput.trim())
                            val arr = o.getJSONArray("levels")
                            val lv = ShortArray(31) { i -> (if (i < arr.length()) arr.optInt(i) else 0).coerceIn(-15, 20).toShort() }
                            val bb = o.optInt("bassBoost", 0).coerceIn(0, 300)
                            val vv = o.optInt("virtualizer", 0).coerceIn(0, 300)
                            val ll = o.optInt("loudness", 0).coerceIn(0, 300)
                            val name = "Imported " + java.text.SimpleDateFormat("HHmm", java.util.Locale.ROOT).format(java.util.Date())
                            engine.saveCustomPreset(name, lv, bb, vv, ll)
                            customPresets = engine.listCustomPresets()
                            scope2.launch { snackbarHost.showSnackbar("Preset imported: " + name) }
                        } catch (t: Throwable) {
                            scope2.launch { snackbarHost.showSnackbar("Import failed — no settings changed") }
                        }
                        showImportPreset = false; impPhase = 0; impPreview = null
                    }, colors = ButtonDefaults.buttonColors(containerColor = T.primary)) { Text("IMPORT") }
                }
            },
            dismissButton = {
                Row {
                    if (impPhase == 1) TextButton(onClick = { impPhase = 0 }) { Text("BACK", color = T.secondary) }
                    TextButton(onClick = { showImportPreset = false; impPhase = 0; impPreview = null }) { Text("CANCEL", color = T.secondary) }
                }
            }
        )
    }

    // ── Build #124: FIRST-RUN GUIDE 2.0 — paged, completion saved locally ──
    LaunchedEffect(Unit) {
        try {
            val sp = context.getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE)
            if (!sp.getBoolean("guide_v124", false)) {
                sp.edit().putBoolean("guide_v124", true).apply()
                showGuide = true
            }
        } catch (_: Throwable) { }
    }
    if (showGuide) {
        var guidePage by remember { mutableStateOf(0) }
        val guidePages = listOf(
            "WELCOME TO SONICCORE" to "Professional Audio Processing.\n\nReal-time Android DSP. Capture, process, and verify — with honest measurements at every stage.",
            "CHOOSE YOUR SOUND" to "Pick a preset (★ star your favorites) or drag the EQ bands on the EQ tab. Undo always brings you back.",
            "WATCH THE SIGNAL" to "The SIGNAL PATH panel shows capture → DSP → output from measured frame counters — never inferred.",
            "VERIFY PROCESSING" to "Processing and output delivery are measured facts. The audible result is honestly marked as not directly verifiable.",
            "IMPORTANT" to "Android may continue playing the original source audio. SonicCore processes the audio it captures through its own pipeline and cannot mute or replace the source app."
        )
        AlertDialog(
            containerColor = S.card.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showGuide = false },
            title = { Text(guidePages[guidePage].first, color = T.primary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(guidePages[guidePage].second, fontSize = 11.sp, color = T.secondary, lineHeight = 17.sp)
                    Spacer(Modifier.height(6.dp))
                    Text((guidePage + 1).toString() + " / " + guidePages.size, fontSize = 9.sp, color = T.secondary)
                }
            },
            confirmButton = {
                if (guidePage < guidePages.size - 1) {
                    Button(onClick = { guidePage++ }, colors = ButtonDefaults.buttonColors(containerColor = T.primary)) { Text("NEXT") }
                } else {
                    Button(onClick = { showGuide = false }, colors = ButtonDefaults.buttonColors(containerColor = T.primary)) { Text("DONE") }
                }
            },
            dismissButton = {
                Row {
                    if (guidePage > 0) TextButton(onClick = { guidePage-- }) { Text("BACK", color = T.secondary) }
                    TextButton(onClick = { showGuide = false }) { Text("SKIP", color = T.secondary) }
                }
            }
        )
    }

    // ── Build #122: RESET confirmation dialog — shows the exact diff
    // before any change; CANCEL preserves everything untouched. ──
    if (showResetDialog) {
        AlertDialog(
            containerColor = S.card.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset DSP settings?", color = T.primary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "This will restore the current DSP chain to the default Flat configuration. Saved presets and session history will not be deleted.",
                        fontSize = 11.sp, color = T.secondary
                    )
                    Spacer(Modifier.height(10.dp))
                    val cur = try { DspParams.load(engine) } catch (_: Throwable) { DspParams() }
                    val diffRows = mutableListOf<String>()
                    if (cur.preamp != 0f) diffRows.add("Preamp       " + "%+.1f dB".format(cur.preamp) + "  →  0.0 dB")
                    if (cur.bass != 0f) diffRows.add("Bass         " + "%+.1f dB".format(cur.bass) + "  →  0.0 dB")
                    if (cur.treble != 0f) diffRows.add("Treble       " + "%+.1f dB".format(cur.treble) + "  →  0.0 dB")
                    if (cur.width != 1f) diffRows.add("Stereo       " + "%.0f%%".format(cur.width * 100) + "  →  100%")
                    if (cur.balance != 0f || cur.mono || cur.swap) diffRows.add("Balance/mono Custom  →  Default")
                    if (bandLevels.any { it != 0f } || cur.slots.any { it.on }) diffRows.add("EQ           Custom  →  Flat")
                    if (cur.compOn) diffRows.add("Compressor   ON      →  Default")
                    if (!cur.limiterOn || cur.limThresh != -1f) diffRows.add("Limiter      Custom  →  Default")
                    Text("Current → Flat", fontSize = 11.sp, color = T.accent)
                    Spacer(Modifier.height(4.dp))
                    if (diffRows.isEmpty()) {
                        Text("Already Flat — nothing to change.", fontSize = 10.sp, color = T.secondary)
                    } else {
                        diffRows.forEach { row -> Text(row, fontSize = 10.sp, color = T.secondary, lineHeight = 15.sp) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Applied through the atomic parameter-target system at the next DSP block boundary. Capture, counters, route, and buffer state are not touched.",
                        fontSize = 9.sp, color = T.secondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        try {
                            if (!NeonDsp.available) throw IllegalStateException("native DSP unavailable")
                            // 1. build the complete Flat configuration (valid by construction),
                            // 2. submit through the atomic target system (seqlock + block commit),
                            // 3. persist, 4. update UI from the committed configuration.
                            pushUndo(true, "Reset to Flat")
                            val flat = DspParams()
                            flat.applyTo(NeonDsp)
                            flat.save(engine)
                            animateLevelsTo(FloatArray(31) { 0f })
                            selectedPreset = "Flat"
                            engine.setSelectedPresetName("Flat")
                            bassBoost = 0; virtualizer = 0; loudness = 0
                            engine.applyFullState(ShortArray(31) { 0 }, 0, 0, 0, smooth = true)
                            Toast.makeText(context, "DSP reset to Flat ✓", Toast.LENGTH_SHORT).show()
                        } catch (t: Throwable) {
                            // no partial reset — previous known-good configuration preserved
                            Toast.makeText(context, "Reset failed — previous DSP settings were preserved.", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = T.accent)
                ) { Text("RESET DSP") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("CANCEL", color = T.secondary) }
            }
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            containerColor = S.card.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save current EQ as preset", color = T.primary, fontWeight = FontWeight.Bold) },
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
                            val levels = ShortArray(31) { i -> if (i < bandCount) round(bandLevels.getOrElse(i) { 0f }).toInt().toShort() else 0 }
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
            containerColor = S.card.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showOverwriteDialog = false },
            title = { Text("Overwrite preset?", color = T.primary, fontWeight = FontWeight.Bold) },
            text = { Text("A preset named '$pendingPresetName' already exists. Overwrite it with current settings?") },
            confirmButton = {
                TextButton(onClick = {
                    val name = pendingPresetName
                    val levels = ShortArray(31) { i -> if (i < bandCount) round(bandLevels.getOrElse(i) { 0f }).toInt().toShort() else 0 }
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
                    val levels = ShortArray(31) { i -> if (i < bandCount) round(bandLevels.getOrElse(i) { 0f }).toInt().toShort() else 0 }
                    engine.updateCustomPreset(preset.name, levels, bassBoost, virtualizer, loudness)
                    customPresets = engine.listCustomPresets()
                    scope2.launch { snackbarHost.showSnackbar("Updated '${'$'}{preset.name}'") }
                    menuPreset = null
                }
            )
            DropdownMenuItem(
                text = { Text(if (preset.name in presetFavs) "☆ Unfavorite" else "★ Favorite") },
                onClick = {
                    toggleFav(preset.name)
                    menuPreset = null
                }
            )
            DropdownMenuItem(
                text = { Text("Export") },
                onClick = {
                    try {
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_TEXT, org.json.JSONObject().apply {
                                    put("neoneq_preset", 1)
                                    put("name", preset.name)
                                    val larr = org.json.JSONArray()
                                    for (lvl in preset.levels) larr.put(lvl.toInt())
                                    put("levels", larr)
                                    put("bassBoost", preset.bassBoost)
                                    put("virtualizer", preset.virtualizer)
                                    put("loudness", preset.loudness)
                                }.toString())
                                putExtra(Intent.EXTRA_SUBJECT, "SonicCore preset — DSP configuration only")
                            }, "Share preset"))
                    } catch (t: Throwable) { }
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
                text = { Text("Delete", color = T.accent) },
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
            containerColor = S.card.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename preset", color = T.primary, fontWeight = FontWeight.Bold) },
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
                    if (engine.customPresetExists(newName) && newName != renamingFrom) {
                        scope2.launch { snackbarHost.showSnackbar("Name already exists — pick another") }
                    } else if (newName.isNotEmpty() && newName != renamingFrom) {
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
            containerColor = S.card.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showSettings = false },
            title = { Text("Settings", color = T.primary, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── Build #89: App theme ──
                    val ctx = LocalContext.current
                    Column {
                        Text("App Theme", fontSize = 14.sp, color = S.text)
                        Text("Accent palette for the entire UI", fontSize = 11.sp, color = Color.Gray)
                        Spacer(Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Themes.ALL.forEach { t ->
                                val selected = appThemeState.value.id == t.id
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) t.primary.copy(alpha = 0.12f) else S.cardDeep)
                                        .border(1.dp, if (selected) t.primary else S.borderDim, RoundedCornerShape(12.dp))
                                        .clickable {
                                            appThemeState.value = t
                                            try {
                                                ctx.getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE)
                                                    .edit().putString("theme", t.id).apply()
                                            } catch (_: Throwable) { }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        listOf(t.primary, t.secondary, t.accent).forEach { c ->
                                            Box(Modifier.size(14.dp).clip(CircleShape).background(c))
                                            Spacer(Modifier.width(6.dp))
                                        }
                                    }
                                    Text(t.label, fontSize = 13.sp,
                                        color = if (selected) t.primary else S.textSoft,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }

                    // Start on boot
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Start on Boot", fontSize = 14.sp, color = S.text)
                            Text("Auto-start EQ after device reboot", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = startOnBoot,
                            onCheckedChange = {
                                startOnBoot = it
                                engine.setStartOnBoot(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = T.primary,
                                checkedTrackColor = T.primary.copy(alpha = 0.3f)
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
                            Text("Auto-apply Preset", fontSize = 14.sp, color = S.text)
                            Text("Restore last preset on app launch", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = autoApplyPreset,
                            onCheckedChange = {
                                autoApplyPreset = it
                                engine.setAutoApplyPreset(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = T.primary,
                                checkedTrackColor = T.primary.copy(alpha = 0.3f)
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
                            Text("Visualizer", fontSize = 14.sp, color = S.text)
                            Text("Show live spectrum bars", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = showVisualizer,
                            onCheckedChange = {
                                showVisualizer = it
                                engine.setShowVisualizer(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = T.primary,
                                checkedTrackColor = T.primary.copy(alpha = 0.3f)
                            )
                        )
                    }
                    // Visualizer style picker
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Visualizer Style", fontSize = 14.sp, color = S.text)
                        Text("Bars, wave or circle pulse", fontSize = 11.sp, color = Color.Gray)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("bars" to "Bars", "wave" to "Wave", "circle" to "Circle").forEach { (key, label) ->
                                val sel = visStyle == key
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    color = if (sel) Color(0xFF0D0D14) else T.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (sel) T.primary else T.primary.copy(alpha = 0.12f))
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
                            Text("Breathing Glow", fontSize = 14.sp, color = S.text)
                            Text("Animated glow behind header", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = showGlow,
                            onCheckedChange = {
                                showGlow = it
                                engine.setShowGlow(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = T.primary,
                                checkedTrackColor = T.primary.copy(alpha = 0.3f)
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
                            color = T.primary,
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
                                    context.startActivity(Intent.createChooser(share, "Backup SonicCore"))
                                } catch (_: Throwable) {
                                    scope2.launch { snackbarHost.showSnackbar("Backup failed") }
                                }
                            }
                        )
                        Text(
                            "⇪ Restore",
                            fontSize = 12.sp,
                            color = T.primary,
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
                    // Build #89: live engine diagnostics — the on-device window
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
                            diagText = try {
                                engine.diagnostics() + "\nplayer: " + SoftEqPlayer.line() + " | tone: " + TonePlayer.line() + " | dspErr: " + (SoftEqPlayer.lastError ?: "-")
                            } catch (t: Throwable) { "diag error: ${t.message}" }
                            delay(1000)
                        }
                    }
                    Text(
                        diagText,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                        color = T.primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    // Build #97: one-tap copy — paste the readout straight into
                    // chat instead of transcribing a live-updating screen.
                    val diagClipboard = LocalClipboardManager.current
                    Button(
                        onClick = { diagClipboard.setText(AnnotatedString(diagText)) },
                        colors = ButtonDefaults.buttonColors(containerColor = T.secondary.copy(alpha = 0.25f)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("COPY DIAGNOSTICS", fontSize = 10.sp, letterSpacing = 1.sp, color = T.accent)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "SonicCore · Build #137 · v3.5.1",
                        fontSize = 10.sp,
                        color = T.secondary,
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
            containerColor = S.card.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import presets", color = T.primary, fontWeight = FontWeight.Bold) },
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
                        Text(importResultMsg, fontSize = 11.sp, color = T.primary)
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
            containerColor = S.card.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Restore backup", color = T.primary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Paste a SonicCore backup JSON to restore all settings. This replaces current bands, effects, presets and toggles.", fontSize = 11.sp, color = Color.Gray)
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
                        Text(restoreResultMsg, fontSize = 11.sp, color = T.accent)
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
// ── Build #130: GLASS DESIGN SYSTEM — reusable, accessible components ──
// State colors: active cyan · muted violet-gray · warning amber · error red.
// Never communicates state through color alone: every chip carries a glyph.
// ── Build #132: SONICCORE IDENTITY + CONSOLE PRIMITIVES ──────────────────

// Compact "SC" monogram — cyan-to-violet gradient, geometric, minimal.
// Used in the header and the splash screen. No external image asset.
@Composable
fun SCMark(size: androidx.compose.ui.unit.Dp = 34.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.4f))
            .background(Brush.linearGradient(listOf(T.primary, T.secondary)))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(size / 3.4f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "SC", color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp, letterSpacing = 0.sp
        )
    }
}

// A single L/R stereo meter — filled bars driven by real NeonDsp millibel
// reads (never a fake animation). dBFromMb converts the engine's mB scale
// (0 = -100dB, 10000 = 0dB) to a 0f..1f fill fraction for the bar.
private fun meterFraction(mb: Int): Float = ((mb / 100f + 60f) / 60f).coerceIn(0f, 1f)

@Composable
fun StereoMeterBar(label: String, lMb: Int, rMb: Int, clipping: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 9.sp, color = T.secondary, letterSpacing = 1.sp, modifier = Modifier.width(28.dp))
            if (clipping) {
                Spacer(Modifier.width(4.dp))
                Text("⚠", fontSize = 9.sp, color = StatusColors.error)
            }
        }
        listOf("L" to lMb, "R" to rMb).forEach { (ch, mb) ->
            val frac by animateFloatAsState(meterFraction(mb), tween(90), label = "meter$ch")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(ch, fontSize = 8.sp, color = T.secondary, modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(S.borderDim.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(frac)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (clipping) StatusColors.error else T.primary)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    String.format("%.1f dB", mb / 100f), fontSize = 8.sp, color = T.secondary,
                    modifier = Modifier.width(42.dp)
                )
            }
        }
    }
}


@Composable
fun GlassChip(label: String, state: Int, modifier: Modifier = Modifier) {
    // Build #132: semantic status colors (spec §5) — cyan keeps the
    // "processing" meaning on ACTIVE, the rest are fixed status colors.
    val (col, glyph) = when (state) {
        0 -> T.primary to "●"                    // ACTIVE / processing
        2 -> StatusColors.warn to "⚠"            // WARNING
        3 -> StatusColors.error to "✗"           // ERROR
        4 -> StatusColors.inactive to "·"        // NEUTRAL
        else -> StatusColors.inactive to "○"     // MUTED / inactive
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(S.cardDeep.copy(alpha = 0.55f))
            .border(1.dp, col.copy(alpha = 0.25f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp)
            .semantics { contentDescription = label }
    ) {
        Text(glyph, fontSize = 8.sp, color = col)
        Text(label, fontSize = 9.sp, color = col, modifier = Modifier.padding(start = 4.dp))
    }
}

// Compact pill button. kind: 0 PRIMARY · 1 SECONDARY · 2 DESTRUCTIVE
@Composable
fun GlassButton(label: String, kind: Int = 0, enabled: Boolean = true, onClick: () -> Unit) {
    val col = when (kind) { 2 -> T.accent; 1 -> T.secondary; else -> T.primary }
    Text(
        label, fontSize = 10.sp,
        color = if (enabled) col else col.copy(alpha = 0.40f),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background((if (enabled) col else col.copy(alpha = 0.4f)).copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp)
    )
}

// Section group header — hairline gradient rules, always with a text label.
@Composable
fun GlassSection(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, T.primary.copy(alpha = 0.35f)))))
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            color = T.secondary, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp)
            .background(Brush.horizontalGradient(listOf(T.primary.copy(alpha = 0.35f), Color.Transparent))))
    }
}

// Technical value — monospace numeric treatment ("48 kHz", "12.4 ms").
@Composable
fun TechValue(text: String, fontSize: TextUnit = 10.sp, color: Color = T.secondary) {
    Text(text, fontSize = fontSize, color = color, fontFamily = FontFamily.Monospace)
}

// Bottom-anchored glass sheet — stable Dialog implementation, no
// experimental APIs. Scrim dismisses; grab handle marks it draggable-looking.
@Composable
fun GlassBottomSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!visible) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().clickable { onDismiss() }) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .shadow(12.dp, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(S.card.copy(alpha = 0.97f))
                    .border(1.dp, Brush.verticalGradient(listOf(T.primary.copy(alpha = 0.22f), T.secondary.copy(alpha = 0.10f))), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 20.dp),
            ) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(50))
                    .background(T.secondary.copy(alpha = 0.35f)).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // Build #127: PRIMARY GLASS — translucent tonal surface, gradient hairline
    // border (stronger at the top edge as a highlight), subtle elevation.
    // Intensity follows the user's glass setting; fully opaque never.
    val a = glassSurfaceAlpha()
    val bA = glassBorderAlpha()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(5.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(S.card.copy(alpha = a), S.cardAlt.copy(alpha = a))))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(T.primary.copy(alpha = bA), T.secondary.copy(alpha = bA * 0.55f))),
                RoundedCornerShape(20.dp)
            )
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
                    colors = listOf(T.primary.copy(alpha = alpha), Color.Transparent)
                )
            )
    )
}

// Build #114: scans a SAF tree for audio files — Poweramp-style folder
// browsing with zero permissions. BFS over subfolders, capped at 500 tracks
// so slow storage on low-end devices can't stall the UI. Fully qualified
// DocumentsContract keeps this dependency-free.
private fun scanAudioFolder(resolver: android.content.ContentResolver, tree: Uri): List<Pair<String, Uri>> {
    val out = ArrayList<Pair<String, Uri>>()
    try {
        val queue = ArrayDeque<String>()
        queue.add(android.provider.DocumentsContract.getTreeDocumentId(tree))
        val proj = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val audioExt = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma")
        while (queue.isNotEmpty() && out.size < 500) {
            val dirId = queue.removeFirst()
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
            val cur = resolver.query(children, proj, null, null, null) ?: continue
            cur.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2) ?: ""
                    if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) queue.add(id)
                    else {
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (mime.startsWith("audio/") || ext in audioExt)
                            out.add(name to android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }
        }
    } catch (_: Throwable) { }
    out.sortBy { it.first.lowercase() }
    return out
}

// Live spectrum visualizer rendered from raw waveform bytes off the master mix.
// Degrades to a gentle idle pulse if no waveform data is available yet (permission
// denied, unsupported device, or nothing playing) — and since Build #89 also when
// the EQ toggle is OFF, or (Build #89) when capture data goes stale mid-playback
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
    // Build #89: shared amplitude smoother — attack fast, release slow, so
    // the 30Hz capture steps render as fluid motion instead of jumpy snaps.
    val smooth = remember { FloatArray(64) { 0f } }
    val waveYs = remember { FloatArray(64) }
    var tick by remember { mutableIntStateOf(0) }

    // ---- Build #89: zero steady-state allocations in the visualizer ----
    // This composable redraws EVERY frame (idle breathing + live waveform),
    // so every object below is created once and reused. The previous version
    // allocated per frame: wave = 2 Paths + 1 FloatArray + 3 brushes,
    // circle = 41 gradient brushes, bars = 32 positioned gradient brushes.
    val waveMainPath = remember { Path() }
    val waveMirrorPath = remember { Path() }
    val waveAmps = remember { FloatArray(64) }
    val waveBrush = remember(appThemeState.value) { Brush.horizontalGradient(listOf(T.secondary, T.primary)) }
    val waveGlowColor = remember { T.primary.copy(alpha = 0.25f) }
    val waveMirrorColor = remember { T.secondary.copy(alpha = 0.15f) }
    val spokeBrush = remember(appThemeState.value) { Brush.linearGradient(listOf(T.secondary, T.primary)) }
    val coreBrush = remember(appThemeState.value) { Brush.radialGradient(listOf(T.secondary, T.secondary.copy(alpha = 0f))) }
    val barBrush = remember(appThemeState.value) { Brush.verticalGradient(listOf(T.secondary, T.primary)) }
    val peakColor = remember { T.primary.copy(alpha = 0.7f) }
    val barGlowColor = remember { T.primary.copy(alpha = 0.08f) }

    // Drive peak decay at ~30fps — cheaper than recomposing the whole tree.
    // Build #136: 8 FPS when the app is backgrounded (Phase-14 tiering).
    var vdForeground by remember { mutableStateOf(true) }
    val vdLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(vdLifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            vdForeground = ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME
        }
        vdLifecycleOwner.lifecycle.addObserver(obs)
        onDispose { vdLifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(vdForeground) {
        while (true) {
            kotlinx.coroutines.delay(if (vdForeground) 33 else 125)
            for (i in 0 until barCount) peaks[i] = (peaks[i] - 0.015f).coerceAtLeast(0f)
            tick++
        }
    }

    Canvas(modifier = Modifier.fillMaxWidth().height(64.dp)) {
        // Extract the amplitude for a single logical bar — shared by all three
        // styles so they react identically to the same waveform data.
        // `live` = EQ on AND fresh capture data. The freshness check (Build #89)
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
                val target = ((sum / (endIdx - startIdx)) / 128f).coerceIn(0.03f, 1f)
                val rate = if (target > smooth[i]) 0.5f else 0.16f
                smooth[i] += (target - smooth[i]) * rate
                return smooth[i]
            }
            val wave = kotlin.math.sin((i / count.toFloat() + idlePhase) * Math.PI * 2).toFloat()
            val target = (0.08f + 0.05f * wave).coerceIn(0.03f, 0.2f)
            val rate = if (target > smooth[i]) 0.5f else 0.16f
            smooth[i] += (target - smooth[i]) * rate
            return smooth[i]
        }

        when (style) {
            "wave" -> {
                // Smooth glowing line traced through 64 sample points.
                // Build #89: paths, amp buffer and brushes are hoisted and
                // reset() per frame — the wave costs zero allocations now.
                val points = 64
                val stepX = size.width / (points - 1).toFloat()
                for (i in 0 until points) {
                    val amp = ampFor(i, points)
                    waveYs[i] = size.height / 2f - (amp - 0.03f) * size.height * 0.8f
                }
                waveMainPath.reset()
                waveMirrorPath.reset()
                // Build #89: quadratic Bézier through segment midpoints —
                // continuous curve instead of visibly kinked lineTo joins.
                waveMainPath.moveTo(0f, waveYs[0])
                waveMirrorPath.moveTo(0f, size.height - (size.height - waveYs[0]) * 0.5f)
                for (i in 1 until points - 1) {
                    val x = i * stepX
                    val y = waveYs[i]
                    val ym = size.height / 2f + (size.height / 2f - y) * 0.25f
                    val midX = x + stepX / 2f
                    waveMainPath.quadraticBezierTo(x, y, midX, (y + waveYs[i + 1]) / 2f)
                    waveMirrorPath.quadraticBezierTo(x, ym, midX, (ym + (size.height / 2f + (size.height / 2f - waveYs[i + 1]) * 0.25f)) / 2f)
                }
                val lastX = (points - 1) * stepX
                waveMainPath.lineTo(lastX, waveYs[points - 1])
                waveMirrorPath.lineTo(lastX, size.height / 2f + (size.height / 2f - waveYs[points - 1]) * 0.25f)
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
                    // Build #89: gentle continuous rotation (~0.9 rad/s) —
                    // the ring feels alive even while idle.
                    val angle = (i / spokes.toFloat()) * Math.PI * 2 + tick * 0.03f
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
            // Build #89: shared ampFor — bars now use the exact same
            // extraction (and the Build #83 smoother) as wave and circle.
            val amp = ampFor(i, barCount)
            val barH = (size.height * 0.9f * amp).coerceAtLeast(3f)
            val x = i * slotWidth + (slotWidth - barWidthPx) / 2f

            // Update peak — only rises, decays over time
            if (barH > peaks[i]) peaks[i] = barH
            val peakH = peaks[i]

            // Build #89: soft glow underlay — same bar, wider and faint.
            drawRoundRect(
                color = barGlowColor,
                topLeft = Offset(x - 2f, midY - barH / 2f - 2f),
                size = Size(barWidthPx + 4f, barH + 4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f)
            )
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
    onResetBand: (Int) -> Unit = {},
    onBandTap: (Int) -> Unit = {},
    scaleDb: Float = 18f,
    selectedBand: Int = -1,
    onBandSelect: (Int) -> Unit = {},
    precisionMode: Boolean = false,
    onGestureStart: (Int) -> Unit = {},
    // v142 EQ Pro: 0 = curve only, 1 = live spectrum only, 2 = spectrum + curve.
    // spectrumBins are the SHARED analyzer poll (real PCM data — the visualizer's
    // spBars), never synthetic. Log-spaced native bins map linearly onto the
    // log-frequency x-axis, same as the big analyzer.
    analyzerMode: Int = 0,
    spectrumBins: FloatArray? = null,
    // v143: larger immersive graph (§5) + optional peak-hold trace (§8)
    graphHeight: Dp = 280.dp,
    spectrumHold: FloatArray? = null,
    showHold: Boolean = false
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    // ---- Build #126: professional log-frequency EQ graph ----
    // 20 Hz -> 20 kHz spans the track logarithmically (decade grid), band
    // points sit at their true log positions, touch selects the NEAREST
    // band within a generous radius, and PRECISION mode switches dragging
    // to relative movement at 0.25x rate. All hoisted paints are reused —
    // the steady-state draw allocates nothing measurable.
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val curvePath = remember { Path() }
    val gridPath = remember { Path() }
    val specPath = remember { Path() }
    val curveBrush = remember(appThemeState.value) { Brush.horizontalGradient(listOf(T.primary, T.secondary)) }
    val curveGlow = T.primary.copy(alpha = 0.20f)
    val gridColor = T.secondary.copy(alpha = 0.14f)
    val centerColor = T.primary.copy(alpha = 0.30f)
    val bubbleBg = T.primary.copy(alpha = 0.16f)
    val bubbleBorder = T.primary.copy(alpha = 0.55f)
    val bubbleText = android.graphics.Color.rgb(220, 248, 255)
    val grayLabel = android.graphics.Color.rgb(140, 140, 158)
    val topsX = remember { FloatArray(31) }
    val topsY = remember { FloatArray(31) }
    val bandX = remember(bands, bandCount) { FloatArray(bandCount.coerceAtMost(31)) { i ->
        // log position: 20 Hz at x=0, 20 kHz at x=1
        val f = (bands.getOrNull(i)?.freq ?: 1000).coerceAtLeast(20)
        (kotlin.math.log10(f.toFloat() / 20f) / 3f).coerceIn(0f, 1f)
    } }
    var activeBand by remember { mutableIntStateOf(-1) }

    val labelAreaPx = with(density) { 46.dp.toPx() }
    val freqSizePx = with(density) { 10.sp.toPx() }
    val lvlSizePx = with(density) { 10.sp.toPx() }
    val dbLabelPx = with(density) { 8.sp.toPx() }
    val curveGlowPx = with(density) { 6.dp.toPx() }
    val curvePx = with(density) { 2.dp.toPx() }
    val gridPx = 1f
    val handlePx = with(density) { 4.dp.toPx() }
    val touchRadiusPx = with(density) { 28.dp.toPx() }

    // Visual y-mapping follows scaleDb (±6/±12/±18); the DSP keeps -15..+20.
    fun levelFromY(y: Float, trackHeight: Float): Float {
        val clampedY = y.coerceIn(0f, trackHeight)
        val normY = 1f - (clampedY / trackHeight)
        return (normY * (scaleDb * 2f) - scaleDb).coerceIn(-15f, 20f)
    }
    // x is NORMALIZED 0..1 (log-frequency fraction of canvas width) —
    // callers must divide pixel coordinates by size.width (Build #136 fix:
    // raw pixels vs 0..1 fractions made every gesture hit the last band).
    fun nearestBand(x: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until bandCount) {
            val d = kotlin.math.abs(x - bandX[i])
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(graphHeight)
            .pointerInput(bandCount, precisionMode, scaleDb) {
                var lastY = 0f
                var dragLvl = 0f
                detectDragGestures(
                    onDragStart = { offset ->
                        val trackHeight = size.height.toFloat() - labelAreaPx
                        val band = nearestBand(offset.x / size.width.toFloat())
                        val lvl = levelFromY(offset.y, trackHeight)
                        onGestureStart(band)   // one undo entry for the whole gesture
                        onLevelChange(band, lvl)
                        activeBand = band
                        lastY = offset.y
                        dragLvl = lvl
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = { activeBand = -1 },
                    onDragCancel = { activeBand = -1 },
                    onDrag = { change, _ ->
                        val trackHeight = size.height.toFloat() - labelAreaPx
                        val band = activeBand.coerceAtLeast(nearestBand(change.position.x / size.width.toFloat()))
                        val lvl = if (precisionMode) {
                            // PRECISION: relative movement at 0.25x rate —
                            // continuous updates, never waiting for release.
                            val dbPerPx = (scaleDb * 2f) / trackHeight
                            dragLvl + (lastY - change.position.y) * dbPerPx * 0.25f
                        } else {
                            levelFromY(change.position.y, trackHeight)
                        }
                        lastY = change.position.y
                        dragLvl = lvl
                        onLevelChange(band, lvl)
                        activeBand = band
                        change.consume()
                    }
                )
            }
            .pointerInput(bandCount) {
                // Build #124/126: tap = select nearest band (never opens the
                // editor), double-tap = reset that band, long-press = editor.
                detectTapGestures(
                    onTap = { offset ->
                        val band = nearestBand(offset.x / size.width.toFloat())
                        onBandSelect(band)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onDoubleTap = { offset ->
                        val band = nearestBand(offset.x / size.width.toFloat())
                        onResetBand(band)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onLongPress = { offset ->
                        onBandTap(nearestBand(offset.x / size.width.toFloat()))
                    }
                )
            }
    ) {
        val trackHeight = size.height - labelAreaPx

        // ---- Frequency grid: log decades 20 Hz .. 20 kHz ----
        val gridFreqs = listOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)
        val gridLabels = listOf("20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k")
        // adaptive density — on narrow screens show every other label
        val labelStep = if (size.width < 420f) 2 else 1
        gridPath.reset()
        gridFreqs.forEachIndexed { gi, f ->
            val x = size.width * (kotlin.math.log10(f / 20f) / 3f)
            gridPath.moveTo(x, 0f)
            gridPath.lineTo(x, trackHeight)
            if (gi % labelStep == 0) {
                drawIntoCanvas {
                    labelPaint.textSize = freqSizePx
                    labelPaint.color = grayLabel
                    it.nativeCanvas.drawText(gridLabels[gi], x, trackHeight + labelAreaPx * 0.5f, labelPaint)
                }
            }
        }
        drawPath(gridPath, color = gridColor, style = Stroke(width = gridPx))

        // ---- dB grid: horizontal lines + compact axis labels ----
        val dbStep = scaleDb / 3f
        var v = -scaleDb
        while (v <= scaleDb + 0.01f) {
            val y = trackHeight * 0.5f - (v / (scaleDb * 2f)) * trackHeight
            val isCenter = kotlin.math.abs(v) < 0.01f
            drawLine(
                color = if (isCenter) centerColor else gridColor,
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(size.width, y),
                strokeWidth = if (isCenter) gridPx * 1.5f else gridPx
            )
            // v143 §6: per-line dB value labels on wide screens
            if (size.width >= 380f) {
                drawIntoCanvas {
                    labelPaint.textSize = dbLabelPx
                    labelPaint.color = grayLabel
                    it.nativeCanvas.drawText(
                        (if (v > 0.01f) "+" else "") + v.toInt().toString(),
                        size.width - 3f,
                        y - 2f,
                        labelPaint
                    )
                }
            }
            v += dbStep
        }
        drawIntoCanvas {
            labelPaint.textSize = dbLabelPx
            labelPaint.color = grayLabel
            labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
            it.nativeCanvas.drawText("+" + scaleDb.toInt(), size.width - 2f, freqSizePx, labelPaint)
            it.nativeCanvas.drawText("-" + scaleDb.toInt(), size.width - 2f, trackHeight - 2f, labelPaint)
            labelPaint.textAlign = android.graphics.Paint.Align.CENTER
        }

        // ---- v142: live spectrum overlay (real analyzer data) ----
        // mode 1/2 draw the shared analyzer bins as a dimmed filled area behind
        // everything else; per-frame cost is one path reuse — no allocations.
        if (analyzerMode >= 1) {
            val bins = spectrumBins
            if (bins != null && bins.size >= 2) {
                val nB = bins.size
                specPath.reset()
                specPath.moveTo(0f, trackHeight)
                for (i in 0 until nB) {
                    val bx = size.width * i / (nB - 1).toFloat()
                    val mag = bins[i].coerceIn(0f, 1f)
                    val by = trackHeight * (1f - mag * 0.94f)
                    specPath.lineTo(bx, by)
                }
                specPath.lineTo(size.width, trackHeight)
                specPath.close()
                drawPath(
                    specPath,
                    brush = Brush.verticalGradient(
                        listOf(T.primary.copy(alpha = 0.26f), T.primary.copy(alpha = 0.03f)),
                        startY = 0f, endY = trackHeight
                    )
                )
                // v143 §8: peak-hold trace — same decay the big analyzer uses
                // (spHold), a thin accent line per bin. No extra polling.
                if (showHold) {
                    val hold = spectrumHold
                    if (hold != null && hold.size >= 2) {
                        val nH = hold.size
                        val holdPx = 1.5f
                        for (i in 0 until nH) {
                            val hv = hold[i]
                            if (hv <= 0.02f) continue
                            val hx = size.width * i / (nH - 1).toFloat()
                            val hy = trackHeight * (1f - hv.coerceIn(0f, 1f) * 0.94f)
                            drawLine(
                                color = T.accent.copy(alpha = 0.55f),
                                start = androidx.compose.ui.geometry.Offset(hx, hy),
                                end = androidx.compose.ui.geometry.Offset(hx + size.width / nH - 2f, hy),
                                strokeWidth = holdPx
                            )
                        }
                    }
                }
            }
        }

        // ---- Band points at their true log positions ----
        for (i in 0 until bandCount) {
            val level = levels.getOrElse(i) { 0f }
            // visual clamp only — out-of-view values stay intact in the DSP
            val normLevel = ((level + scaleDb) / (scaleDb * 2f)).coerceIn(0f, 1f)
            val x = bandX[i] * size.width
            val y = trackHeight - trackHeight * normLevel
            topsX[i] = x
            topsY[i] = y

            val isSel = i == activeBand || i == selectedBand
            val dimmed = (activeBand >= 0 || selectedBand >= 0) && !isSel
            if (!isSel) {
                drawCircle(
                    color = if (dimmed) T.primary.copy(alpha = 0.25f) else T.primary.copy(alpha = 0.65f),
                    radius = handlePx * 0.85f,
                    center = androidx.compose.ui.geometry.Offset(x, y)
                )
            }
            // visual clipping: value beyond the selected range is pinned to the
            // edge and marked in accent — the DSP value itself is unchanged.
            if (kotlin.math.abs(level) > scaleDb) {
                drawCircle(color = T.accent, radius = handlePx * 0.5f,
                    center = androidx.compose.ui.geometry.Offset(x, if (level > 0) 2f else trackHeight - 2f))
            }
        }

        // ---- Smooth response curve through the points (log-x interpolation;
        // visualization only — the DSP is the engine, never this curve) ----
        if (bandCount > 1 && analyzerMode != 1) {
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

        // ---- Active/selected band emphasis: halo + dot + value bubble ----
        val emphBand = if (activeBand in 0 until bandCount) activeBand else selectedBand
        if (emphBand in 0 until bandCount) {
            val cx = topsX[emphBand]
            val topY = topsY[emphBand]
            val glowR = handlePx * 5f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(T.primary.copy(alpha = 0.35f), T.primary.copy(alpha = 0f)),
                    center = androidx.compose.ui.geometry.Offset(cx, topY),
                    radius = glowR
                ),
                radius = glowR,
                center = androidx.compose.ui.geometry.Offset(cx, topY)
            )
            drawCircle(color = T.primary, radius = handlePx, center = androidx.compose.ui.geometry.Offset(cx, topY))
            drawCircle(color = T.secondary, radius = handlePx * 0.45f, center = androidx.compose.ui.geometry.Offset(cx, topY))

            val lvl = levels.getOrElse(emphBand) { 0f }
            val bf = bands.getOrNull(emphBand)?.freq ?: 1000
            val freqTxt = if (bf >= 1000) "%.2f kHz".format(bf / 1000f) else "$bf Hz"
            val text = "B" + (emphBand + 1) + " · " + freqTxt + " · " + (if (lvl > 0) "+" else "") + "%.1f".format(lvl) + " dB"
            labelPaint.textSize = lvlSizePx
            labelPaint.color = bubbleText
            val textW = labelPaint.measureText(text)
            val bubbleH = lvlSizePx * 1.9f
            val bubbleW = textW + bubbleH
            val bx = (cx - bubbleW / 2f).coerceIn(0f, size.width - bubbleW)
            val by = (topY - bubbleH - handlePx - 6f).coerceAtLeast(0f)
            drawRoundRect(
                color = bubbleBg,
                topLeft = androidx.compose.ui.geometry.Offset(bx, by),
                size = androidx.compose.ui.geometry.Size(bubbleW, bubbleH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bubbleH / 2f, bubbleH / 2f)
            )
            drawRoundRect(
                color = bubbleBorder,
                topLeft = androidx.compose.ui.geometry.Offset(bx, by),
                size = androidx.compose.ui.geometry.Size(bubbleW, bubbleH),
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
        color = if (selected) T.primary else Color.Gray,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) T.primary.copy(alpha = 0.15f) else S.cardDeep)
            .border(
                1.dp,
                if (selected) T.primary.copy(alpha = 0.5f) else T.primary.copy(alpha = 0.12f),
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
                if (selected) T.primary.copy(alpha = 0.15f) else S.cardDeep
            )
            .border(
                1.dp,
                if (selected) T.primary.copy(alpha = 0.6f) else T.primary.copy(alpha = 0.12f),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Mini sparkline preview — 24px tall, draws the EQ curve shape
        // Hoisted out of the draw loop — the old version allocated two Color
        // objects per band per frame while the row scrolled (31 bands x N chips).
        val thumbColor = if (selected) T.primary.copy(alpha = 0.8f) else T.secondary.copy(alpha = 0.5f)
        Canvas(modifier = Modifier.width(60.dp).height(24.dp)) {
            val levels = preset.levels
            // Build #93: built-in presets are 10-slot curves now; user-saved
            // custom presets may still be 31-slot. Draw whatever width the
            // array has so the sparkline always fills the canvas.
            val n = maxOf(levels.size, 1)
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
        Text(preset.name, fontSize = 10.sp, color = if (selected) T.primary else Color.Gray, maxLines = 1)
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
                if (selected) T.secondary.copy(alpha = 0.2f) else S.cardDeep
            )
            .border(
                1.dp,
                if (selected) T.secondary.copy(alpha = 0.6f) else T.secondary.copy(alpha = 0.15f),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 4.dp)
    ) {
        // Mini sparkline preview for custom preset
        Canvas(modifier = Modifier.width(40.dp).height(24.dp)) {
            val levels = preset.levels
            // Build #93: built-in presets are 10-slot curves now; user-saved
            // custom presets may still be 31-slot. Draw whatever width the
            // array has so the sparkline always fills the canvas.
            val n = maxOf(levels.size, 1)
            val slotW = size.width / n
            val midY = size.height / 2f
            val maxLevel = 15f
            for (i in 0 until n) {
                val lvl = levels.getOrElse(i) { 0 }.toFloat()
                val normY = (lvl / maxLevel).coerceIn(-1f, 1f)
                val barH = size.height * 0.45f * kotlin.math.abs(normY)
                val y = if (normY >= 0) midY - barH else midY
                drawRoundRect(
                    color = if (selected) T.secondary.copy(alpha = 0.8f) else T.secondary.copy(alpha = 0.4f),
                    topLeft = Offset(i * slotW, y),
                    size = Size(slotW * 0.7f, barH.coerceAtLeast(1f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f, 1f)
                )
            }
        }
        Text(
            preset.name,
            fontSize = 10.sp,
            color = if (selected) T.secondary else Color.Gray,
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
            Text("×", fontSize = 14.sp, color = T.accent)
        }
    }
}

// Build #94: FxSound-style circular effect dial — the last piece of the
// FxSound visual overhaul (the curved EQ landed in v91; effects were still
// linear sliders). Canvas-drawn 270° arc with a neon gradient sweep, a knob
// dot on the arc, and the value inside the dial. Drag anywhere on the dial
// to spin it; double-tap resets to 0 (same haptic as before).
@Composable
fun CircularDial(
    label: String,
    value: Int,
    range: IntRange,
    valueText: String? = null,
    onValueChange: (Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val fraction = (value - range.first).toFloat() / (range.last - range.first).toFloat()

    // Build #136: hoisted out of the draw pass — was allocating a new
    // shader every frame while the dial animated.
    val dialSweepBrush = remember(T.primary, T.secondary) {
        Brush.sweepGradient(0.375f to T.secondary, 1.0f to T.primary)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .size(84.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val deg = Math.toDegrees(
                                atan2(
                                    (change.position.y - cy).toDouble(),
                                    (change.position.x - cx).toDouble()
                                ).toDouble()
                            ).toFloat()
                            // Dial starts at 135° (lower-left) and sweeps 270°.
                            val a = ((deg - 135f) % 360f + 360f) % 360f
                            val frac = (a / 270f).coerceIn(0f, 1f)
                            val v = range.first + (frac * (range.last - range.first)).roundToInt()
                            onValueChange(v)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                onValueChange(0)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        )
                    }
            ) {
                val strokePx = 8.dp.toPx()
                val r = size.minDimension / 2f - strokePx / 2f
                val topLeft = Offset((size.width / 2f) - r, (size.height / 2f) - r)
                val arcSize = Size(r * 2f, r * 2f)

                // Track — full 270° in muted surface tone
                drawArc(
                    color = Color.Gray.copy(alpha = 0.18f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
                // Progress — neon gradient sweep (cyan → purple), stops
                // shifted so the gradient begins at the dial's 135° start.
                drawArc(
                    brush = dialSweepBrush,
                    startAngle = 135f,
                    sweepAngle = 270f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
                // Knob dot riding the arc at the current value
                val knobAngle = Math.toRadians((135f + 270f * fraction).toDouble())
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = strokePx * 0.75f,
                    center = Offset(
                        (size.width / 2f) + (r * cos(knobAngle)).toFloat(),
                        (size.height / 2f) + (r * sin(knobAngle)).toFloat()
                    )
                )
            }
            Text(
                valueText ?: "$value",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = T.accent
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, color = Color.Gray, letterSpacing = 1.sp)
    }
}

@Composable
fun CrashScreen(trace: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(S.bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("SONICCORE CRASHED", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = T.accent)
        Spacer(Modifier.height(8.dp))
        Text(
            "Screenshot this and send it back — this is the real error, not a guess.",
            fontSize = 12.sp, color = Color.Gray
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap Copy to clipboard if you can't screenshot.",
            fontSize = 11.sp, color = T.secondary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            trace,
            fontSize = 10.sp,
            color = T.primary,
            modifier = Modifier
                .background(S.surface)
                .border(1.dp, T.accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .padding(12.dp)
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDismiss) { Text("Dismiss & Retry") }
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SonicCore crash log", trace))
                Toast.makeText(context, "Crash log copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
        }
    }
}
