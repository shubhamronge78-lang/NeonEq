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

## 📱 Screenshots

_Coming soon_

## 🛠️ Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- Android `AudioEffect` API (`Equalizer`, `BassBoost`, `Virtualizer`, `LoudnessEnhancer`)
- Foreground service with `audioProcessing` type
- Min SDK 21 (Android 5.0+)

## 🚀 Getting Started

### Option 1: Android Studio
1. Clone this repo
2. Open the folder in Android Studio (File → Open)
3. Let Gradle sync (2-5 min)
4. Connect your Android phone (USB debugging enabled)
5. Hit Run ▶️

### Option 2: Command Line
```bash
git clone https://github.com/ShubhamRonge/NeonEQ.git
cd NeonEQ
./gradlew assembleDebug
# Install APK:
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 📂 Project Structure

```
app/src/main/java/com/neon/eq/
├── MainActivity.kt            ← Jetpack Compose neon UI
└── engine/
    ├── EqualizerEngine.kt     ← Core audio FX engine
    ├── Presets.kt              ← 10 EQ presets
    └── EQService.kt            ← Background foreground service
```

## 🔑 Permissions

- `MODIFY_AUDIO_SETTINGS` — apply EQ to audio output
- `FOREGROUND_SERVICE` — keep EQ running in background
- `FOREGROUND_SERVICE_AUDIO_PROCESSING` — audio processing service type

## 📄 License

MIT — free to use, modify, and distribute.

## 👤 Author

**Shubham Ronge**
