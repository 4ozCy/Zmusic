# Zmusic

Offline-first Android music player. Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSessionService, Room.

**Download:** grab the latest `.apk` from the [Releases](../../releases) page (plain APK, not zipped).

> Built by CI and not yet confirmed on every device. If something breaks, open an issue or paste the log.

## Design

Calm, typographic and cover-free. Neutrals are `#0B0B0D` (dark) and `#F6F6F4` (light), nudged by one muted seed colour derived from the current song's name (chroma capped so contrast always holds). It follows the system light/dark setting. The identity of the app is the live audio visual and the type, not artwork.

- No album covers, no cards, borders or pill backgrounds around controls. Structure comes from spacing and 0.5dp hairlines at 10% alpha.
- Type is Manrope; timestamps, counts and row numbers are JetBrains Mono.
- Motion is critically damped (no bounce). Press detents are 0.94 scale with haptics on transport controls.
- One signature visual, the radial spectrum ring: hero on Now Playing, 44dp in the mini player, idle brand mark on the permission screen. One shared frame loop feeds every live visual.
- Now Playing: ring, title block directly above the precision scrubber (2dp paused, 4dp playing, thumb + time pill + haptic ticks while dragging), play button that morphs circle to 16dp squircle. Skipping slides the title in the direction of travel. The sheet follows your finger when you swipe it down.
- The queue opens in place of the ring under a compact header.
- Honours the system "remove animations" setting (decorative motion stops).

### Audio visuals are real

`LevelAudioProcessor` sits in ExoPlayer's audio pipeline and measures a 12-band spectrum from the actual PCM samples (no microphone permission). Limits: 16-bit PCM only, and the visuals can lead the sound by the audio buffer (tens of ms, up to a couple of hundred depending on the device).

## What works

- MediaStore scan into Room, stable IDs, missing files flagged instead of deleted, scan throttle, manual rescan
- Permission flow that explains first
- Single ExoPlayer in `PlaybackService`: background playback, notification and lock-screen controls, audio focus, becoming-noisy handling
- Queue (view, jump, remove), shuffle, repeat off/all/one
- Home (daily mix, recently added), Library (A-Z), Search (debounced, top-artist links), mini player, Now Playing
- Large libraries queue a window around the tapped song (100 before, 300 after)

## Not built yet

- Lyrics and album art are intentionally not part of the app.
- Playlists, favorites, history, sort/filter, drag-reorder queue, queue restore after process death, equalizer/effects, sleep timer, playback speed, backup/restore, settings, manual theme switch.

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
