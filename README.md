# Zmusic

Offline-first Android music player. Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSessionService, Room.

> **Status: Phases 1-3 scaffold. Written without access to an Android SDK, so it has NOT been compiled or run yet.**
> The first CI run is the first real compile. If it fails, paste the log and it gets fixed.

## What works (in code, untested on device)

- Gradle KTS project, version catalog, adaptive vector icon, splash screen, dark theme (light scheme defined, UI switch comes later)
- MediaStore scan into Room, stable IDs (MediaStore id, not file path), missing files flagged instead of deleted, 30s scan throttle, manual refresh
- Permission flow with explanation first (READ_MEDIA_AUDIO on 13+, READ_EXTERNAL_STORAGE below, POST_NOTIFICATIONS on 13+)
- Single ExoPlayer inside `PlaybackService` (MediaSessionService): background playback, notification + lock-screen controls, audio focus, becoming-noisy handling
- Queue (view, jump, remove), shuffle, repeat off/all/one
- Home, Library, Search (debounced), mini player, Now Playing (swipe to change track, swipe down to close, seek bar, crossfading artwork)

## Not built yet

Playlists, favorites, history, sort/filter, drag-reorder queue, queue restore after process death, equalizer/effects, sleep timer, playback speed, lyrics, visualizer, smart playlists, backup/restore, settings screen, theme switching. Playlists tab is a labelled placeholder.

## Build

**From your phone (no PC):** push to GitHub, open the Actions tab, run "Build debug APK", download the `zmusic-debug-apk` artifact, install it.

**Android Studio:** open the folder, let it sync, run `app`. (Gradle wrapper files are not committed yet; Studio can generate them, or run `gradle wrapper --gradle-version 8.9` once.)

**CLI:** `gradle assembleDebug` (Gradle 8.9, JDK 17).

## Known risks to check on a device

- Notification artwork uses the legacy `content://media/external/audio/albumart` URI, which some Android 10+ devices do not resolve. Fix is to pass artwork bytes from the service.
- Release build is signed with the debug key for now. Replace before publishing.
- Passing a very large library as one queue is simple but not optimal.

## Layout

```
app/src/main/java/com/zmusic/app/
  data/        Room entity, DAO, database, MediaStore scanner, repository
  playback/    PlaybackService, PlayerController (MediaController wrapper), UI state
  ui/          theme, components, screens (home, library, search, nowplaying)
  util/        formatting helpers
```
