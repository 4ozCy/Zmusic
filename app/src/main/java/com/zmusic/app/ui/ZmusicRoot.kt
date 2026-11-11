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
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zmusic.app.AppContainer
import com.zmusic.app.ui.components.MiniPlayer
import com.zmusic.app.ui.components.bouncyClick
import com.zmusic.app.ui.library.HomeScreen
import com.zmusic.app.ui.library.LibraryScreen
import com.zmusic.app.ui.library.LibraryViewModel
import com.zmusic.app.ui.library.PlaylistsScreen
import com.zmusic.app.ui.library.SearchScreen
import com.zmusic.app.ui.nowplaying.NowPlayingScreen
import com.zmusic.app.ui.theme.Mint

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
    Dest("playlists", "Playlists", Icons.Rounded.QueueMusic),
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
        MainShell(vm, container)
    }
}

@Composable
private fun MainShell(vm: LibraryViewModel, container: AppContainer) {
    val player = container.player
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val recent by vm.recentlyAdded.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanError by vm.scanError.collectAsStateWithLifecycle()
    val playerState by player.state.collectAsStateWithLifecycle()

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

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column(Modifier.navigationBarsPadding()) {
                    AnimatedVisibility(
                        visible = playerState.hasMedia,
                        enter = slideInVertically(tween(320)) { it } + fadeIn(tween(320)),
                        exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(220)),
                    ) {
                        MiniPlayer(playerState, player) { showPlayer = true }
                    }
                    val backStack by nav.currentBackStackEntryAsState()
                    val currentDestination = backStack?.destination
                    FloatingNav(
                        isSelected = { route -> currentDestination?.hierarchy?.any { it.route == route } == true },
                        onSelect = ::go,
                    )
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = "home",
                modifier = Modifier.padding(padding),
                enterTransition = { fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 40 } },
                exitTransition = { fadeOut(tween(120)) },
                popEnterTransition = { fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 40 } },
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
                composable("playlists") { PlaylistsScreen() }
            }
        }

        AnimatedVisibility(
            visible = showPlayer && playerState.hasMedia,
            enter = slideInVertically(tween(380)) { it } + fadeIn(tween(380)),
            exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(280)),
        ) {
            NowPlayingScreen(player = player, onClose = { showPlayer = false })
        }
    }
}

/** Floating pill bar. The selected item gets a tinted capsule and slides its label open. */
@Composable
private fun FloatingNav(isSelected: (String) -> Boolean, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DESTINATIONS.forEach { d ->
            NavItem(d, selected = isSelected(d.route)) { onSelect(d.route) }
        }
    }
}

@Composable
private fun NavItem(d: Dest, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (selected) Mint.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(250),
        label = "navBg",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) Mint else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(250),
        label = "navTint",
    )
    Row(
        modifier = Modifier
            .bouncyClick(onClick)
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(d.icon, contentDescription = d.label, tint = tint, modifier = Modifier.size(24.dp))
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(tween(250)) + expandHorizontally(tween(250)),
            exit = fadeOut(tween(150)) + shrinkHorizontally(tween(200)),
        ) {
            Text(
                text = d.label,
                color = tint,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
