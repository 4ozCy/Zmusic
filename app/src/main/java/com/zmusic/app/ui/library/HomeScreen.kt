package com.zmusic.app.ui.library

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.components.EqBars
import com.zmusic.app.ui.components.GlassPill
import com.zmusic.app.ui.components.RescanButton
import com.zmusic.app.ui.components.Reveal
import com.zmusic.app.ui.components.ScreenHeader
import com.zmusic.app.ui.components.TextAction
import com.zmusic.app.ui.components.TrackRow
import com.zmusic.app.ui.components.frostedGlassCard
import com.zmusic.app.ui.components.tilt3DOnPress
import com.zmusic.app.ui.components.topFade
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.IndexStyle
import com.zmusic.app.ui.theme.Nocturne
import java.util.Calendar
import kotlin.random.Random

/**
 * Home. Editorial and airy: the greeting and the lists are bare type sitting directly on the
 * aurora. Glass appears only where something is meant to be picked up: the three action pills and
 * the "Recently added" tiles.
 *
 * @param contentPadding status bar at the top, floating dock and mini player at the bottom.
 */
@Composable
fun HomeScreen(
    tracks: List<TrackEntity>?,
    recent: List<TrackEntity>,
    playerState: PlayerUiState,
    player: PlayerController,
    scanning: Boolean,
    contentPadding: PaddingValues,
    onOpenLibrary: () -> Unit,
    onRefresh: () -> Unit,
    onTrackOptions: (TrackEntity) -> Unit,
) {
    val mix = if (tracks == null) emptyList() else daily(tracks)

    LazyColumn(
        modifier = Modifier.fillMaxSize().topFade(contentPadding.calculateTopPadding()),
        contentPadding = contentPadding,
    ) {
        item(key = "greeting") {
            val (eyebrow, headline) = remember { dayPart() }
            Reveal(0) {
                ScreenHeader(eyebrow, headline) { RescanButton(scanning, onRefresh) }
            }
        }

        when {
            tracks == null -> item(key = "loading") { LoadingIndicator(Modifier.fillMaxWidth().padding(48.dp)) }

            tracks.isEmpty() -> item(key = "empty") { EmptyLibrary(scanning, onRefresh) }

            else -> {
                item(key = "summary") { Reveal(90) { LibrarySummary(tracks, player, onOpenLibrary) } }

                if (recent.isNotEmpty()) {
                    item(key = "recent") {
                        Reveal(180) {
                            Column {
                                SectionTitle("Recently added", Modifier.padding(top = 40.dp, bottom = 16.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    itemsIndexed(recent, key = { _, t -> t.mediaStoreId }) { i, t ->
                                        RecentTile(
                                            position = i + 1,
                                            track = t,
                                            isCurrent = t.mediaStoreId.toString() == playerState.mediaId,
                                            onLongClick = { onTrackOptions(t) },
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

                item(key = "mix-header") { Reveal(270) { TodayMixHeader(mix, player) } }
                itemsIndexed(mix.take(6), key = { _, t -> "mix-${t.mediaStoreId}" }) { i, t ->
                    TrackRow(
                        track = t,
                        isCurrent = t.mediaStoreId.toString() == playerState.mediaId,
                        number = i + 1,
                        onLongClick = { onTrackOptions(t) },
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
            color = AppTheme.extended.text,
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
        color = AppTheme.extended.text,
        modifier = modifier.padding(horizontal = 24.dp),
    )
}

/** The library at a glance as three glass counters that count up, then the three action pills. */
@Composable
private fun LibrarySummary(tracks: List<TrackEntity>, player: PlayerController, onOpenLibrary: () -> Unit) {
    val albums = remember(tracks) { tracks.map { it.albumId }.distinct().size }
    val artists = remember(tracks) { tracks.map { it.artist }.distinct().size }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatTile(tracks.size, if (tracks.size == 1) "Song" else "Songs", Modifier.weight(1f))
            StatTile(albums, if (albums == 1) "Album" else "Albums", Modifier.weight(1f))
            StatTile(artists, if (artists == 1) "Artist" else "Artists", Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        // A LazyRow so the pills scroll rather than squash on narrow screens or large font scales.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                GlassPill("Play all", Icons.Rounded.PlayArrow, prominent = true) {
                    player.setShuffle(false)
                    player.playQueue(tracks, 0)
                }
            }
            item {
                GlassPill("Shuffle", Icons.Rounded.Shuffle) {
                    player.setShuffle(true)
                    player.playQueue(tracks, Random.nextInt(tracks.size))
                }
            }
            item { GlassPill("Browse", Icons.Rounded.LibraryMusic, onClick = onOpenLibrary) }
        }
    }
}

/** One counter. The number runs up from zero the first time it appears and follows later changes. */
@Composable
private fun StatTile(value: Int, label: String, modifier: Modifier = Modifier) {
    var target by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(value) { target = value }
    val shown by animateIntAsState(target, tween(900, easing = FastOutSlowInEasing), label = "stat")
    Column(
        modifier
            .frostedGlassCard(RoundedCornerShape(20.dp), surfaceAlpha = 0.10f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = shown.toString(),
            style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
            color = AppTheme.extended.text,
            maxLines = 1,
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.extended.textSoft,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * One recently added song as a floating glass tile: index numeral, title, artist. No picture.
 * Pressing tilts the tile in 3D toward the finger; the song that is playing shows live bars in
 * the corner, an amber numeral and a rim of amber-to-indigo light.
 */
@Composable
private fun RecentTile(
    position: Int,
    track: TrackEntity,
    isCurrent: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val amber = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier
            .width(164.dp)
            .height(152.dp)
            .tilt3DOnPress(
                onClickLabel = "Play ${track.title}",
                onLongClickLabel = "Song options",
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .frostedGlassCard(shape, surfaceAlpha = 0.12f)
            .then(if (isCurrent) Modifier.border(1.5.dp, CurrentRim, shape) else Modifier)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = position.toString().padStart(2, '0'),
                style = IndexStyle,
                color = if (isCurrent) amber else AppTheme.extended.textDim,
                modifier = Modifier.weight(1f),
            )
            if (isCurrent) EqBars(color = amber, modifier = Modifier.size(width = 16.dp, height = 14.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.extended.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.extended.textSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private val CurrentRim = Brush.linearGradient(listOf(Nocturne.Amber, Nocturne.Indigo.copy(alpha = 0.55f)))

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
fun EmptyLibrary(scanning: Boolean, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("No music found", style = MaterialTheme.typography.headlineSmall, color = AppTheme.extended.text)
        Text(
            "Zmusic looks for audio files on this device that Android has indexed. Add some music, then rescan.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.extended.textSoft,
        )
        GlassPill(if (scanning) "Scanning…" else "Scan again", Icons.Rounded.Refresh, Modifier.padding(top = 8.dp)) {
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
