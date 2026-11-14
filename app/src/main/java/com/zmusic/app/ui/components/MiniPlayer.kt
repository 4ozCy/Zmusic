package com.zmusic.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Collapsed player. The live ring (drawn by the sheet) sits in the left inset; this holds the
 * title, two bare icons and a hairline progress edge. No card, no border.
 */
@Composable
fun MiniBar(
    title: String,
    artist: String,
    playing: Boolean,
    position: State<Long>,
    durationMs: Long,
    startInset: Dp,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Box(modifier.clickable(onClickLabel = "Open player", onClick = onOpen)) {
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawRect(track)
            val f = if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            drawRect(primary, size = Size(size.width * f, size.height))
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(start = startInset, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggle) {
                Crossfade(targetState = playing, label = "mini-playpause") { isPlaying ->
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = primary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next track")
            }
        }
    }
}
