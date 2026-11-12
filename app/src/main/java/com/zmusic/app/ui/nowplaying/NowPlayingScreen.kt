package com.zmusic.app.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.components.Aurora
import com.zmusic.app.ui.components.EqBars
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.PlayButton
import com.zmusic.app.ui.components.Spectrum
import com.zmusic.app.ui.theme.trackTint
import com.zmusic.app.util.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(player: PlayerController, onClose: () -> Unit) {
    val s by player.state.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    // Every song gets its own colour. It fades over 700 ms when the track changes.
    val tint by animateColorAsState(trackTint(s.title + s.artist, dark), tween(700), label = "tint")
    val tint2 by animateColorAsState(trackTint(s.artist + s.title + "~", dark), tween(700), label = "tint2")
    var showQueue by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            // Swipe down to minimize. The chevron button is the non-gesture alternative.
            detectVerticalDragGestures(
                onDragStart = { dragY = 0f },
                onDragEnd = { if (dragY > 220f) onClose() },
                onVerticalDrag = { _, d -> dragY += d },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Aurora(tint, tint2, MaterialTheme.colorScheme.background, Modifier.fillMaxWidth().height(540.dp))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Minimize player")
                    }
                    Eyebrow(
                        text = if (s.queue.isNotEmpty()) "Now playing \u00B7 ${s.currentIndex + 1} / ${s.queue.size}" else "Now playing",
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showQueue = true }) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = "Open queue")
                    }
                }

                Spacer(Modifier.height(36.dp))

                AnimatedContent(
                    targetState = Triple(s.title, s.artist, s.album),
                    transitionSpec = {
                        (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 5 }) togetherWith
                            (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 5 })
                    },
                    label = "meta",
                ) { (title, artist, album) ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.displaySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = artist.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                        Text(
                            text = album,
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(36.dp))

                // Swipe the spectrum left/right to change track. Previous/Next buttons are the alternative.
                Spectrum(
                    playing = s.isPlaying,
                    tint = tint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
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

                Spacer(Modifier.height(24.dp))
                SeekSection(player, s.durationMs, s.title + s.artist)
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
                            tint = if (s.shuffle) primary else muted,
                        )
                    }
                    IconButton(onClick = { player.previous() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track", modifier = Modifier.size(34.dp))
                    }
                    PlayButton(playing = s.isPlaying, size = 80.dp) { player.togglePlayPause() }
                    IconButton(onClick = { player.next() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", modifier = Modifier.size(34.dp))
                    }
                    IconButton(onClick = { player.cycleRepeat() }) {
                        Icon(
                            if (s.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            contentDescription = when (s.repeatMode) {
                                Player.REPEAT_MODE_ALL -> "Repeat all"
                                Player.REPEAT_MODE_ONE -> "Repeat one"
                                else -> "Repeat off"
                            },
                            tint = if (s.repeatMode == Player.REPEAT_MODE_OFF) muted else primary,
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            QueueList(s, player)
        }
    }
}

/**
 * Waveform scrubber. The bar shapes are generated from the song's name, so they are a stable
 * decorative fingerprint, not the real audio waveform (reading that would need decoding every file).
 * Tap or drag to seek; TalkBack gets a progress action.
 */
@Composable
private fun SeekSection(player: PlayerController, durationMs: Long, seed: String) {
    val position = player.position.collectAsStateWithLifecycle(initialValue = 0L)
    var drag by remember { mutableStateOf<Float?>(null) }
    val wave = remember(seed) { waveform(seed, 64) }
    val played = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val shownMs = drag?.let { (it * durationMs).toLong() } ?: position.value

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { o ->
                        player.seekTo(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            drag?.let { player.seekTo((it * durationMs).toLong()) }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                        onHorizontalDrag = { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) },
                    )
                }
                .semantics {
                    contentDescription = "Seek bar"
                    setProgress { target ->
                        player.seekTo((target.coerceIn(0f, 1f) * durationMs).toLong())
                        true
                    }
                },
        ) {
            val progress = drag ?: if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val slot = size.width / wave.size
            val barW = slot * 0.55f
            wave.forEachIndexed { i, v ->
                val h = size.height * v
                val done = (i + 0.5f) / wave.size <= progress
                drawRoundRect(
                    color = if (done) played else rest,
                    topLeft = Offset(i * slot + (slot - barW) / 2f, (size.height - h) / 2f),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
            Text(formatTime(shownMs), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(durationMs), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun waveform(seed: String, bars: Int): FloatArray {
    val rnd = java.util.Random(seed.hashCode().toLong())
    val out = FloatArray(bars)
    var prev = 0.5f
    for (i in 0 until bars) {
        prev = prev * 0.45f + rnd.nextFloat() * 0.55f
        out[i] = 0.18f + 0.82f * (prev * 1.4f).coerceAtMost(1f)
    }
    return out
}

@Composable
private fun QueueList(s: PlayerUiState, player: PlayerController) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (s.currentIndex - 2).coerceAtLeast(0))
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Queue \u00B7 ${s.queue.size} songs",
            style = MaterialTheme.typography.headlineSmall,
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
                        EqBars(s.isPlaying, primary, Modifier.size(width = 20.dp, height = 18.dp), bars = 3)
                        Spacer(Modifier.size(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            q.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current) primary else MaterialTheme.colorScheme.onSurface,
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
