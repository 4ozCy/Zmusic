package com.zmusic.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
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
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.components.LocalAudioReactor
import com.zmusic.app.ui.components.MiniPlayer
import com.zmusic.app.ui.components.pressClick
import com.zmusic.app.ui.components.rememberAudioReactor
import com.zmusic.app.ui.library.HomeScreen
import com.zmusic.app.ui.library.LibraryScreen
import com.zmusic.app.ui.library.LibraryViewModel
import com.zmusic.app.ui.library.SearchScreen
import com.zmusic.app.ui.nowplaying.NowPlayingScreen
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.DefaultSeed
import com.zmusic.app.ui.theme.ZmusicTheme
import com.zmusic.app.ui.theme.seedFromText
import kotlin.math.roundToInt

private val AUDIO_PERMISSION = if (Build.VERSION.SDK_INT >= 33) {
    Manifest.permission.READ_MEDIA_AUDIO
} else {
    Manifest.permission.READ_EXTERNAL_STORAGE
}

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val DESTINATIONS = listOf(
    Dest("home", "Home", Icons.Rounded.Home),
    Dest("library", "Library", Icons.Rounded.LibraryMusic),
    Dest("search", "Search", Icons.Rounded.Search),
)

@Composable
fun ZmusicRoot(container: AppContainer) {
    val context = LocalContext.current
    val vm: LibraryViewModel = viewModel(
        factory = viewModelFactory { initializer { LibraryViewModel(container.repository) } },
    )

    fun hasAudioAccess() =
        ContextCompat.checkSelfPermission(context, AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasAudioAccess()) }
    var denied by remember { mutableStateOf(false) }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
        if (ok) vm.refresh(force = true)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Covers coming back from system settings and picking up newly added music.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasAudioAccess()
        if (granted) vm.refresh()
    }

    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!granted) {
        ZmusicTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                PermissionScreen(
                    denied = denied,
                    onAllow = { audioLauncher.launch(AUDIO_PERMISSION) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    },
                )
            }
        }
    } else {
        SeededApp(vm, container)
    }
}

/**
 * Derives one muted seed colour from the current song's name and feeds it to the theme, so the
 * neutrals and the accent pick up a faint tint of whatever is playing. Also owns the single audio
 * reactor every live visual draws from.
 */
@Composable
private fun SeededApp(vm: LibraryViewModel, container: AppContainer) {
    val playerState by container.player.state.collectAsStateWithLifecycle()
    val seed = remember(playerState.mediaId, playerState.title, playerState.artist) {
        if (playerState.mediaId == null) DefaultSeed else seedFromText(playerState.title + playerState.artist)
    }
    val reactor = rememberAudioReactor(active = playerState.isPlaying)
    ZmusicTheme(seed = seed) {
        CompositionLocalProvider(LocalAudioReactor provides reactor) {
            MainShell(vm, container, playerState)
        }
    }
}

@Composable
private fun MainShell(
    vm: LibraryViewModel,
    container: AppContainer,
    playerState: PlayerUiState,
) {
    val player = container.player
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val recent by vm.recentlyAdded.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanError by vm.scanError.collectAsStateWithLifecycle()

    val nav = rememberNavController()
    var showPlayer by rememberSaveable { mutableStateOf(false) }

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

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    AnimatedVisibility(
                        visible = playerState.hasMedia,
                        enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(320)),
                        exit = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(220)),
                    ) {
                        MiniPlayer(playerState, player) { showPlayer = true }
                    }
                    NavBar(selectedIndex) { go(DESTINATIONS[it].route) }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = "home",
                modifier = Modifier.padding(padding),
                enterTransition = { fadeIn(tween(260)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 40 } },
                exitTransition = { fadeOut(tween(120)) },
                popEnterTransition = { fadeIn(tween(260)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 40 } },
                popExitTransition = { fadeOut(tween(120)) },
            ) {
                composable("home") {
                    HomeScreen(
                        tracks = tracks,
                        recent = recent,
                        playerState = playerState,
                        player = player,
                        scanning = scanning,
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
                        onRefresh = { vm.refresh(force = true) },
                    )
                }
                composable("search") { SearchScreen(vm, playerState, player) }
            }
        }

        AnimatedVisibility(
            visible = showPlayer && playerState.hasMedia,
            enter = slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(380)),
            exit = slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(280)),
        ) {
            NowPlayingScreen(player = player, onClose = { showPlayer = false })
        }
    }
}

/**
 * Bottom bar with no container: the page colour, a hairline on top, and one 28dp line that
 * slides between tabs on a critically damped spring. Selected icon lifts a few dp.
 */
@Composable
private fun NavBar(selectedIndex: Int, onSelect: (Int) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(AppTheme.extended.hairline))
        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
            val itemWidth = maxWidth / DESTINATIONS.size
            val density = LocalDensity.current
            val position by animateFloatAsState(
                targetValue = selectedIndex.toFloat(),
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                label = "navIndicator",
            )
            Row(Modifier.fillMaxSize()) {
                DESTINATIONS.forEachIndexed { i, d ->
                    NavItem(d, isSelected = i == selectedIndex, modifier = Modifier.weight(1f)) { onSelect(i) }
                }
            }
            Box(
                Modifier
                    .offset {
                        val x = with(density) { (itemWidth * position + (itemWidth - 28.dp) / 2).roundToPx() }
                        IntOffset(x, 0)
                    }
                    .width(28.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
                    .background(primary),
            )
        }
    }
}

@Composable
private fun NavItem(d: Dest, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
        animationSpec = tween(220),
        label = "navTint",
    )
    val lift by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium),
        label = "navLift",
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .pressClick(onClick)
            .semantics {
                role = Role.Tab
                this.selected = isSelected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = d.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp).graphicsLayer { translationY = -3.dp.toPx() * lift },
        )
        Text(
            text = d.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
