# 🎧 Neon EQ — Futuristic Android Equalizer

A system-wide audio equalizer for Android with a neon AMOLED UI. Processes ALL device audio — Spotify, YouTube, games, everything.

## ✨ Features

- 🎚️ **System-wide EQ** — hooks into global audio session (session 0)
- 🎵 **10 presets** — Flat, Bass Boost, Rock, Pop, Jazz, EDM, Classical, Dance, Bass Extreme, Treble Boost
- 📊 **Selectable bands** — 5, 10, 15, or 31-band equalizer
- 🔊 **Bass Boost** (0–1000)
- 🎧 **3D Sound / Virtualizer** (0–1000)
- 📈 **Loudness Enhancer / Volume Booster** (0–4000)
- 🌙 **Neon AMOLED dark theme** — cyan/purple/pink gradients on pure black
- 🔧 **Foreground service** — keeps EQ running in background
- ⚡ **On/off toggle** switch

---

## 📲 Install APK (Version-wise)

> Download the APK directly from GitHub Releases — no PC needed.

### 🔥 Latest Release

**➡️ [Download Latest APK](https://github.com/shubhamronge78-lang/NeonEq/releases/latest)**

### 📋 All Versions

| Version | Status | Download | Changelog |
|---------|--------|----------|-----------|
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
