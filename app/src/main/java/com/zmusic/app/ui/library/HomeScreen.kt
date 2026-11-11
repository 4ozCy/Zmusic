package com.zmusic.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.ui.components.Artwork
import java.util.Calendar
import kotlin.random.Random

@Composable
fun HomeScreen(
    tracks: List<TrackEntity>?,
    recent: List<TrackEntity>,
    player: PlayerController,
    scanning: Boolean,
    onOpenLibrary: () -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Zmusic", style = MaterialTheme.typography.headlineMedium)
                }
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(24.dp).padding(end = 0.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Rescan library") }
                }
            }
        }

        when {
            tracks == null -> item {
                Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }

            tracks.isEmpty() -> item {
                EmptyLibrary(scanning, onRefresh)
            }

            else -> {
                item {
                    val albums = remember(tracks) { tracks.map { it.albumId }.distinct().size }
                    val artists = remember(tracks) { tracks.map { it.artist }.distinct().size }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatCard("Songs", tracks.size, Modifier.weight(1f), onOpenLibrary)
                        StatCard("Albums", albums, Modifier.weight(1f), onOpenLibrary)
                        StatCard("Artists", artists, Modifier.weight(1f), onOpenLibrary)
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                player.setShuffle(false)
                                player.playQueue(tracks, 0)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Text("Play all")
                        }
                        OutlinedButton(
                            onClick = {
                                player.setShuffle(true)
                                player.playQueue(tracks, Random.nextInt(tracks.size))
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null)
                            Text("Shuffle")
                        }
                    }
                }
                if (recent.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Recently added",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(recent, key = { _, t -> t.mediaStoreId }) { i, t ->
                                    Column(
                                        modifier = Modifier.width(128.dp).clickable { player.playQueue(recent, i) },
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Artwork(t.albumId, t.uri, t.album, Modifier.size(128.dp), 12.dp)
                                        Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            t.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyLibrary(scanning: Boolean, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("No music found", style = MaterialTheme.typography.titleMedium)
        Text(
            "Zmusic looks for audio files on this device that Android has indexed. Add some music, then rescan.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRefresh, enabled = !scanning) { Text(if (scanning) "Scanning\u2026" else "Scan again") }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    in 18..22 -> "Good evening"
    else -> "Late night listening"
}
