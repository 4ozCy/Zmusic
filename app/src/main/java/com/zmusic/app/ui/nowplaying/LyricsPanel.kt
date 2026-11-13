package com.zmusic.app.ui.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.zmusic.app.lyrics.LyricLine

/**
 * Synced lyrics: the active line sits at 100% opacity, the rest at 30%, and the list glides so the
 * active line stays about a third of the way down. With no lyrics source yet, it says so plainly.
 */
@Composable
fun LyricsPanel(
    lines: List<LyricLine>?,
    position: State<Long>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lines.isNullOrEmpty()) {
        Column(modifier.fillMaxSize().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No lyrics for this song", style = MaterialTheme.typography.titleMedium)
            Text(
                "Zmusic doesn't read lyrics yet. Synced lines will scroll here once a lyrics source is added.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
            )
        }
    } else {
        val listState = rememberLazyListState()
        val active by remember(lines) {
            derivedStateOf { lines.indexOfLast { it.timeMs <= position.value }.coerceAtLeast(0) }
        }
        LaunchedEffect(active) {
            listState.animateScrollToItem(active, scrollOffset = -(listState.layoutInfo.viewportSize.height / 3))
        }
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(lines) { i, line ->
                val a by animateFloatAsState(if (i == active) 1f else 0.30f, tween(220), label = "lyricAlpha")
                Text(
                    text = line.text.ifEmpty { "\u266A" },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = a }
                        .clickable { onSeek(line.timeMs) },
                )
            }
        }
    }
}
