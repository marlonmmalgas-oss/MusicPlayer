# Xsky Music Player

Native Android offline music player built with Kotlin, Jetpack Compose and AndroidX Media3.

## Included
- On-device music library scan and search
- Songs, albums, artists, favorites and recently played views
- Media3 background playback service and media-session notification controls
- Playback queue foundation, previous/next, seek, play/pause
- Equalizer with device-supported bands, bass boost, virtualizer and presets
- Sleep timer
- Dark graphite / champagne-gold interface
- GitHub Actions APK build

## Build
GitHub Actions builds on pushes to `main` or `master`, and can also be started manually from the Actions tab.

The workflow uses JDK 17, Android SDK 35 and Gradle 8.9. Download the APK from the workflow run's Artifacts section.

## Apply to an existing repository
This ZIP is a complete project source layout. Back up your existing repository first. Extract into the repository root and allow replacement of the same paths. Review any project-specific signing or package configuration before building.

## Important implementation notes
- The app plays local audio files available through Android MediaStore. It does not stream copyrighted music from online services.
- Lyrics synchronization, online metadata providers, Android Auto browse customisation, playlist editing/export, crossfade, gapless transition tuning, audio replay gain and home-screen widgets are not implemented in this baseline.
- Android audio effects depend on device and OEM support. Some devices expose fewer EQ bands or disable effects.
- This source package has not been compiled on the user's device or repository. Run the included GitHub Actions workflow and address any build log errors before installing.


## UI and library update
- Silver/black visual system with translucent glass-style panels and borders.
- Tapping a track starts playback and opens the full-screen Now Playing view.
- Tapping the mini-player opens Now Playing.
- Local album art is loaded from Android MediaStore album-art URIs.
- Albums are grouped by album name and shown as artwork cards; selecting one opens its track list.
- Artists and folders have separate browsing views.
- Added a custom silver X launcher icon.
- Added Coil Compose for artwork loading.

Note: Android only provides album artwork when it is embedded in the audio file or indexed by the device media provider. Tracks without embedded/indexed artwork will show an empty dark artwork tile. Online artwork lookup, synced lyrics, playlist editor, and a fully connected system equalizer are not included in this update.
