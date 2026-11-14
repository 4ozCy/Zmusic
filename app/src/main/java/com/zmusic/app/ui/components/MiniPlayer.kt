package com.zmusic.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.zmusic.app.ui.theme.AppTheme

/**
 * Collapsed player: the live ring, the title, two bare icons and a hairline progress edge.
 * No card, no border. Progress is read only while drawing, so playback ticks never recompose it.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    player: PlayerController,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val position = player.position.collectAsStateWithLifecycle(initialValue = 0L)
    val primary = MaterialTheme.colorScheme.primary
    val track = AppTheme.extended.hairline
    Box(
        modifier
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClickLabel = "Open player", onClick = onOpen),
    ) {
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawRect(track)
            val f = if (state.durationMs > 0) (position.value.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            drawRect(primary, size = Size(size.width * f, size.height))
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadialSpectrum(Modifier.size(44.dp))
            // Crossfade the text on track change so the bar never jumps.
            Crossfade(targetState = state.title to state.artist, modifier = Modifier.weight(1f), label = "mini-meta") { (title, artist) ->
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row {
                IconButton(onClick = player::togglePlayPause) {
                    Crossfade(targetState = state.isPlaying, label = "mini-playpause") { isPlaying ->
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = primary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                IconButton(onClick = player::next) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next track")
                }
            }
        }
    }
}
