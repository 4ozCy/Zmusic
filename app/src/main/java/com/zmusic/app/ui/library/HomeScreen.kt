package com.zmusic.app.ui.library

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.components.Artwork
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.HairlineDivider
import com.zmusic.app.ui.components.Reveal
import com.zmusic.app.ui.components.TextAction
import com.zmusic.app.ui.components.TrackRow
import com.zmusic.app.ui.components.pressClick
import com.zmusic.app.ui.theme.NumberStyle
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Reveal(0) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 28.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(dayPart().first, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = dayPart().second,
                            style = MaterialTheme.typography.displayMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (scanning) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
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
                item { Reveal(100) { LibrarySummary(tracks, player, onOpenLibrary) } }

                if (recent.isNotEmpty()) {
                    item {
                        Reveal(200) {
                            Column {
                                SectionTitle("Recently added", Modifier.padding(top = 36.dp, bottom = 16.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    itemsIndexed(recent, key = { _, t -> t.mediaStoreId }) { i, t ->
                                        ArtTile(t, if (i == 0) 176.dp else 128.dp) {
                                            player.setShuffle(false)
                                            player.playQueue(recent, i)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val mix = daily(tracks)
                item {
                    Reveal(300) {
                        TodayMixHeader(mix, player)
                    }
                }
                itemsIndexed(mix.take(6), key = { _, t -> "mix-${t.mediaStoreId}" }) { i, t ->
                    TrackRow(
                        track = t,
                        isCurrent = t.mediaStoreId.toString() == playerState.mediaId,
                        isPlaying = playerState.isPlaying,
                    ) {
                        player.setShuffle(false)
                        player.playQueue(mix, i)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayMixHeader(mix: List<TrackEntity>, player: PlayerController) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 40.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Today's mix",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        TextAction("Play", Icons.Rounded.PlayArrow, accent = true) {
            player.setShuffle(false)
            player.playQueue(mix, 0)
        }
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.padding(horizontal = 24.dp),
    )
}

/** No card: a big count, one line of detail, and text actions separated by a hairline. */
@Composable
private fun LibrarySummary(tracks: List<TrackEntity>, player: PlayerController, onOpenLibrary: () -> Unit) {
    val albums = remember(tracks) { tracks.map { it.albumId }.distinct().size }
    val artists = remember(tracks) { tracks.map { it.artist }.distinct().size }
    val count by animateIntAsState(tracks.size, tween(900), label = "songCount")

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 36.dp)) {
        HairlineDivider()
        Spacer(Modifier.height(20.dp))
        Eyebrow("Your library")
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(count.toString(), style = NumberStyle.copy(fontSize = 72.sp, lineHeight = 76.sp))
            Text(
                " songs",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                modifier = Modifier.padding(start = 6.dp, bottom = 14.dp),
            )
        }
        Text(
            "$albums albums \u00B7 $artists artists",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction("Play all", Icons.Rounded.PlayArrow, accent = true) {
                player.setShuffle(false)
                player.playQueue(tracks, 0)
            }
            Divider()
            TextAction("Shuffle", Icons.Rounded.Shuffle) {
                player.setShuffle(true)
                player.playQueue(tracks, Random.nextInt(tracks.size))
            }
            Divider()
            TextAction("Browse", Icons.Rounded.LibraryMusic, onClick = onOpenLibrary)
        }
        Spacer(Modifier.height(8.dp))
        HairlineDivider()
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .width(0.5.dp)
            .height(20.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
    )
}

@Composable
private fun ArtTile(track: TrackEntity, size: Dp, onClick: () -> Unit) {
    Column(Modifier.width(size).pressClick(onClick)) {
        Artwork(track.albumId, track.uri, Modifier.size(size), corner = 10.dp, px = 256)
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun EmptyLibrary(scanning: Boolean, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("No music found", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Zmusic looks for audio files on this device that Android has indexed. Add some music, then rescan.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
        )
        TextAction(if (scanning) "Scanning…" else "Scan again", Icons.Rounded.Refresh, accent = true) {
            if (!scanning) onRefresh()
        }
    }
}

/** Same picks all day, different tomorrow. */
@Composable
private fun daily(tracks: List<TrackEntity>): List<TrackEntity> = remember(tracks) {
    val cal = Calendar.getInstance()
    val seed = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    tracks.shuffled(Random(seed)).take(25)
}

/** (eyebrow word, headline) for the time of day. */
private fun dayPart(): Pair<String, String> = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Morning" to "Start the day\nwith sound."
    in 12..17 -> "Afternoon" to "Keep the\nmomentum going."
    in 18..22 -> "Evening" to "Wind down\nin style."
    else -> "Late night" to "Low lights,\nloud thoughts."
}
