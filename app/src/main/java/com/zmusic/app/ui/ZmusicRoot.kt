package com.zmusic.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.zmusic.app.debug.BugLog
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.bugs.BugLogScreen
import com.zmusic.app.ui.components.AmbientAudioShaderBackground
import com.zmusic.app.ui.components.DockDestination
import com.zmusic.app.ui.components.FloatingChrome
import com.zmusic.app.ui.components.FloatingGlassNavAndMiniPlayer
import com.zmusic.app.ui.components.LocalAudioTracker
import com.zmusic.app.ui.components.rememberAudioFrequencyTracker
import com.zmusic.app.ui.library.HomeScreen
import com.zmusic.app.ui.library.LibraryScreen
import com.zmusic.app.ui.library.LibraryViewModel
import com.zmusic.app.ui.library.SearchScreen
import com.zmusic.app.ui.nowplaying.Expanded3DNowPlayingSheet
import com.zmusic.app.ui.nowplaying.NowPlayingActions
import com.zmusic.app.ui.nowplaying.SheetSpring
import com.zmusic.app.ui.theme.ZmusicTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

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

    ZmusicTheme {
        // The single tracker every live visual draws from.
        val tracker = rememberAudioFrequencyTracker(playing = playerState.isPlaying, captureAllowed = captureAllowed)
        CompositionLocalProvider(LocalAudioTracker provides tracker) {
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
 *
 * Layers 2 and 3 are siblings of the source, never children of it, which is what Haze requires.
 * One [Animatable] drives the whole expand transition: the sheet rises and un-tilts, the screen
 * behind it sinks back in depth, and the dock drops away.
 */
@Composable
private fun MainShell(
    vm: LibraryViewModel,
    container: AppContainer,
    playerState: PlayerUiState,
    onFirstPlayerOpen: () -> Unit,
) {
    val player = container.player
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val recent by vm.recentlyAdded.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanError by vm.scanError.collectAsStateWithLifecycle()
    // One position ticker shared by the mini player and the sheet. Read only in draw lambdas.
    val position = player.position.collectAsStateWithLifecycle(initialValue = 0L)

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
        )
    }

    // Tab changes: the new screen comes forward out of depth on a spring, the old one falls back.
    val tabSpring = spring<Float>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().haze(hazeState)) {
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
                        playerState = playerState,
                        player = player,
                        scanning = scanning,
                        contentPadding = contentPadding,
                        onOpenLibrary = { go("library") },
                        onRefresh = { vm.refresh(force = true) },
                    )
                }
                composable("library") {
                    LibraryScreen(
                        tracks = tracks,
                        playerState = playerState,
                        player = player,
                        scanning = scanning,
                        scanError = scanError,
                        contentPadding = contentPadding,
                        onRefresh = { vm.refresh(force = true) },
                    )
                }
                composable("search") { SearchScreen(vm, playerState, player, contentPadding) }
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
            )
        }
    }
}
