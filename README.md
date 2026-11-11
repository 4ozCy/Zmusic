# Zmusic

Offline-first Android music player. Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSessionService, Room.

**Download:** grab the latest `.apk` from the [Releases](../../releases) page (plain APK, not zipped).

> Built by CI and not yet confirmed on every device. If something breaks, open an issue or paste the log.

## Design

Cover-free on purpose. No album art anywhere. Instead: gradient theme, glass cards, a floating nav bar, springy press feedback, and an animated orb/equalizer on Now Playing. The equalizer motion is decorative (it follows play/pause), it is not real audio analysis.

## What works

- MediaStore scan into Room, stable IDs, missing files flagged instead of deleted, scan throttle, manual rescan
- Permission flow that explains first (READ_MEDIA_AUDIO on 13+, READ_EXTERNAL_STORAGE below, POST_NOTIFICATIONS on 13+)
- Single ExoPlayer in `PlaybackService`: background playback, notification and lock-screen controls, audio focus, becoming-noisy handling
- Queue (view, jump, remove), shuffle, repeat off/all/one
- Home, Library, Search (debounced), mini player, Now Playing (swipe orb to change track, swipe down to close)
- Large libraries queue a window around the tapped song (100 before, 300 after) so tapping is instant

## Not built yet

Playlists, favorites, history, sort/filter, drag-reorder queue, queue restore after process death, equalizer/effects, sleep timer, playback speed, lyrics, backup/restore, settings, theme switching. The Playlists tab is a labelled placeholder.

## Releases and signing

Every push to `main` builds a signed release APK and publishes it as a GitHub Release. The signing key is committed (`ci/`) so each build has the same signature and installs over the previous one. That key is public, which is fine for a personal sideloaded app but must not be used for Play Store publishing.

## Build locally

Gradle 8.9, JDK 17: `gradle assembleDebug`.

## Layout

```
app/src/main/java/com/zmusic/app/
  data/        Room entity, DAO, database, MediaStore scanner, repository
  playback/    PlaybackService, PlayerController (MediaController wrapper), UI state
  ui/          theme, effects (Fx.kt), components, screens
  util/        formatting helpers
```
