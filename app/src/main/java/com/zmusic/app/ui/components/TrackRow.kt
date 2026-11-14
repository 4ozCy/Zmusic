package com.zmusic.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.TimeStyle
import com.zmusic.app.util.formatTime

/**
 * Plain, scannable row. Spacing does the grouping. The current song is marked by colour and live
 * bars. Pass [number] to show a position in an ordered list.
 */
@Composable
fun TrackRow(
    track: TrackEntity,
    isCurrent: Boolean,
    number: Int? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (number != null) {
            Text(
                text = number.toString().padStart(2, '0'),
                style = TimeStyle,
                color = AppTheme.extended.faint,
                modifier = Modifier.width(22.dp),
            )
        }
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
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (isCurrent) {
            EqBars(color = primary, modifier = Modifier.size(width = 16.dp, height = 14.dp))
        }
        Text(
            text = formatTime(track.durationMs),
            style = TimeStyle,
            color = AppTheme.extended.faint,
        )
    }
}
