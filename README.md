# Zmusic

Offline-first Android music player. Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSessionService, Room.

**Download:** grab the latest `.apk` from the [Releases](../../releases) page (plain APK, not zipped).

> Built by CI and not yet confirmed on every device. If something breaks, open an issue or paste the log.

## Design

Nocturnal liquid glass, typographic and cover-free. A deep obsidian base (`#070709`) sits in front of a black hole whose ring turns while a song plays; a few panes of smoked frosted glass float in front of it. The app is dark-only on purpose.

- Palette: obsidian `#070709`, smoked glass `#14151A`. All type is white: full strength for titles, 76% for secondary lines, 58% for counts and durations. Amber is the one accent (current song, primary action). Indigo only ever appears as light.
- No album covers and no lyrics anywhere. Type is Manrope; timestamps, counts and index numerals are JetBrains Mono.
- Glass is reserved for things that float: the navigation dock, the mini player, the action pills, the "Recently added" tiles and the Now Playing sheet. List rows are bare type.
- The dock, mini player and sheet blur what is behind them live ([Haze](https://github.com/chrisbanes/haze), `RenderEffect`, Android 12L+; older versions get near-solid smoked glass).
- Motion is spring-driven. Tiles tilt in 3D under your finger, the dock lozenge slides between tabs, the sheet rises while un-tilting in perspective.
- Now Playing shows a procedural 3D gyroscope instead of artwork: three gimballed metal hoops and a spectrum halo, built from real vertices with perspective projection, smooth per-vertex lighting and depth sorting on a Compose `Canvas`. It is staged like an anime skill going off: a summoning circle with runes and a hexagram turns on the floor under it, a pillar of light rises through the core, and streams of light wind up round the hoops. A hard kick fires lightning from the core to the hoops, focus lines in from the edge of the frame, and a flare across the core star.
- The backdrop is a scene: a black hole with a tilted accretion disc, four planets orbiting in the disc's plane, stars that bend around the hole, and a shooting star every 11 seconds. The disc spins only while music plays and coasts to a stop on pause; bass brightens it.
- The current song's row lights up with a wash of amber, screen titles carry a bar of light that stretches with the bass, and the dock's selected tab has an amber filament that does the same.
- Honours the system "remove animations" setting (decorative motion stops).

### Audio-reactive visuals

`AudioFrequencyTracker` publishes smoothed `bass`, `mid` and `treble` (`StateFlow<Float>`, 0..1). Each frame it picks the best available source:

1. `android.media.audiofx.Visualizer` on the player's own audio session, if `RECORD_AUDIO` was granted. The app asks once, the first time Now Playing opens. Nothing is recorded.
2. `LevelAudioProcessor`, which measures a 12-band spectrum from ExoPlayer's PCM buffers. No permission needed, so denying the prompt costs nothing but a little sync accuracy (16-bit PCM only; it can lead the sound by the audio buffer).
3. A slow synthetic "breathing" wave while paused, so the UI never looks frozen.

The backdrop is an AGSL `RuntimeShader` on Android 13+ and an equivalent Canvas gradient version below that.

### Bug log

The fourth tab is a console. `BugLog` records crashes (uncaught exceptions, written to disk before the process dies, so they are there on the next launch), playback errors, library scan failures, warnings such as a rejected shader or Visualizer, and a few milestones for context. Lines are timestamped and colour-coded by level, filterable, and a line with a stack trace expands when tapped. The log follows new output like a terminal, can be copied or cleared, keeps the last 400 entries, and never leaves the device. The Bugs tab shows a red dot while there is an error you have not looked at.

## What works

- MediaStore scan into Room, stable IDs, missing files flagged instead of deleted, scan throttle, manual rescan
- Permission flow that explains first
- Single ExoPlayer in `PlaybackService`: background playback, notification and lock-screen controls, audio focus, becoming-noisy handling
- Queue (view, jump, remove), shuffle, repeat all/one/off. Repeat all is the default, so after the last song the queue starts over from the first
- Home (library counters, daily mix, recently added), Library (A-Z with a jump rail), Search (debounced, top-artist links), Bugs (console), mini player, Now Playing
- Large libraries queue a window around the tapped song first (100 before, 300 after) so it starts at once, then the rest of the list follows in batches of 250

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
  debug/       BugLog: the in-app console's store and crash handler
  playback/    PlaybackService, PlayerController, LevelAudioProcessor, AudioLevels
  ui/theme/    seed-based colour schemes, type, shapes
  ui/components/  motion primitives, audio-reactive visuals, scrubber, backdrop, rows, mini player
  ui/          screens and navigation shell
  util/        formatting helpers
```
