# Zmusic

Offline-first Android music player. Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSessionService, Room.

**Download:** grab the latest `.apk` from the [Releases](../../releases) page (plain APK, not zipped).

> Built by CI and not yet confirmed on every device. If something breaks, open an issue or paste the log.

## Design

Nocturnal liquid glass, typographic and cover-free. A deep obsidian base (`#070709`) sits in front of a black hole whose disc turns while a song plays, with an astronaut drifting past; a few panes of smoked frosted glass float in front of it. The app is dark-only on purpose.

- Palette: obsidian `#070709`, smoked glass `#14151A`. All type is white: full strength for titles, 76% for secondary lines, 58% for counts and durations. Amber is the one accent (current song, primary action). Indigo only ever appears as light.
- No album covers and no lyrics anywhere. Type is Manrope; timestamps, counts and index numerals are JetBrains Mono.
- Glass is reserved for things that float: the navigation dock, the mini player, the action pills, the "Recently added" tiles and the Now Playing sheet. List rows are bare type.
- The dock, mini player and sheet blur what is behind them live ([Haze](https://github.com/chrisbanes/haze), `RenderEffect`, Android 12L+; older versions get near-solid smoked glass).
- Motion is spring-driven. Tiles tilt in 3D under your finger, the dock lozenge slides between tabs, the sheet rises while un-tilting in perspective.
- Now Playing shows a procedural 3D gyroscope instead of artwork: three gimballed metal hoops and a spectrum halo, built from real vertices with perspective projection, smooth per-vertex lighting and depth sorting on a Compose `Canvas`. It is staged like an anime skill going off: a summoning circle with runes and a hexagram turns on the floor under it, a pillar of light rises through the core, and streams of light wind up round the hoops. A hard kick fires lightning from the core to the hoops, focus lines in from the edge of the frame, and a flare across the core star.
- The gyroscope is one of four sculptures. A shuffled deck deals a new one every time a song starts (including the same song again on repeat), so each shows once in any four songs and none shows twice running. The deck is kept on disk, so reopening the app carries on the same rotation. The mini player and the queue header show a cut-down copy of the one on stage.
- The other three are emblems built from flat layers in 3D. None moves like the gyroscope: nothing tumbles or tips over like a hoop, and the camera never nods or sways. Each has its own camera, shading, motion, spectrum display, entrance, exit and kick effect:
  - **Eclipse**: a crimson compass star round a dark core, a broken silver ring, a ring of thorns. Faces you flat and is cel-shaded in two flat tones, like an anime gem, under a corona of red light. It moves like heavy clockwork: the star holds, then ratchets a quarter turn on a kick and overshoots, the flames trail it on a looser spring, and the silver ring clicks the other way. Its depth is a punch: on a kick the star leaps toward you while the ring is knocked back, the thorns snap out, and the whole emblem rolls and shakes as two crescent slashes cut across it. A dotted ring swells with the spectrum, and embers spiral into the core. It arrives by blooming out of the core and leaves by falling into it.
  - **Sunwheel**: a gold neon sign, flat-lit. The star spins like a pinwheel, faster as the music gets louder, with afterimages and sparks flying off its tips; the spin throws the spears and leaves outward, the leaves sway, the two rings turn against each other, and the neon buzzes now and then. A level meter between the rings fills from the bottom up both sides, with a peak that hangs. Its depth is a flip: every few seconds a kick turns the whole sign over. A kick also sends rays out of the core. It arrives spinning in a blur and leaves by rolling away.
  - **Astrolabe**: pale engraved plates stacked in depth like a hologram. The viewpoint drifts so the plates slide across one another, and the stack breathes apart with the bass. The disc ticks like a second hand with geared dials, planets run round flat orbits on their own plates, a light reads along the runes, starburst rays flicker with the spectrum, gold dust drifts between the plates, and a comet crosses behind now and then. A kick sends ripples across the disc and joins dust into a constellation. It arrives by drawing itself in as its plates part, and leaves by switching off.
- Every sculpture follows the music in its own way: the gyroscope turns with the mids, the Eclipse steps on the kicks, the Sunwheel spins with the loudness, the Astrolabe ticks with the tempo of the mids.
- The backdrop is a scene: a black hole seen almost edge-on, four planets on their own tilted orbits round it, a shooting star every 11 seconds, and an astronaut drifting in front of it all. The disc spins only while music plays and coasts to a stop on pause; bass brightens it.
  - **The hole** bends light the way a real one does. The far side of the disc arches over the top of the shadow and its underside shows as a second arc beneath it, with a hairline photon ring on the shadow's edge. These come from tracing where each ray crosses the disc after the hole's gravity has bent it (an analytic fit to Schwarzschild orbits, no ray marching). The gas glows as a black body: warm white at the inner edge, then gold, then deep amber at the rim. The side orbiting toward you is brighter and whiter, held to about four to one so neither side burns out. Inner gas laps outer gas, so its streaks shear, and the fine streaks fade wherever the lensing would squeeze them finer than a pixel, so the arcs stay smooth. A little bloom spills round the shadow and along the disc. Stars near the hole are pushed outward and stretched into arcs, and a faint band of distant stars bends round it.
  - **The planets**: an icy moon crossed by rust-coloured cracks, a desert world with dark plains, craters and ice caps, a blue ice giant with soft bands and a dark storm, and a ringed giant with cream and rust belts and a storm of its own. The giant's rings have a dark gap and a thread of an outer ring; they cast a shadow band across the planet and take its shadow in turn. All are lit by the disc, so you see their day side when they are behind the hole and a crescent when they swing in front. Dim blue starlight keeps the night sides from going flat black, and planets with air get a red-edged terminator and a halo that lights up all round when backlit. They grow a little as they swing toward you, overlap in the right order, and are lensed into arcs when they pass behind the hole.
  - **The astronaut** drifts slowly through the lower half of the screen, tumbling over every five minutes, limbs and tether swaying. It is backlit by the disc: mostly in shadow, with a warm rim on every edge that faces the hole. Its gold visor catches the disc, and the light on its chest unit pulses with the bass.
- The current song's row lights up with a wash of amber, screen titles carry a bar of light that stretches with the bass, and the dock's selected tab has an amber filament that does the same.
- Honours the system "remove animations" setting (decorative motion stops).

### Audio-reactive visuals

`AudioFrequencyTracker` publishes smoothed `bass`, `mid` and `treble` (`StateFlow<Float>`, 0..1). Each frame it picks the best available source:

1. `android.media.audiofx.Visualizer` on the player's own audio session, if `RECORD_AUDIO` was granted. The app asks once, the first time Now Playing opens. Nothing is recorded.
2. `LevelAudioProcessor`, which measures a 12-band spectrum from ExoPlayer's PCM buffers. No permission needed, so denying the prompt costs nothing but a little sync accuracy (16-bit PCM only; it can lead the sound by the audio buffer).
3. A slow synthetic "breathing" wave while paused, so the UI never looks frozen.

The backdrop is an AGSL `RuntimeShader` on Android 13+ and a simpler Canvas gradient version below that (same hole, planets and astronaut, no lensing). The astronaut is a second, small shader drawn only in a square round the figure.

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
