# Local Music — Android music player

Native Android music player built with Kotlin, Jetpack Compose and AndroidX Media3. It scans local audio with MediaStore, plays tracks through a background media session, and exposes device-supported equalizer, bass boost and virtualizer effects.

## Build an APK using GitHub (no Android Studio)

1. Create a **new GitHub repository** (for example, `local-music-player`).
2. Upload the **contents of this project folder** to the repository root. The `.github/workflows/android-apk.yml` file must be included.
3. Open the repository's **Actions** tab and select **Build Android APK**.
4. Tap **Run workflow** (or push a commit to `main`/`master` to trigger a build automatically).
5. When the run finishes successfully, open the run and download the `local-music-debug-apk` artifact.
6. Extract the downloaded artifact ZIP and install `app-debug.apk` on your Android phone. Allow installation from the browser/files app if Android asks.

GitHub Actions installs Java, Android SDK and Gradle in the cloud and builds `app-debug.apk`. No Android Studio or local computer build setup is required. The APK artifact is retained for 14 days per build.

## Included foundation

- Local audio library scan using MediaStore
- Search by title, artist and album
- Playback controls and queue
- Background playback using Media3 MediaSessionService
- Android notification and lock-screen media controls
- Device-provided Equalizer, BassBoost and Virtualizer effects where supported
- Jetpack Compose interface

## Compatibility notes

- Supported formats depend on Android's codecs and the device. MP3, AAC/M4A, FLAC, WAV, Ogg/Opus support can vary by device and Android version.
- Equalizer effects and available bands are OEM/device dependent. This app controls playback effects for its own audio, not other apps.
- This is a debug/test APK pipeline, not a signed Play Store release pipeline.
- Features still requiring implementation include persistent EQ presets, crossfade, sleep timer, synchronized lyrics, tag editing, folder browsing, smart playlists and Android Auto.
