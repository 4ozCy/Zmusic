package com.zmusic.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState

/** Docked bar: cover, title, two bare icons, and a hairline progress edge. No card, no border. */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    player: PlayerController,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClickLabel = "Open player", onClick = onOpen),
    ) {
        MiniProgress(player, state.durationMs)
        Row(
            modifier = Modifier.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(state.albumId, state.trackUri, Modifier.size(44.dp), corner = 6.dp, px = 128)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { player.togglePlayPause() }) {
                Crossfade(targetState = state.isPlaying, label = "mini-playpause") { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            IconButton(onClick = { player.next() }) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next track")
            }
        }
    }
}

/** Position is read inside the draw block, so 4 Hz ticks never recompose anything. */
@Composable
private fun MiniProgress(player: PlayerController, durationMs: Long) {
    val position = player.position.collectAsStateWithLifecycle(initialValue = 0L)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawRect(track)
        val f = if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        drawRect(primary, size = Size(size.width * f, size.height))
    }
}
