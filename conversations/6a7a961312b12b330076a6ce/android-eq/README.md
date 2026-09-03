# 🎧 Neon EQ — Futuristic Android Equalizer

A system-wide audio equalizer for Android with a neon AMOLED UI. Processes ALL device audio — Spotify, YouTube, games, everything.

## ✨ Features

- 🎚️ **System-wide EQ** — hooks into global audio session (session 0) with dynamic session polling
- 🎵 **10 built-in presets** — Flat, Bass Boost, Rock, Pop, Jazz, EDM, Classical, Dance, Bass Extreme, Treble Boost
- ✏️ **Custom presets** — create, rename, duplicate, reorder, update, delete, share & import (JSON); auto-apply last preset on startup
- 🎯 **Per-app audio profiles** — auto-switch preset based on which app is playing (see v54 below)
- 📊 **Selectable bands** — 5, 10, 15, or 31-band equalizer
- 🔊 **Bass Boost** (0–300, speaker-safe range with preamp compensation)
- 🎧 **3D Sound / Virtualizer** (0–300)
- 📈 **Loudness Enhancer / Volume Booster** (0–300)
- 📊 **Visualizer** — Bars, Wave & Circle styles, on-canvas rendered
- 📱 **Home screen toggle widget** + **Quick Settings tile** — switch EQ without opening the app
- 💾 **Backup & Restore** — full-state export/import incl. presets and per-app profiles
- 🌙 **Glass-morphism AMOLED theme** — neon cards, gradient headers, cyan/purple/pink on pure black
- 📳 **Haptic fine-tuning** — feel a tick at every dB step while dragging bands
- 🔧 **Foreground service** — keeps EQ running in background
- ⚡ **On/off toggle** switch

## 🆕 What's New in Build #54

### 🎯 Per-App Audio Profiles

Assign a preset to any audio app — the EQ switches automatically when that app plays, and restores your previous preset when it stops.

- **How it works:** the engine's existing session-scan loop detects the playing app via `AudioPlaybackConfiguration` client UID (reflection, `Throwable`-guarded) → `PackageManager` package lookup. No new background service or polling.
- **Assign:** start the app playing audio, open Neon EQ → the **APP PROFILES** card shows the playing app with a row of preset pills. Tap one to assign; tap × on any listed profile to remove.
- **Manual input always wins** — if you tweak bands, effects, or pick a preset yourself while a profiled app is playing, auto-switching stays suppressed for that app until playback changes.
- **Detection details:** package resolution uses public `PackageManager` API; reflection (with fallback) for the hidden client-UID call — consistent with the MI-safe session detection approach.
- **Backup & Restore** includes your per-app profiles.

### 📳 Haptic Fine-Tuning

- Subtle tick at every integer dB step while dragging an EQ band — dial in levels by feel.
- Light tick on preset selection; double-tap band reset keeps its firmer haptic.

