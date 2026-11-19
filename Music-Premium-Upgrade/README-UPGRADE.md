# Music app source upgrade

Replace these three files in your repository:
1. `app/src/main/java/com/xsky/musicplayer/MainActivity.kt`
2. `app/src/main/java/com/xsky/musicplayer/Song.kt`
3. `app/src/main/AndroidManifest.xml`

No new Gradle dependencies are required. Keep your existing `PlaybackService.kt` and Gradle files.

Implemented in this source update:
- Premium graphite/champagne styling and app label **Music**
- MediaStore album artwork
- Functional Songs, Albums, Artists, Folders, Favorites, Playlists, Recent and Most played sections
- Persistent favorites, playlists, play counts and recent history
- Full-screen player, seeking, shuffle and repeat
- Queue display with reorder and remove
- Equalizer presets, bass boost and virtualizer
- Sleep timer
- Existing Media3 MediaSession continues to provide Android media notification/lock-screen controls

Limitations:
- Gapless behavior depends on the device/codec and Media3 playback pipeline.
- Lyrics are not fetched from an external lyrics service.
- This is source code, not a verified APK. Run the existing GitHub Actions workflow and inspect its build result before installing.
