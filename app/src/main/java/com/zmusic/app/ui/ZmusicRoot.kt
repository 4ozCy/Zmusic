package com.zmusic.app.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zmusic.app.AppContainer
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.debug.BugLog
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.bugs.BugLogScreen
import com.zmusic.app.ui.components.AmbientAudioShaderBackground
import com.zmusic.app.ui.components.DockDestination
import com.zmusic.app.ui.components.FloatingChrome
import com.zmusic.app.ui.components.FloatingGlassNavAndMiniPlayer
import com.zmusic.app.ui.components.GyroDeck
import com.zmusic.app.ui.components.LocalAudioTracker
import com.zmusic.app.ui.components.LocalGyroVariant
import com.zmusic.app.ui.components.NoticeHost
import com.zmusic.app.ui.components.rememberNoticeState
import com.zmusic.app.ui.components.rememberAudioFrequencyTracker
import com.zmusic.app.ui.library.HomeScreen
import com.zmusic.app.ui.library.LibraryScreen
import com.zmusic.app.ui.library.LibraryViewModel
import com.zmusic.app.ui.library.SearchScreen
import com.zmusic.app.ui.nowplaying.Expanded3DNowPlayingSheet
import com.zmusic.app.ui.nowplaying.NowPlayingActions
import com.zmusic.app.ui.nowplaying.SheetSpring
import com.zmusic.app.ui.nowplaying.SleepTimerSheet
import com.zmusic.app.ui.nowplaying.minutesLabel
import com.zmusic.app.ui.nowplaying.sleepTimerLabel
import com.zmusic.app.ui.songs.DeleteDialog
import com.zmusic.app.ui.songs.RenameDialog
import com.zmusic.app.ui.songs.SongActionsViewModel
import com.zmusic.app.ui.songs.SongOptionActions
import com.zmusic.app.ui.songs.SongOptionsSheet
import com.zmusic.app.ui.songs.VOLUME_SHORTCUTS_ON
import com.zmusic.app.ui.sound.SoundViewModel
import com.zmusic.app.ui.theme.ZmusicTheme
import com.zmusic.app.ui.welcome.WelcomeScreen
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.delay

private val AUDIO_PERMISSION = if (Build.VERSION.SDK_INT >= 33) {
    Manifest.permission.READ_MEDIA_AUDIO
} else {
    Manifest.permission.READ_EXTERNAL_STORAGE
}

private val DESTINATIONS = listOf(
    DockDestination("home", "Home", Icons.Rounded.Home),
    DockDestination("library", "Library", Icons.Rounded.LibraryMusic),
    DockDestination("search", "Search", Icons.Rounded.Search),
    DockDestination("bugs", "Bugs", Icons.Rounded.BugReport),
)

private const val BUGS_ROUTE = "bugs"

private const val UI_PREFS = "zmusic_ui"
private const val KEY_CAPTURE_ASKED = "capture_permission_asked"
private const val KEY_GYRO_DECK = "gyro_deck"
private const val KEY_GYRO_SONG = "gyro_deck_song"

/** How long a song change settles before it deals a new sculpture. Too short to see. */
private const val DEAL_SETTLE_MS = 150L

/**
 * Whether Haze gives the Now Playing sheet a live blur here (it does by default from Android 12L).
 * Below that the sheet is a nearly opaque tint instead, and what is under it stays drawn.
 */
private val SHEET_BLURS = Build.VERSION.SDK_INT >= 32

