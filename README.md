# Zmusic

Offline-first Android music player. Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSessionService, Room.

**Download:** grab the latest `.apk` from the [Releases](../../releases) page (plain APK, not zipped).

> Built by CI and not yet confirmed on every device. If something breaks, open an issue or paste the log.

## Design

Direction: **warm-ink editorial**. Cover-free on purpose (no album art anywhere).

- Warm near-black with a brown undertone and cream text, plus a paper-toned light theme. Follows the system light/dark setting.
- One scarce ember accent. Each song also gets its own colour (derived from its title and artist) that tints the Now Playing glow, the mini player and the Home tiles.
- Serif display type, condensed caps labels, tabular numerals for counts and times.
- Cut-corner signature shape on the hero card, tiles and buttons.
- Typographic tiles (giant italic initial) instead of cover images.
- Custom bottom bar with a spring-sliding indicator, A-Z sticky headers in the library, animated counters, staggered entrances, springy press feedback.
- Now Playing: a mirrored spectrum plus a waveform scrubber. **Both are decorative.** The spectrum follows play/pause and the waveform is generated from the song's name. Neither is real audio analysis.

## What works

- MediaStore scan into Room, stable IDs, missing files flagged instead of deleted, scan throttle, manual rescan
- Permission flow that explains first (READ_MEDIA_AUDIO on 13+, READ_EXTERNAL_STORAGE below, POST_NOTIFICATIONS on 13+)
- Single ExoPlayer in `PlaybackService`: background playback, notification and lock-screen controls, audio focus, becoming-noisy handling
- Queue (view, jump, remove), shuffle, repeat off/all/one
- Home (daily mix, recently added), Library (A-Z), Search (debounced, top-artist chips), mini player, Now Playing
- Large libraries queue a window around the tapped song (100 before, 300 after) so tapping is instant

## Not built yet

Playlists, favorites, history, sort/filter, drag-reorder queue, queue restore after process death, equalizer/effects, sleep timer, playback speed, lyrics, backup/restore, settings, manual theme switch. The Playlists tab is a labelled placeholder.

## Releases and signing

Every push to `main` builds a signed release APK and publishes it as a GitHub Release. The signing key is committed (`ci/`) so each build has the same signature and installs over the previous one. That key is public, which is fine for a personal sideloaded app but must not be used for Play Store publishing.

## Build locally

Gradle 8.9, JDK 17: `gradle assembleDebug`.

## Layout

```
app/src/main/java/com/zmusic/app/
  data/        Room entity, DAO, database, MediaStore scanner, repository
  playback/    PlaybackService, PlayerController (MediaController wrapper), UI state
  ui/theme/    colour schemes, extended tokens, type, shapes, spacing
  ui/          components (Fx.kt), screens, navigation shell
  util/        formatting helpers
```
