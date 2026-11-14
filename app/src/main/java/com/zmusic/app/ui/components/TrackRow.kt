package com.zmusic.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne
import com.zmusic.app.ui.theme.TimeStyle
import com.zmusic.app.util.formatTime

/**
 * Plain, scannable row. Spacing does the grouping. The current song is marked by colour, live
 * bars, and a wash of amber light with a bar on its leading edge that fades in when it starts.
 * Pass [number] to show a position in an ordered list.
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
    val lit = animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "rowLit",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawWithCache {
                val wash = Brush.horizontalGradient(
                    0f to Nocturne.Amber.copy(alpha = 0.20f),
                    0.7f to Nocturne.Indigo.copy(alpha = 0.06f),
                    1f to Color.Transparent,
                )
                val barW = 3.dp.toPx()
                val barH = size.height * 0.5f
                val barRadius = CornerRadius(barW / 2f)
                onDrawBehind {
                    val a = lit.value
                    if (a > 0.01f) {
                        drawRect(wash, alpha = a)
                        // The bar grows from the middle as the row lights up.
                        val h = barH * a
                        drawRoundRect(Nocturne.Amber, Offset(0f, (size.height - h) / 2f), Size(barW, h), barRadius, alpha = a)
                    }
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (number != null) {
            Text(
                text = number.toString().padStart(2, '0'),
                style = TimeStyle,
                color = AppTheme.extended.textDim,
                modifier = Modifier.width(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) primary else AppTheme.extended.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${track.artist} \u00B7 ${track.album}",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.extended.textSoft,
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
            color = AppTheme.extended.textDim,
        )
    }
}