private fun Context.hasPermission(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun ZmusicRoot(container: AppContainer) {
    val context = LocalContext.current
    val vm: LibraryViewModel = viewModel(
        factory = viewModelFactory { initializer { LibraryViewModel(container.repository) } },
    )

    var granted by remember { mutableStateOf(context.hasPermission(AUDIO_PERMISSION)) }
    var denied by remember { mutableStateOf(false) }
    // RECORD_AUDIO, used only for the output Visualizer. Optional: see AudioFrequencyTracker.
    var captureAllowed by remember { mutableStateOf(context.hasPermission(Manifest.permission.RECORD_AUDIO)) }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
        if (ok) vm.refresh(force = true)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        // Granted: the tracker re-attaches the Visualizer. Denied: it simply stays on its fallbacks.
        captureAllowed = ok
    }

    // Covers coming back from system settings and picking up newly added music.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = context.hasPermission(AUDIO_PERMISSION)
        captureAllowed = context.hasPermission(Manifest.permission.RECORD_AUDIO)
        if (granted) vm.refresh()
    }

    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= 33 && !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val playerState by container.player.state.collectAsStateWithLifecycle()
    // Shown once per launch. Saveable, so turning the phone or coming back to a restored app
    // does not play it again.
    var welcome by rememberSaveable { mutableStateOf(true) }

    ZmusicTheme {
        // The single tracker every live visual draws from.
        val tracker = rememberAudioFrequencyTracker(playing = playerState.isPlaying, captureAllowed = captureAllowed)
        // Which sculpture Now Playing shows: the top card of a shuffled deck of all four. Each song
        // that starts deals the next card, so every sculpture shows once in any four songs and never
        // twice running. The deck and the song that dealt its card are kept on disk, so reopening the
        // app carries on where it left off instead of starting a new, unfair shuffle.
        val prefs = remember(context) { context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }
        var deck by remember { mutableStateOf(GyroDeck.restore(prefs.getString(KEY_GYRO_DECK, null))) }
        var dealtFor by remember { mutableStateOf<String?>(prefs.getString(KEY_GYRO_SONG, null)) }
        // The player's song-start count when the card was dealt. -1: not seen since the app opened.
        var dealtAt by rememberSaveable { mutableIntStateOf(-1) }
        val song = playerState.mediaId
        val starts = playerState.songStarts
        LaunchedEffect(song, starts) {
            if (song == null) return@LaunchedEffect
            // A song change can arrive as two quick updates (new queue, then the song itself).
            // Waiting a moment lets the second cancel the first, so one change deals one card.
            delay(DEAL_SETTLE_MS)
            // The same song carrying on (the one that dealt the card, with no new start since) keeps
            // its card. The very first song ever keeps the deck's opening card.
            val carryingOn = song == dealtFor && (dealtAt < 0 || starts == dealtAt)
            if (!carryingOn && dealtFor != null) deck = deck.next()
            dealtFor = song
            dealtAt = starts
            prefs.edit().putString(KEY_GYRO_DECK, deck.save()).putString(KEY_GYRO_SONG, song).apply()
        }
        CompositionLocalProvider(LocalAudioTracker provides tracker, LocalGyroVariant provides deck.current) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                if (!granted) {
                    PermissionScreen(
                        denied = denied,
                        onAllow = { audioLauncher.launch(AUDIO_PERMISSION) },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        },
                    )
                } else {
                    MainShell(
                        vm = vm,
                        container = container,
                        playerState = playerState,
                        onFirstPlayerOpen = {
                            // Asked once, in context, the first time Now Playing is opened. Never nagged
                            // again: after that it is whatever the user chose (changeable in system settings).
                            val prefs = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                            if (!captureAllowed && !prefs.getBoolean(KEY_CAPTURE_ASKED, false)) {
                                prefs.edit().putBoolean(KEY_CAPTURE_ASKED, true).apply()
                                captureLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    )
                }
                // On top of everything, so the app is already laid out underneath and shows
                // through as the welcome fades.
                if (welcome) WelcomeScreen(onFinished = { welcome = false })
            }
        }
    }
}

