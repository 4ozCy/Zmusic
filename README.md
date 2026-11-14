# Zmusic

Offline-first Android music player. Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSessionService, Room.

**Download:** grab the latest `.apk` from the [Releases](../../releases) page (plain APK, not zipped).

> Built by CI and not yet confirmed on every device. If something breaks, open an issue or paste the log.

## Design

Nocturnal liquid glass, typographic and cover-free. A deep obsidian base (`#070709`) is lit from behind by slow aurora light that follows the music; a few panes of smoked frosted glass float in front of it. The app is dark-only on purpose.

- Palette: obsidian `#070709`, smoked glass `#14151A`, alabaster type `#F5F5F7`, silver secondary `#8E8E93`. Amber is the one accent (current song, primary action). Indigo only ever appears as light.
- No album covers and no lyrics anywhere. Type is Manrope; timestamps, counts and index numerals are JetBrains Mono.
- Glass is reserved for things that float: the navigation dock, the mini player, the action pills, the "Recently added" tiles and the Now Playing sheet. List rows are bare type.
- The dock, mini player and sheet blur what is behind them live ([Haze](https://github.com/chrisbanes/haze), `RenderEffect`, Android 12L+; older versions get near-solid smoked glass).
- Motion is spring-driven. Tiles tilt in 3D under your finger, the dock lozenge slides between tabs, the sheet rises while un-tilting in perspective.
- Now Playing shows a procedural 3D gyroscope instead of artwork: three gimballed metal hoops and a spectrum halo, built from real vertices with perspective projection, smooth per-vertex lighting and depth sorting on a Compose `Canvas`.
- Honours the system "remove animations" setting (decorative motion stops).

### Audio-reactive visuals

`AudioFrequencyTracker` publishes smoothed `bass`, `mid` and `treble` (`StateFlow<Float>`, 0..1). Each frame it picks the best available source:

1. `android.media.audiofx.Visualizer` on the player's own audio session, if `RECORD_AUDIO` was granted. The app asks once, the first time Now Playing opens. Nothing is recorded.
2. `LevelAudioProcessor`, which measures a 12-band spectrum from ExoPlayer's PCM buffers. No permission needed, so denying the prompt costs nothing but a little sync accuracy (16-bit PCM only; it can lead the sound by the audio buffer).
3. A slow synthetic "breathing" wave while paused, so the UI never looks frozen.

The backdrop is an AGSL `RuntimeShader` on Android 13+ and an equivalent Canvas gradient version below that.

## What works

- MediaStore scan into Room, stable IDs, missing files flagged instead of deleted, scan throttle, manual rescan
- Permission flow that explains first
- Single ExoPlayer in `PlaybackService`: background playback, notification and lock-screen controls, audio focus, becoming-noisy handling
- Queue (view, jump, remove), shuffle, repeat off/all/one
- Home (daily mix, recently added), Library (A-Z with a jump rail), Search (debounced, top-artist links), mini player, Now Playing
- Large libraries queue a window around the tapped song (100 before, 300 after)

## Not built yet

- Lyrics and album art are intentionally not part of the app.
- Playlists, favorites, history, sort/filter, drag-reorder queue, queue restore after process death, equalizer/effects, sleep timer, playback speed, backup/restore, settings, light theme.

## Releases and signing

Every push to `main` builds a signed release APK and publishes it as a GitHub Release. The signing key is committed (`ci/`) so each build has the same signature and installs over the previous one. That key is public, which is fine for a personal sideloaded app but must not be used for Play Store publishing.

## Build locally

Gradle 8.9, JDK 17: `gradle assembleDebug`. Compose previews (light and dark) are in `NowPlayingScreen.kt`.

## Layout

```
app/src/main/java/com/zmusic/app/
  data/        Room entity, DAO, database, MediaStore scanner, repository
  playback/    PlaybackService, PlayerController, LevelAudioProcessor, AudioLevels
  ui/theme/    seed-based colour schemes, type, shapes
  ui/components/  motion primitives, audio-reactive visuals, scrubber, backdrop, rows, mini player
  ui/          screens and navigation shell
  util/        formatting helpers
```
