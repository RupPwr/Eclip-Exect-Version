# Eclip

**Eclip** is a modern, high-performance video editor for Android designed for speed, precision, and privacy. Built for local, on-device processing, it empowers users to trim, edit, enhance, and export watermark-free videos seamlessly.

---

## Key Features

- **Timeline & Multi-Track Editing:**
  - Precision video trimming, splitting, and segment reordering.
  - Multi-layer draggable text, sticker, image, and video overlays.
  - Interactive transition effects with real-time preview animations.
- **Audio Studio:**
  - Multi-track background music, sound effects, and voice-over recording.
  - Beat detection, audio waveform visualization, audio ducking, and volume boosting up to 200%.
  - Audio extraction to dedicated tracks and MP3 audio-only export mode.
- **Subtitles & Overlays:**
  - SRT subtitle importing with interactive on-screen positioning and styling.
  - Chroma key (green screen) color picking and removal.
  - Keyframe animations for opacity and layer positioning.
- **Visual Enhancement & Filters:**
  - Speed adjustments (slow motion and fast motion) with high-speed proxy generation.
  - Video freeze-frames and reverse video playback.
  - Color adjustment curves (brightness, contrast, saturation, filters) and freehand canvas drawing.
- **High-Performance Export Engine:**
  - Hardware-accelerated GPU encoding (MediaCodec) alongside CPU fallback (x264).
  - Customizable export resolutions (1080p, 720p, 480p) and custom export directories.

---

## Technical Architecture

- **Architecture:** MVVM (Model-View-ViewModel) with Android Jetpack.
- **Rendering Engine:** FFmpegKit (Full GPL build) with hardware encoder acceleration.
- **Playback Engine:** AndroidX Media3 / ExoPlayer 2.19.
- **UI & Controls:** Android ViewBinding, Material Components 3, custom multi-touch gesture handlers.
- **Language & Runtime:** Kotlin, targeting Android SDK 36 (Android 14+ compatible) with minSdk 26 (Android 8.0 Oreo).

---

## Build & Development

### Prerequisites
- JDK 17 (Java Development Kit)
- Android SDK (API 34 / 36) with Android Build Tools
- Gradle 8.9+

### Build Commands

```powershell
# Clean and compile debug sources
./gradlew.bat clean compileDebugSources

# Run unit tests
./gradlew.bat testDebugUnitTest

# Assemble debug APKs
./gradlew.bat assembleDebug

# Assemble release APKs
./gradlew.bat assembleRelease
```

---

## License & Proprietary Notice

Copyright &copy; 2026 Eclip. All rights reserved.  
Confidential and Proprietary.
