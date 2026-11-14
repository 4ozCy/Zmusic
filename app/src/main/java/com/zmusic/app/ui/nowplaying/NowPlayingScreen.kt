package com.zmusic.app.ui.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.playback.QueueEntry
import com.zmusic.app.ui.components.AmbientGlow
import com.zmusic.app.ui.components.EqBars
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.HairlineDivider
import com.zmusic.app.ui.components.LocalAudioReactor
import com.zmusic.app.ui.components.PlayPauseButton
import com.zmusic.app.ui.components.RadialSpectrum
import com.zmusic.app.ui.components.Scrubber
import com.zmusic.app.ui.components.ToggleButton
import com.zmusic.app.ui.components.TransportButton
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.TimeStyle
import com.zmusic.app.ui.theme.ZmusicTheme
import com.zmusic.app.util.formatTime
import kotlinx.coroutines.launch

/** Everything the screen can ask the player to do. Keeps the content composable stateless and previewable. */
data class NowPlayingActions(
    val onClose: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onToggleShuffle: () -> Unit,
    val onCycleRepeat: () -> Unit,
    val onSkipTo: (Int) -> Unit,
    val onRemove: (Int) -> Unit,
)

@Composable
fun NowPlayingScreen(player: PlayerController, onClose: () -> Unit) {
    val s by player.state.collectAsStateWithLifecycle()
    val position = player.position.collectAsStateWithLifecycle(initialValue = 0L)
    val actions = remember(player, onClose) {
        NowPlayingActions(
            onClose = onClose,
            onTogglePlay = player::togglePlayPause,
            onNext = player::next,
            onPrevious = player::previous,
            onSeek = player::seekTo,
            onToggleShuffle = player::toggleShuffle,
            onCycleRepeat = player::cycleRepeat,
            onSkipTo = player::skipTo,
            onRemove = player::removeAt,
        )
    }
    NowPlayingContent(s, position, actions)
}

/**
 * Cover-free player. The live ring is the hero; the title block sits directly above the scrubber
 * so everything you read and touch is in the lower half. Opening the queue fades the hero out and
 * brings in a compact header plus the list in the same space.
 */
@Composable
fun NowPlayingContent(
    s: PlayerUiState,
    position: State<Long>,
    actions: NowPlayingActions,
    modifier: Modifier = Modifier,
) {
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val queueOpen = rememberUpdatedState(showQueue)
    // 0 = hero, 1 = queue. Read only in graphicsLayer lambdas and the two derived flags below.
    val dock = animateFloatAsState(
        targetValue = if (showQueue) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "dock",
    )
    val heroVisible by remember { derivedStateOf { dock.value < 0.99f } }
    val queueVisible by remember { derivedStateOf { dock.value > 0.01f } }

    BackHandler(enabled = showQueue) { showQueue = false }

    // The sheet follows the finger on the way down, then either closes or settles back.
    val scope = rememberCoroutineScope()
    val sheetY = remember { Animatable(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }

    // Direction of travel for the skip transition: forward when the queue index went up.
    val lastIndex = remember { intArrayOf(s.currentIndex) }
    val direction = if (s.currentIndex >= lastIndex[0]) 1 else -1
    SideEffect { lastIndex[0] = s.currentIndex }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { translationY = sheetY.value }
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                // Swipe down to minimize (only when the queue is closed). The chevron is the alternative.
                val closeAt = 96.dp.toPx()
                fun settle() {
                    if (sheetY.value > closeAt) actions.onClose() else scope.launch { sheetY.animateTo(0f, spring(dampingRatio = 1f)) }
                }
                detectVerticalDragGestures(
                    onDragEnd = ::settle,
                    onDragCancel = ::settle,
                    onVerticalDrag = { _, d ->
                        if (!queueOpen.value) scope.launch { sheetY.snapTo((sheetY.value + d).coerceAtLeast(0f)) }
                    },
                )
            },
    ) {
        AmbientGlow(Modifier.fillMaxWidth().fillMaxHeight(0.62f), reactor = LocalAudioReactor.current)

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
            // Top bar
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Minimize player")
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Eyebrow(
                        if (s.queue.isNotEmpty()) "Now playing \u00B7 ${s.currentIndex + 1} / ${s.queue.size}" else "Now playing",
                    )
                }
                IconButton(onClick = { showQueue = !showQueue }) {
                    Icon(
                        Icons.Rounded.QueueMusic,
                        contentDescription = if (showQueue) "Hide queue" else "Show queue",
                        tint = if (showQueue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Stage: hero and queue share this space and cross over as `dock` moves.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        val skipAt = 56.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { dragX = 0f },
                            onDragEnd = {
                                if (!queueOpen.value) {
                                    if (dragX > skipAt) actions.onPrevious() else if (dragX < -skipAt) actions.onNext()
                                }
                            },
                            onHorizontalDrag = { _, d -> dragX += d },
                        )
                    },
            ) {
                if (heroVisible) {
                    Column(
                        Modifier.fillMaxSize().graphicsLayer {
                            alpha = (1f - dock.value * 2f).coerceIn(0f, 1f)
                            val sc = lerp(1f, 0.96f, dock.value)
                            scaleX = sc
                            scaleY = sc
                        },
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            RadialSpectrum(Modifier.fillMaxSize(0.84f))
                        }
                        TrackMeta(s.title, s.artist, s.album, direction)
                        Spacer(Modifier.height(16.dp))
                    }
                }
                if (queueVisible) {
                    QueuePanel(
                        s = s,
                        actions = actions,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            alpha = ((dock.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
                            translationY = lerp(16.dp.toPx(), 0f, dock.value)
                        },
                    )
                }
            }

            Scrubber(position, s.durationMs, s.isPlaying, actions.onSeek)
            TimeRow(position, s.durationMs)
            Spacer(Modifier.height(12.dp))

            // Transport: bare icons, machined press
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleButton(
                    icon = Icons.Rounded.Shuffle,
                    description = if (s.shuffle) "Shuffle on" else "Shuffle off",
                    active = s.shuffle,
                    onClick = actions.onToggleShuffle,
                )
                TransportButton(Icons.Rounded.SkipPrevious, "Previous track", onClick = actions.onPrevious)
                PlayPauseButton(playing = s.isPlaying, size = 72.dp, onClick = actions.onTogglePlay)
                TransportButton(Icons.Rounded.SkipNext, "Next track", onClick = actions.onNext)
                ToggleButton(
                    icon = if (s.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    description = when (s.repeatMode) {
                        Player.REPEAT_MODE_ALL -> "Repeat all"
                        Player.REPEAT_MODE_ONE -> "Repeat one"
                        else -> "Repeat off"
                    },
                    active = s.repeatMode != Player.REPEAT_MODE_OFF,
                    onClick = actions.onCycleRepeat,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TimeRow(position: State<Long>, durationMs: Long) {
    val c = AppTheme.extended.faint
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(position.value), style = TimeStyle, color = c)
        Text(formatTime(durationMs), style = TimeStyle, color = c)
    }
}

