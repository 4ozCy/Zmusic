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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.zmusic.app.ui.library.HomeScreen
import com.zmusic.app.ui.library.LibraryScreen
import com.zmusic.app.ui.library.LibraryViewModel
import com.zmusic.app.ui.library.PlaylistsScreen
import com.zmusic.app.ui.library.SearchScreen
import com.zmusic.app.ui.nowplaying.NowPlayingScreen

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

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    if (playerState.hasMedia) {
                        MiniPlayer(playerState, player) { showPlayer = true }
                    }
                    val backStack by nav.currentBackStackEntryAsState()
                    val currentDestination = backStack?.destination
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        DESTINATIONS.forEach { d ->
                            NavigationBarItem(
                                selected = currentDestination?.hierarchy?.any { it.route == d.route } == true,
                                onClick = {
                                    nav.navigate(d.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(d.icon, contentDescription = null) },
                                label = { Text(d.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") {
                    HomeScreen(
                        tracks = tracks,
                        recent = recent,
                        player = player,
                        scanning = scanning,
                        onOpenLibrary = {
                            nav.navigate("library") {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
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
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            NowPlayingScreen(player = player, onClose = { showPlayer = false })
        }
    }
}
