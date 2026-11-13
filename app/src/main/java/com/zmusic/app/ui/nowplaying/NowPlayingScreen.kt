package com.zmusic.app.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.zmusic.app.lyrics.LyricLine
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.playback.QueueEntry
import com.zmusic.app.ui.components.ArtworkFrame
import com.zmusic.app.ui.components.AudioReactor
import com.zmusic.app.ui.components.EqBars
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.HairlineDivider
import com.zmusic.app.ui.components.PlayPauseButton
import com.zmusic.app.ui.components.Scrubber
import com.zmusic.app.ui.components.TickSpectrum
import com.zmusic.app.ui.components.TransportButton
import com.zmusic.app.ui.components.WashBackdrop
import com.zmusic.app.ui.components.rememberAudioReactor
import com.zmusic.app.ui.components.rememberReduceMotion
import com.zmusic.app.ui.theme.TimeStyle
import com.zmusic.app.ui.theme.ZmusicTheme
import com.zmusic.app.util.formatTime
import kotlin.math.roundToInt

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

enum class Panel { None, Queue, Lyrics }

private data class ArtKey(val albumId: Long, val uri: String)

@Composable
fun NowPlayingScreen(player: PlayerController, seed: Color, onClose: () -> Unit) {
    val s by player.state.collectAsStateWithLifecycle()
    val position = player.position.collectAsStateWithLifecycle(initialValue = 0L)
    val reactor = rememberAudioReactor(s.isPlaying)
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
    // No lyrics source exists yet, so this is always null and the panel shows its empty state.
    NowPlayingContent(s, position, reactor, seed, actions, lyrics = null)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingContent(
    s: PlayerUiState,
    position: State<Long>,
    reactor: AudioReactor,
    seed: Color,
    actions: NowPlayingActions,
    modifier: Modifier = Modifier,
    lyrics: List<LyricLine>? = null,
) {
    var panel by rememberSaveable { mutableStateOf(Panel.None) }
    val panelNow = rememberUpdatedState(panel)
    val dock by animateFloatAsState(
        targetValue = if (panel == Panel.None) 0f else 1f,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "dock",
    )
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val reduceMotion = rememberReduceMotion()
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }

    // Direction of travel for the skip transition: forward when the queue index went up.
    val lastIndex = remember { intArrayOf(s.currentIndex) }
    val direction = if (s.currentIndex >= lastIndex[0]) 1 else -1
    SideEffect { lastIndex[0] = s.currentIndex }

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                // Swipe down to minimize (only when no panel is open). The chevron is the alternative.
                detectVerticalDragGestures(
                    onDragStart = { dragY = 0f },
                    onDragEnd = { if (panelNow.value == Panel.None && dragY > 220f) actions.onClose() },
                    onVerticalDrag = { _, d -> dragY += d },
                )
            },
    ) {
        WashBackdrop(seed, Modifier.fillMaxWidth().fillMaxHeight(0.62f))

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
                IconButton(onClick = { panel = if (panel == Panel.Lyrics) Panel.None else Panel.Lyrics }) {
                    Icon(
                        Icons.Rounded.Lyrics,
                        contentDescription = "Lyrics",
                        tint = if (panel == Panel.Lyrics) primary else onSurface,
                    )
                }
                IconButton(onClick = { panel = if (panel == Panel.Queue) Panel.None else Panel.Queue }) {
                    Icon(
                        Icons.Rounded.QueueMusic,
                        contentDescription = "Queue",
                        tint = if (panel == Panel.Queue) primary else onSurface,
                    )
                }
            }

            // Stage: the cover docks into a compact header when a panel opens.
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragX = 0f },
                            onDragEnd = {
                                if (panelNow.value == Panel.None) {
                                    if (dragX > 120f) actions.onPrevious() else if (dragX < -120f) actions.onNext()
                                }
                            },
                            onHorizontalDrag = { _, d -> dragX += d },
                        )
                    },
            ) {
                val density = LocalDensity.current
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                val metaPx = with(density) { 118.dp.toPx() }
                val bigPx = minOf(w, h - metaPx).coerceAtLeast(with(density) { 120.dp.toPx() })
                val compactPx = with(density) { 56.dp.toPx() }
                val gapPx = with(density) { 20.dp.toPx() }
                val bigDp = with(density) { bigPx.toDp() }

                // Cover
                Box(
                    Modifier.size(bigDp).graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = lerp((w - bigPx) / 2f, 0f, dock)
                        val sc = lerp(1f, compactPx / bigPx, dock)
                        scaleX = sc
                        scaleY = sc
                    },
                ) {
                    // Breathing: 1.0 to 1.02, driven only by real low-end energy while playing.
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            if (!reduceMotion) {
                                @Suppress("UNUSED_VARIABLE")
                                val subscribe = reactor.frame.longValue
                                val kick = ((reactor.bass - 0.35f) / 0.5f).coerceIn(0f, 1f)
                                val b = 1f + 0.02f * kick
                                scaleX = b
                                scaleY = b
                            }
                        },
                    ) {
                        AnimatedContent(
                            targetState = ArtKey(s.albumId, s.trackUri),
                            transitionSpec = {
                                val d = direction
                                val ease = FastOutSlowInEasing
                                ((slideInHorizontally(tween(280, easing = ease)) { (it * 0.22f * d).toInt() } +
                                    fadeIn(tween(280, easing = ease)) +
                                    scaleIn(tween(280, easing = ease), initialScale = 0.94f)) togetherWith
                                    (slideOutHorizontally(tween(280, easing = ease)) { -(it * 0.22f * d).toInt() } +
                                        fadeOut(tween(280, easing = ease)) +
                                        scaleOut(tween(280, easing = ease), targetScale = 0.94f)))
                                    .using(SizeTransform(clip = false))
                            },
                            label = "art",
                        ) { key ->
                            ArtworkFrame(key.albumId, key.uri, Modifier.fillMaxSize())
                        }
                    }
                }

                // Full title block under the cover (fades out as the cover docks)
                TrackMeta(
                    title = s.title,
                    artist = s.artist,
                    album = s.album,
                    modifier = Modifier
                        .offset { IntOffset(0, (bigPx + gapPx).roundToInt()) }
                        .fillMaxWidth()
                        .graphicsLayer { alpha = (1f - dock * 2f).coerceIn(0f, 1f) },
                )

                // Compact title beside the docked cover (fades in)
                Row(
                    Modifier
                        .offset(x = 68.dp)
                        .height(56.dp)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = ((dock - 0.5f) * 2f).coerceIn(0f, 1f) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            s.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            s.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurface.copy(alpha = 0.70f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Queue or lyrics, below the docked header
                AnimatedContent(
                    targetState = panel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 76.dp)
                        .graphicsLayer { alpha = ((dock - 0.35f) / 0.65f).coerceIn(0f, 1f) },
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                    label = "panel",
                ) { p ->
                    when (p) {
                        Panel.Queue -> QueuePanel(s, actions)
                        Panel.Lyrics -> LyricsPanel(lyrics, position, actions.onSeek)
                        Panel.None -> Spacer(Modifier.fillMaxSize())
                    }
                }
            }

            // Telemetry: real spectrum ticks, monochrome
            TickSpectrum(reactor, primary.copy(alpha = 0.7f), Modifier.fillMaxWidth().height(28.dp))
            Spacer(Modifier.height(4.dp))
            Scrubber(position, s.durationMs, s.isPlaying, actions.onSeek)
            TimeRow(position, s.durationMs)
            Spacer(Modifier.height(12.dp))

            // Transport: bare icons, machined press
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    icon = Icons.Rounded.Shuffle,
                    description = if (s.shuffle) "Shuffle on" else "Shuffle off",
                    tint = if (s.shuffle) primary else onSurface.copy(alpha = 0.70f),
                    onClick = actions.onToggleShuffle,
                )
                TransportButton(Icons.Rounded.SkipPrevious, "Previous track", onClick = actions.onPrevious)
                PlayPauseButton(playing = s.isPlaying, size = 72.dp, onClick = actions.onTogglePlay)
                TransportButton(Icons.Rounded.SkipNext, "Next track", onClick = actions.onNext)
                TransportButton(
                    icon = if (s.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    description = when (s.repeatMode) {
                        Player.REPEAT_MODE_ALL -> "Repeat all"
                        Player.REPEAT_MODE_ONE -> "Repeat one"
                        else -> "Repeat off"
                    },
                    tint = if (s.repeatMode == Player.REPEAT_MODE_OFF) onSurface.copy(alpha = 0.70f) else primary,
                    onClick = actions.onCycleRepeat,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TimeRow(position: State<Long>, durationMs: Long) {
    val c = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(position.value), style = TimeStyle, color = c)
        Text(formatTime(durationMs), style = TimeStyle, color = c)
    }
}