/**
 * Title block. A fixed minimum height with the text sitting on the bottom keeps the ring from
 * shifting between one-line and two-line titles. On a skip the text slides a short way in the
 * direction of travel.
 */
@Composable
private fun TrackMeta(title: String, artist: String, album: String, direction: Int, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = Triple(title, artist, album),
        modifier = modifier.fillMaxWidth().heightIn(min = 124.dp),
        contentAlignment = Alignment.BottomStart,
        transitionSpec = {
            val ease = FastOutSlowInEasing
            ((slideInHorizontally(tween(280, easing = ease)) { direction * it / 10 } + fadeIn(tween(280, easing = ease))) togetherWith
                (slideOutHorizontally(tween(200, easing = ease)) { -direction * it / 10 } + fadeOut(tween(160))))
                .using(SizeTransform(clip = false))
        },
        label = "meta",
    ) { (t, a, al) ->
        Column(Modifier.fillMaxWidth()) {
            TrackTitle(t, MaterialTheme.typography.displaySmall)
            Text(
                text = a,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (al.isNotEmpty()) {
                Text(
                    text = al,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.extended.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Two lines max. If the title still overflows, it becomes a single marquee line instead of an ellipsis. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackTitle(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    var overflow by remember(text) { mutableStateOf(false) }
    if (!overflow) {
        Text(
            text = text,
            style = style,
            maxLines = 2,
            overflow = TextOverflow.Clip,
            onTextLayout = { if (it.hasVisualOverflow) overflow = true },
            modifier = modifier,
        )
    } else {
        Text(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = modifier.basicMarquee(),
        )
    }
}

/** Compact header (small ring, title, artist) over the queue list. */
@Composable
private fun QueuePanel(s: PlayerUiState, actions: NowPlayingActions, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (s.currentIndex - 1).coerceAtLeast(0))
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RadialSpectrum(Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    s.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Eyebrow("Queue \u00B7 ${s.queue.size}", modifier = Modifier.padding(bottom = 8.dp))
        HairlineDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(s.queue, key = { q -> "${q.index}-${q.mediaId}" }) { q ->
                val current = q.index == s.currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.onSkipTo(q.index) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = (q.index + 1).toString().padStart(2, '0'),
                        style = TimeStyle,
                        color = if (current) primary else AppTheme.extended.faint,
                    )
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
                    if (current) EqBars(primary, Modifier.size(width = 16.dp, height = 14.dp))
                    IconButton(onClick = { actions.onRemove(q.index) }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Remove from queue",
                            tint = AppTheme.extended.faint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                HairlineDivider()
            }
        }
    }
}

// ---- Previews (light and dark) -------------------------------------------------------------

private val PreviewState = PlayerUiState(
    hasMedia = true,
    mediaId = "1",
    title = "Midnight City",
    artist = "M83",
    album = "Hurry Up, We're Dreaming",
    isPlaying = true,
    durationMs = 243_000L,
    queue = listOf(
        QueueEntry(0, "1", "Midnight City", "M83"),
        QueueEntry(1, "2", "Reunion", "M83"),
        QueueEntry(2, "3", "Wait", "M83"),
    ),
    currentIndex = 0,
)

private val PreviewActions = NowPlayingActions({}, {}, {}, {}, {}, {}, {}, {}, {})

@Composable
private fun NowPlayingPreviewHost(dark: Boolean) {
    ZmusicTheme(darkTheme = dark, seed = androidx.compose.ui.graphics.Color(0xFF7FA3C9)) {
        val position = remember { mutableStateOf(92_000L) }
        NowPlayingContent(PreviewState, position, PreviewActions)
    }
}

@Preview(name = "Now Playing - Dark", widthDp = 390, heightDp = 844)
@Composable
private fun NowPlayingDarkPreview() = NowPlayingPreviewHost(dark = true)

@Preview(name = "Now Playing - Light", widthDp = 390, heightDp = 844)
@Composable
private fun NowPlayingLightPreview() = NowPlayingPreviewHost(dark = false)
