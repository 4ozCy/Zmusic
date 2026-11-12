package com.zmusic.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.util.formatTime

/** Plain, scannable row. The current song gets an ember edge, ember title and live bars. */
@Composable
fun TrackRow(
    track: TrackEntity,
    isCurrent: Boolean,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val edge by animateFloatAsState(if (isCurrent) 1f else 0f, tween(250), label = "rowEdge")
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind {
                drawRect(primary.copy(alpha = edge), size = Size(3.dp.toPx(), size.height))
            }
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${track.artist} \u00B7 ${track.album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            EqBars(playing = isPlaying, color = primary, modifier = Modifier.size(width = 18.dp, height = 16.dp), bars = 3)
        }
        Text(
            text = formatTime(track.durationMs),
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
