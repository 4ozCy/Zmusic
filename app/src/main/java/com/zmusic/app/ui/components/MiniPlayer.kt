package com.zmusic.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.trackTint

/** Docked (not floating) bar, washed with the current song's colour, progress as a thin top edge. */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    player: PlayerController,
    onOpen: () -> Unit,
) {
    val tint by animateColorAsState(
        trackTint(state.title + state.artist, isSystemInDarkTheme()),
        tween(600),
        label = "miniTint",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.26f), base)))
            .clickable(onClickLabel = "Open player", onClick = onOpen),
    ) {
        MiniProgress(player, state.durationMs)
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EqBars(state.isPlaying, tint, Modifier.size(width = 24.dp, height = 20.dp), bars = 4)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.artist.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlayButton(playing = state.isPlaying, size = 44.dp) { player.togglePlayPause() }
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
    val track = AppTheme.extended.hairline
    Canvas(Modifier.fillMaxWidth().height(2.dp)) {
        drawRect(track)
        val f = if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        drawRect(primary, size = Size(size.width * f, size.height))
    }
}