@Composable
private fun TrackMeta(title: String, artist: String, album: String, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = Triple(title, artist, album),
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(280, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(160))
        },
        label = "meta",
    ) { (t, a, al) ->
        Column(Modifier.fillMaxWidth()) {
            TrackTitle(t, MaterialTheme.typography.displaySmall)
            Text(
                text = a,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (al.isNotEmpty()) {
                Text(
                    text = al,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
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

@Composable
private fun QueuePanel(s: PlayerUiState, actions: NowPlayingActions) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (s.currentIndex - 2).coerceAtLeast(0))
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize()) {
        Eyebrow("Up next \u00B7 ${s.queue.size}", modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(s.queue, key = { _, q -> "${q.index}-${q.mediaId}" }) { _, q ->
                val current = q.index == s.currentIndex
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { actions.onSkipTo(q.index) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (current) {
                            EqBars(s.isPlaying, primary, Modifier.size(width = 16.dp, height = 14.dp))
                        }
                        IconButton(onClick = { actions.onRemove(q.index) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove from queue")
                        }
                    }
                    HairlineDivider()
                }
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
    val seed = Color(0xFF7FA3C9)
    ZmusicTheme(darkTheme = dark, seed = seed) {
        val position = remember { mutableStateOf(92_000L) }
        val reactor = remember { AudioReactor() }
        NowPlayingContent(PreviewState, position, reactor, seed, PreviewActions)
    }
}

@Preview(name = "Now Playing - Dark", widthDp = 390, heightDp = 844)
@Composable
private fun NowPlayingDarkPreview() = NowPlayingPreviewHost(dark = true)

@Preview(name = "Now Playing - Light", widthDp = 390, heightDp = 844)
@Composable
private fun NowPlayingLightPreview() = NowPlayingPreviewHost(dark = false)
