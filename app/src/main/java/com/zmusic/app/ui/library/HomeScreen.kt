package com.zmusic.app.ui.library

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.components.Aurora
import com.zmusic.app.ui.components.Reveal
import com.zmusic.app.ui.components.TrackRow
import com.zmusic.app.ui.components.bouncyClick
import com.zmusic.app.ui.components.glass
import com.zmusic.app.ui.theme.AccentGradient
import com.zmusic.app.ui.theme.gradientTitleStyle
import java.util.Calendar
import kotlin.random.Random

@Composable
fun HomeScreen(
    tracks: List<TrackEntity>?,
    recent: List<TrackEntity>,
    playerState: PlayerUiState,
    player: PlayerController,
    scanning: Boolean,
    onOpenLibrary: () -> Unit,
    onRefresh: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Aurora(Modifier.fillMaxWidth().height(340.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Reveal(0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                greeting(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("Zmusic", style = gradientTitleStyle())
                        }
                        if (scanning) {
                            CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Rescan library")
                            }
                        }
                    }
                }
            }

            when {
                tracks == null -> item {
                    Row(Modifier.fillMaxWidth().padding(48.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }

                tracks.isEmpty() -> item { EmptyLibrary(scanning, onRefresh) }

                else -> {
                    item {
                        Reveal(90) { HeroCard(tracks, player, onOpenLibrary) }
                    }
                    if (recent.isNotEmpty()) {
                        item {
                            Reveal(180) {
                                Text(
                                    "Recently added",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 6.dp),
                                )
                            }
                        }
                        itemsIndexed(recent, key = { _, t -> t.mediaStoreId }) { i, t ->
                            TrackRow(
                                track = t,
                                isCurrent = t.mediaStoreId.toString() == playerState.mediaId,
                                isPlaying = playerState.isPlaying,
                            ) {
                                player.setShuffle(false)
                                player.playQueue(recent, i)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(tracks: List<TrackEntity>, player: PlayerController, onOpenLibrary: () -> Unit) {
    val albums = remember(tracks) { tracks.map { it.albumId }.distinct().size }
    val artists = remember(tracks) { tracks.map { it.artist }.distinct().size }
    val count by animateIntAsState(tracks.size, tween(900), label = "songCount")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .bouncyClick(onOpenLibrary)
            .glass(RoundedCornerShape(28.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                count.toString(),
                style = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp, brush = AccentGradient),
            )
            Text(
                "songs · $albums albums · $artists artists",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Play all", Icons.Rounded.PlayArrow, filled = true, modifier = Modifier.weight(1f)) {
                player.setShuffle(false)
                player.playQueue(tracks, 0)
            }
            PillButton("Shuffle", Icons.Rounded.Shuffle, filled = false, modifier = Modifier.weight(1f)) {
                player.setShuffle(true)
                player.playQueue(tracks, Random.nextInt(tracks.size))
            }
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    filled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val content = if (filled) Color(0xFF00201B) else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .height(48.dp)
            .bouncyClick(onClick)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(AccentGradient) else Modifier.glass(CircleShape)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Text("  $label", color = content, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun EmptyLibrary(scanning: Boolean, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("No music found", style = MaterialTheme.typography.titleMedium)
        Text(
            "Zmusic looks for audio files on this device that Android has indexed. Add some music, then rescan.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PillButton(
            label = if (scanning) "Scanning…" else "Scan again",
            icon = Icons.Rounded.Refresh,
            filled = true,
            modifier = Modifier.fillMaxWidth(0.6f),
        ) { if (!scanning) onRefresh() }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    in 18..22 -> "Good evening"
    else -> "Late night listening"
}