/**
 * Layering, back to front:
 *
 * 1. Haze source: the backdrop shader plus the current screen. Everything in here can be blurred.
 * 2. Floating glass dock and mini player, blurring layer 1.
 * 3. The Now Playing sheet, also blurring layer 1.
 * 4. The notice pill under the status bar.
 *
 * Layers 2 and 3 are siblings of the source, never children of it, which is what Haze requires.
 * One [Animatable] drives the whole expand transition: the sheet rises and un-tilts, the screen
 * behind it sinks back in depth, and the dock drops away.
 *
 * Song options, the rename and delete dialogs and the sleep timer picker open from here, so a
 * long press on any screen and the options button in Now Playing share them.
 */
@Composable
private fun MainShell(
    vm: LibraryViewModel,
    container: AppContainer,
    playerState: PlayerUiState,
    onFirstPlayerOpen: () -> Unit,
) {
    val context = LocalContext.current
    val player = container.player
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val recent by vm.recentlyAdded.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanError by vm.scanError.collectAsStateWithLifecycle()
    // One position ticker shared by the mini player and the sheet. Read only in draw lambdas.
    val position = player.position.collectAsStateWithLifecycle(initialValue = 0L)
    val sleepTimer by player.sleepTimer.collectAsStateWithLifecycle()
    val volumeShortcuts by container.volumeShortcuts.enabled.collectAsStateWithLifecycle()

    val soundVm: SoundViewModel = viewModel(factory = viewModelFactory { initializer { SoundViewModel(container.equalizer) } })
    val sound by soundVm.settings.collectAsStateWithLifecycle()
    val songVm: SongActionsViewModel = viewModel(
        factory = viewModelFactory { initializer { SongActionsViewModel(container.repository, player) } },
    )
    val songState by songVm.state.collectAsStateWithLifecycle()

    val notices = rememberNoticeState()
    LaunchedEffect(songVm) { songVm.notices.collect { notices.show(it) } }

    // Android's own prompts for a rename or delete: a system dialog, or a storage permission on 9 and older.
    val approvalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        songVm.onApprovalResult(result.resultCode == Activity.RESULT_OK)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        songVm.onPermissionResult(granted)
    }
    LaunchedEffect(songState.approval) {
        val request = songState.approval ?: return@LaunchedEffect
        songVm.onApprovalLaunched()
        try {
            approvalLauncher.launch(IntentSenderRequest.Builder(request).build())
        } catch (e: ActivityNotFoundException) {
            BugLog.w("Files", "Could not show Android's confirmation", e)
            songVm.onApprovalResult(false)
        }
    }
    LaunchedEffect(songState.permission) {
        val permission = songState.permission ?: return@LaunchedEffect
        songVm.onPermissionRequested()
        permissionLauncher.launch(permission)
    }

    // Which song a sheet or dialog is about, by id, so they survive rotation and close by
    // themselves if the song leaves the library.
    var optionsId by rememberSaveable { mutableStateOf<Long?>(null) }
    var optionsFromPlayer by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showSleepTimer by rememberSaveable { mutableStateOf(false) }
    fun find(id: Long?): TrackEntity? = if (id == null) null else tracks?.firstOrNull { it.mediaStoreId == id }
    val openOptions: (TrackEntity) -> Unit = remember {
        { t ->
            optionsFromPlayer = false
            optionsId = t.mediaStoreId
        }
    }

    val nav = rememberNavController()
    val hazeState = remember { HazeState() }

    var showPlayer by rememberSaveable { mutableStateOf(false) }
    val sheet = remember { Animatable(0f) }
    val sheetOpen = showPlayer && playerState.hasMedia
    LaunchedEffect(sheetOpen) {
        if (sheetOpen) onFirstPlayerOpen()
        sheet.animateTo(if (sheetOpen) 1f else 0f, SheetSpring)
    }
    val sheetInComposition by remember { derivedStateOf { sheet.value > 0.001f } }

    BackHandler(enabled = showPlayer) { showPlayer = false }

    fun go(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val selectedIndex = DESTINATIONS.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

    // The Bugs tab shows a red dot while there is an error or crash the user has not looked at
    // yet. A crash from the previous run counts, so it is flagged as soon as the app reopens.
    val bugEntries by BugLog.entries.collectAsStateWithLifecycle()
    val latestProblemId = remember(bugEntries) { bugEntries.lastOrNull { it.isProblem }?.id ?: 0L }
    var seenProblemId by remember { mutableLongStateOf(0L) }
    LaunchedEffect(currentRoute, latestProblemId) {
        if (currentRoute == BUGS_ROUTE) seenProblemId = latestProblemId
    }
    val bugsAlert = latestProblemId > seenProblemId && currentRoute != BUGS_ROUTE

    // The screens only need to know which song is playing. Handing them this instead of the whole
    // player state means play/pause, shuffle, repeat and the queue filling in the background
    // never recompose the lists.
    val currentId = playerState.mediaId?.toLongOrNull()

    val bars = WindowInsets.systemBars.asPaddingValues()
    val contentPadding = PaddingValues(
        top = bars.calculateTopPadding(),
        bottom = bars.calculateBottomPadding() + FloatingChrome.reservedHeight(playerState.hasMedia),
    )

    val actions = remember(player) {
        NowPlayingActions(
            onClose = { showPlayer = false },
            onTogglePlay = player::togglePlayPause,
            onNext = player::next,
            onPrevious = player::previous,
            onSeek = player::seekTo,
            onToggleShuffle = player::toggleShuffle,
            onCycleRepeat = player::cycleRepeat,
            onSkipTo = player::skipTo,
            onRemove = player::removeAt,
            onSongOptions = {
                optionsFromPlayer = true
                optionsId = player.state.value.mediaId?.toLongOrNull()
            },
            onSleepTimer = { showSleepTimer = true },
        )
    }

    // Tab changes: the new screen comes forward out of depth on a spring, the old one falls back.
    val tabSpring = spring<Float>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

    Box(Modifier.fillMaxSize()) {
        // From Android 12L the sheet is real frosted glass: an opaque pane that paints its own
        // blurred copy of everything behind it, edge to edge. Once it is all the way up, drawing
        // that content a second time underneath it only costs a full-screen pass of the backdrop
        // shader every frame, so the copy in place is hidden. The blur keeps reading it: Haze
        // records the content once and draws that same recording into the sheet. The layer also
        // keeps the backdrop's redraw on every frame inside this box.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (SHEET_BLURS && sheet.value == 1f) 0f else 1f }
                .haze(hazeState),
        ) {
            AmbientAudioShaderBackground(Modifier.fillMaxSize())
            NavHost(
                navController = nav,
                startDestination = "home",
                // The transform sits on the content, not on the haze node, so blur stays registered.
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val p = sheet.value.coerceIn(0f, 1f)
                    val s = 1f - 0.07f * p
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.55f * p
                },
                enterTransition = { fadeIn(tween(220, delayMillis = 60)) + scaleIn(tabSpring, initialScale = 0.955f) },
                exitTransition = { fadeOut(tween(110)) + scaleOut(tween(160), targetScale = 1.03f) },
                popEnterTransition = { fadeIn(tween(220, delayMillis = 60)) + scaleIn(tabSpring, initialScale = 0.955f) },
                popExitTransition = { fadeOut(tween(110)) + scaleOut(tween(160), targetScale = 1.03f) },
            ) {
                composable("home") {
                    HomeScreen(
                        tracks = tracks,
                        recent = recent,
                        currentId = currentId,
                        player = player,
                        scanning = scanning,
                        contentPadding = contentPadding,
                        onOpenLibrary = { go("library") },
                        onRefresh = { vm.refresh(force = true) },
                        onTrackOptions = openOptions,
                    )
                }
                composable("library") {
                    LibraryScreen(
                        tracks = tracks,
                        currentId = currentId,
                        player = player,
                        scanning = scanning,
                        scanError = scanError,
                        contentPadding = contentPadding,
                        onRefresh = { vm.refresh(force = true) },
                        onTrackOptions = openOptions,
                    )
                }
                composable("search") { SearchScreen(vm, currentId, player, contentPadding, onTrackOptions = openOptions) }
                composable(BUGS_ROUTE) { BugLogScreen(contentPadding) }
            }
        }

        FloatingGlassNavAndMiniPlayer(
            hazeState = hazeState,
            destinations = DESTINATIONS,
            selectedIndex = selectedIndex,
            onSelect = { go(DESTINATIONS[it].route) },
            playerState = playerState,
            position = position,
            onTogglePlay = player::togglePlayPause,
            onNext = player::next,
            onOpenPlayer = { showPlayer = true },
            alertIndex = if (bugsAlert) DESTINATIONS.indexOfFirst { it.route == BUGS_ROUTE } else -1,
            modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer {
                // Drops away as the sheet takes over, and comes back as it leaves.
                val p = sheet.value.coerceIn(0f, 1f)
                alpha = (1f - p * 2.2f).coerceIn(0f, 1f)
                translationY = 36.dp.toPx() * p
            },
        )

        if (sheetInComposition) {
            Expanded3DNowPlayingSheet(
                state = playerState,
                position = position,
                progress = sheet,
                hazeState = hazeState,
                actions = actions,
                sound = sound,
                soundActions = soundVm.actions,
                sleepTimer = sleepTimer,
            )
        }

        NoticeHost(notices, Modifier.align(Alignment.TopCenter))
    }

    find(optionsId)?.let { track ->
        SongOptionsSheet(
            track = track,
            onDismiss = { optionsId = null },
            sleepDetail = if (optionsFromPlayer) sleepTimerLabel(sleepTimer) ?: "Off" else null,
            shortcutsDetail = if (volumeShortcuts) VOLUME_SHORTCUTS_ON else "Off",
            actions = SongOptionActions(
                onPlayNext = if (optionsFromPlayer) null else ({ songVm.playNext(track) }),
                onAddToQueue = if (optionsFromPlayer) null else ({ songVm.addToQueue(track) }),
                onSleepTimer = if (optionsFromPlayer) ({ showSleepTimer = true }) else null,
                onVolumeShortcuts = if (optionsFromPlayer) {
                    {
                        val on = !volumeShortcuts
                        container.volumeShortcuts.setEnabled(on)
                        notices.show(if (on) "Bluetooth volume shortcuts on" else "Bluetooth volume shortcuts off")
                    }
                } else {
                    null
                },
                onShare = { shareSong(context, track) },
                onRename = { renameId = track.mediaStoreId },
                onDelete = { deleteId = track.mediaStoreId },
            ),
        )
    }
    find(renameId)?.let { track ->
        RenameDialog(
            track = track,
            onDismiss = { renameId = null },
            onRename = { input ->
                renameId = null
                songVm.rename(track, input)
            },
        )
    }
    find(deleteId)?.let { track ->
        DeleteDialog(
            track = track,
            onDismiss = { deleteId = null },
            onDelete = {
                deleteId = null
                songVm.delete(track)
            },
        )
    }
    if (showSleepTimer) {
        SleepTimerSheet(
            timer = sleepTimer,
            onDismiss = { showSleepTimer = false },
            onMinutes = { minutes ->
                player.sleepIn(minutes)
                notices.show("Playback stops in ${minutesLabel(minutes)}")
            },
            onEndOfSong = {
                player.sleepAtEndOfSong()
                notices.show("Playback stops after this song")
            },
            onOff = {
                player.cancelSleepTimer()
                notices.show("Sleep timer off")
            },
        )
    }
}

/** Hands the song's file to another app (messages, Drive, Bluetooth) through the share sheet. */
private fun shareSong(context: Context, track: TrackEntity) {
    val uri = Uri.parse(track.uri)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = track.mimeType ?: "audio/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, track.title)
        clipData = ClipData.newRawUri(track.title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Share “${track.title}”"))
    } catch (e: ActivityNotFoundException) {
        BugLog.w("Share", "No app can receive the file", e)
    }
}
