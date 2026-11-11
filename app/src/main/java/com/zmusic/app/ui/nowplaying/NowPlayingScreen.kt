package com.zmusic.app.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.components.Aurora
import com.zmusic.app.ui.components.EqBars
import com.zmusic.app.ui.components.GradientPlayButton
import com.zmusic.app.ui.components.OrbVisualizer
import com.zmusic.app.ui.theme.Mint
import com.zmusic.app.util.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(player: PlayerController, onClose: () -> Unit) {
    val s by player.state.collectAsStateWithLifecycle()
    var showQueue by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            // Swipe down to minimize. The close button is the non-gesture alternative.
            detectVerticalDragGestures(
                onDragStart = { dragY = 0f },
                onDragEnd = { if (dragY > 220f) onClose() },
                onVerticalDrag = { _, d -> dragY += d },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Aurora(Modifier.fillMaxWidth().height(460.dp))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Minimize player")
                    }
                    Text(
                        "Now playing",
                        style = MaterialTheme.typography.labelLarge,
                        color = muted,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { showQueue = true }) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = "Open queue")
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Swipe the orb left/right to change track. Previous/Next buttons are the alternative.
                OrbVisualizer(
                    playing = s.isPlaying,
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .aspectRatio(1f)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { dragX = 0f },
                                onDragEnd = {
                                    if (dragX > 120f) player.previous() else if (dragX < -120f) player.next()
                                },
                                onHorizontalDrag = { _, d -> dragX += d },
                            )
                        },
                )

                Spacer(Modifier.height(28.dp))

                AnimatedContent(
                    targetState = Triple(s.title, s.artist, s.album),
                    transitionSpec = {
                        (fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 4 }) togetherWith
                            (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 4 })
                    },
                    label = "meta",
                ) { (title, artist, album) ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            album,
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                SeekBar(player, s.durationMs)
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { player.toggleShuffle() }) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            contentDescription = if (s.shuffle) "Shuffle on" else "Shuffle off",
                            tint = if (s.shuffle) accent else muted,
                        )
                    }
                    IconButton(onClick = { player.previous() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track", modifier = Modifier.size(36.dp))
                    }
                    GradientPlayButton(playing = s.isPlaying, size = 76.dp) { player.togglePlayPause() }
                    IconButton(onClick = { player.next() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = { player.cycleRepeat() }) {
                        Icon(
                            if (s.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            contentDescription = when (s.repeatMode) {
                                Player.REPEAT_MODE_ALL -> "Repeat all"
                                Player.REPEAT_MODE_ONE -> "Repeat one"
                                else -> "Repeat off"
                            },
                            tint = if (s.repeatMode == Player.REPEAT_MODE_OFF) muted else accent,
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            QueueList(s, player)
        }
    }
}

/** Isolated so the 4 Hz position ticks only recompose the seek bar, not the whole screen. */
@Composable
private fun SeekBar(player: PlayerController, durationMs: Long) {
    val position by player.position.collectAsStateWithLifecycle(initialValue = 0L)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val max = durationMs.coerceAtLeast(1L).toFloat()
    val shown = dragging ?: position.toFloat().coerceIn(0f, max)

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..max,
            colors = SliderDefaults.colors(
                thumbColor = Mint,
                activeTrackColor = Mint,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shown.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QueueList(s: PlayerUiState, player: PlayerController) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (s.currentIndex - 2).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Queue \u00B7 ${s.queue.size} songs",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(420.dp)) {
            itemsIndexed(s.queue, key = { _, q -> "${q.index}-${q.mediaId}" }) { _, q ->
                val current = q.index == s.currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { player.skipTo(q.index) }
                        .padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (current) {
                        EqBars(s.isPlaying, Modifier.size(width = 20.dp, height = 18.dp), bars = 3)
                        Spacer(Modifier.size(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            q.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            q.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { player.removeAt(q.index) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove from queue")
                    }
                }
            }
        }
    }
}