*(Build #53: full glass-morphism UI pass — all dialogs, cards and headers unified under the neon design language.)*

---

## 📲 Install APK (Version-wise)

> Download the APK directly from GitHub Releases — no PC needed.

### 🔥 Latest Release

**➡️ [Download Latest APK](https://github.com/shubhamronge78-lang/NeonEq/releases/latest)**

### 📋 All Versions

| Version | Status | Download | Changelog |
|---------|--------|----------|-----------|
| **v2.0.1** | 🔥 **Latest** | [Download APK](https://github.com/shubhamronge78-lang/NeonEq/releases/tag/v2.0.1) | New band canvas (smooth curve, 0 dB line, drag glow + value bubble), zero-allocation rendering across all redraw paths, per-app audio profiles, custom presets, haptics, widget, QS tile, backup & restore |
| v2.0.0 | ✅ Stable | [Download APK](https://github.com/shubhamronge78-lang/NeonEq/releases/tag/v2.0.0) | Per-app audio profiles, custom presets (share/import/reorder), haptic fine-tuning, home widget, QS tile, backup & restore, visualizer styles, speaker-safe effect ranges (0–300) with preamp compensation, glass-morphism UI overhaul |
| v1.0.0 | ✅ Stable | [Download APK](https://github.com/shubhamronge78-lang/NeonEq/releases/tag/v1.0.0) | Initial release — 10 presets, 5/10/15/31 bands, bass boost, virtualizer, loudness enhancer |
| v0.9.0 | 🧪 Beta | [Download APK](https://github.com/shubhamronge78-lang/NeonEq/releases/tag/v0.9.0) | Pre-release build with core EQ functionality |

> **New versions auto-appear here** whenever a release tag is pushed.

### 📱 Installation Steps

1. **Download** the APK file from the links above
2. **Enable unknown sources** — Go to `Settings → Security → Install unknown apps` (or `Settings → Apps → Special access → Install unknown apps`) and allow your browser
3. **Install** — Tap the downloaded APK file and tap "Install"
4. **Grant permissions** — Allow audio modification when prompted
5. **Enable EQ** — Open Neon EQ, toggle the switch ON, pick a preset or adjust bands manually
6. **Enjoy!** — The EQ processes all audio system-wide, even in the background

> 💡 **Tip:** Keep the foreground service running for persistent EQ across all apps. If you close the app, the EQ turns off.

### 🔄 Updating to a New Version

1. Download the new APK from the table above
2. Install it over the existing version (no need to uninstall)
3. Your settings will reset to defaults

### ❓ Troubleshooting

| Problem | Solution |
|---------|----------|
| "App not installed" | Ensure you have enough space (~10 MB) and unknown sources is enabled |
| EQ not working | Toggle off/on, or restart the app. Some devices need `RECORD_AUDIO` permission |
| No sound change | The EQ works on session 0 (global). Some OEMs block this — try with headphones |
| App crashes | Check Android version is 5.0+ (API 21+). Report the issue on GitHub |
| Service stops | Battery optimization may kill it. Disable battery optimization for Neon EQ |

---

## 🛠️ Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- Android `AudioEffect` API (`Equalizer`, `BassBoost`, `Virtualizer`, `LoudnessEnhancer`)
- Foreground service with `mediaPlayback` type
- Min SDK 21 (Android 5.0+)

## 🚀 Build from Source

### Option 1: Android Studio
1. Clone this repo
2. Open the folder in Android Studio (File → Open)
3. Let Gradle sync (2-5 min)
4. Connect your Android phone (USB debugging enabled)
5. Hit Run ▶️

### Option 2: Command Line / Termux
```bash
git clone https://github.com/shubhamronge78-lang/NeonEq.git
cd NeonEq
./gradlew assembleDebug
# Install via ADB:
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Option 3: GitHub Actions (No PC needed)
1. Push code to the `main` branch
2. GitHub Actions auto-builds the APK
3. Download from the **Actions** tab → Artifacts

## 📂 Project Structure

```
app/src/main/java/com/neon/eq/
├── MainActivity.kt            ← Jetpack Compose neon UI
└── engine/
    ├── EqualizerEngine.kt     ← Core audio FX engine
    ├── Presets.kt              ← 10 EQ presets
    └── EQService.kt            ← Background foreground service

.github/workflows/
├── build.yml                   ← Auto-build on push to main
└── release.yml                 ← Versioned release on tag push (v*)
```

## 🔑 Permissions

- `MODIFY_AUDIO_SETTINGS` — apply EQ to audio output
- `RECORD_AUDIO` — may be needed on some devices for audio processing
- `FOREGROUND_SERVICE` — keep EQ running in background

## ⚡ Performance (Build #51 + #56/#57 optimizations)

The visualizer and EQ canvas are tuned for low-end hardware (tested on a Redmi 10C).

- **Zero steady-state allocations** (Builds #56–57) — every `Path`, `Paint`, `Brush` and buffer in the redraw paths is hoisted with `remember` and reused. The band canvas previously created a `Paint` per band per frame (up to 31/frame), the visualizer 32–41 gradient brushes per frame; both now allocate nothing while drawing. Unpositioned brushes size themselves to the drawn geometry, so one remembered brush serves every bar/spoke with identical visuals.
- **Smooth band curve** (Build #56) — quadratic bezier traced through band tops with a glow underlay, dashed 0 dB reference line, and an active-band glow + floating value bubble while dragging.

- **Custom Canvas rendering** — visualizer (Bars / Wave / Circle styles) and EQ sliders are drawn with Compose Canvas directly. No AndroidView, no XML, no per-frame view invalidation.
- **Zero per-frame allocation in hot loops** — `Path`, `Brush`, and color lists are hoisted out of render loops: one allocation per frame instead of one per bar/spoke (32+ objects per frame eliminated in Bars mode, 40 in Circle mode). Peak-marker arrays are reused across frames.
- **Shared track geometry** — CanvasEQ touch mapping and drawing use the exact same computed track rectangle, so drag latency doesn't fight the render pass. Band drags stay smooth while the visualizer runs.
- **Frame throttling** — capture/render cadence is capped so a low-end CPU budget isn't saturated; the UI thread never contends with the audio thread (`audioExecutor` runs all native `AudioEffect` calls off-thread).
- **GC pressure kept flat** — waveform bytes and draw objects are reused rather than reallocated, avoiding GC pauses during playback.

---

## 🏷️ Creating a New Version Release

```bash
# Tag a new version
git tag v1.1.0
git push origin v1.1.0

# GitHub Actions auto-builds the APK and creates a release
# Download link: https://github.com/shubhamronge78-lang/NeonEq/releases/tag/v1.1.0
```

## 📄 License

MIT — free to use, modify, and distribute.

## 👤 Author

**Shubham Ronge**
