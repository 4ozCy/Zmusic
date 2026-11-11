package com.zmusic.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    player: PlayerController,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(state.albumId, state.trackUri, state.album.ifEmpty { state.title }, Modifier.size(44.dp), 8.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { player.togglePlayPause() }) {
                    Crossfade(targetState = state.isPlaying, label = "mini-playpause") { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                        )
                    }
                }
                IconButton(onClick = { player.next() }) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next track")
                }
            }
            MiniProgress(player, state.durationMs)
        }
    }
}

/** Own composable so the 4 Hz position updates only recompose this, not the whole mini player. */
@Composable
private fun MiniProgress(player: PlayerController, durationMs: Long) {
    val position = player.position.collectAsStateWithLifecycle(initialValue = 0L)
    LinearProgressIndicator(
        progress = {
            if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        },
        trackColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp)
            .height(2.dp),
    )
}
