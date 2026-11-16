package com.zmusic.app.ui.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.media3.common.Player
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.playback.QueueEntry
import com.zmusic.app.playback.SleepTimer
import com.zmusic.app.playback.eq.EqPreset
import com.zmusic.app.playback.eq.EqSettings
import com.zmusic.app.ui.components.EqBars
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.GlassIconButton
import com.zmusic.app.ui.components.GlassScrubber
import com.zmusic.app.ui.components.HairlineDivider
import com.zmusic.app.ui.components.KineticGyroscope
import com.zmusic.app.ui.components.PlayPauseButton
import com.zmusic.app.ui.components.ToggleButton
import com.zmusic.app.ui.components.TransportButton
import com.zmusic.app.ui.components.pressClick
import com.zmusic.app.ui.sound.SoundActions
import com.zmusic.app.ui.sound.SoundPanel
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne
import com.zmusic.app.ui.theme.TimeStyle
import com.zmusic.app.ui.theme.ZmusicTheme
import com.zmusic.app.util.formatTime
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.launch

/** Everything the sheet can ask the player to do. Keeps the content stateless and previewable. */
data class NowPlayingActions(
    val onClose: () -> Unit = {},
    val onTogglePlay: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onToggleShuffle: () -> Unit = {},
    val onCycleRepeat: () -> Unit = {},
    val onSkipTo: (Int) -> Unit = {},
    val onRemove: (Int) -> Unit = {},
    /** Opens the options sheet for the song that is playing. */
    val onSongOptions: () -> Unit = {},
    /** Opens the sleep timer picker. */
    val onSleepTimer: () -> Unit = {},
)

/** What fills the stage between the top bar and the scrubber. */
enum class NowPlayingPanel { Hero, Queue, Sound }

/** Spring the sheet opens, closes and settles on. Slightly underdamped: it lands, it does not ease. */
val SheetSpring = spring<Float>(dampingRatio = 0.80f, stiffness = 240f)

/**
 * Full-screen Now Playing: one large pane of dark frosted glass over the rest of the app, with the
 * 3D gyroscope standing where cover art would be.
 *
 * [progress] is the single source of truth for the transition, 0 = collapsed into the mini
 * player, 1 = fully open. The caller animates it on [SheetSpring]; this composable only reads it
 * inside `graphicsLayer`, so the whole transition runs without recomposition:
 *
 * - the pane rises from the bottom edge while un-tilting in 3D (`rotationX` hinged on its bottom
 *   edge, real perspective), with its top corners squaring off as it arrives;
 * - the sculpture and the title block scale and fade up slightly behind it, so the content
 *   arrives after the glass rather than with it.
 *
 * Dragging down anywhere (when the sculpture is on stage) pulls the same [progress] back with
 * the finger; letting go either closes or springs back, taking fling velocity into account.
 *
 * The stage swaps between three panels: the sculpture and title (hero), the queue, and the
 * equalizer (Sound). The top bar toggles the last two; back returns to the hero.
 *
 * @param hazeState the app's backdrop source. Null (previews, or no blur) gives solid obsidian.
 */
@Composable
fun Expanded3DNowPlayingSheet(
    state: PlayerUiState,
    position: State<Long>,
    progress: Animatable<Float, AnimationVector1D>,
    hazeState: HazeState?,
    actions: NowPlayingActions,
    sound: EqSettings,
    soundActions: SoundActions,
    sleepTimer: SleepTimer,
    modifier: Modifier = Modifier,
    initialPanel: NowPlayingPanel = NowPlayingPanel.Hero,
) {
    var panel by rememberSaveable { mutableStateOf(initialPanel) }
    val currentPanel = rememberUpdatedState(panel)
    val close by rememberUpdatedState(actions.onClose)
    val panelSpring = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    // 0 = hidden, 1 = on stage. Read only in graphicsLayer lambdas and the derived flags below.
    val queueIn = animateFloatAsState(if (panel == NowPlayingPanel.Queue) 1f else 0f, panelSpring, label = "queueIn")
    val soundIn = animateFloatAsState(if (panel == NowPlayingPanel.Sound) 1f else 0f, panelSpring, label = "soundIn")
    val heroVisible by remember { derivedStateOf { queueIn.value < 0.99f && soundIn.value < 0.99f } }
    val queueVisible by remember { derivedStateOf { queueIn.value > 0.01f } }
    val soundVisible by remember { derivedStateOf { soundIn.value > 0.01f } }
    fun toggle(target: NowPlayingPanel) {
        panel = if (panel == target) NowPlayingPanel.Hero else target
    }

    BackHandler(enabled = panel != NowPlayingPanel.Hero) { panel = NowPlayingPanel.Hero }

    val scope = rememberCoroutineScope()
    var dragX by remember { mutableFloatStateOf(0f) }

    // Direction of travel for the skip transition: forward when the queue index went up.
    val lastIndex = remember { intArrayOf(state.currentIndex) }
    val direction = if (state.currentIndex >= lastIndex[0]) 1 else -1
    SideEffect { lastIndex[0] = state.currentIndex }

    val backdrop = if (hazeState != null) {
        Modifier.hazeChild(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = Nocturne.Obsidian,
                tint = HazeTint(Nocturne.Obsidian.copy(alpha = 0.58f)),
                blurRadius = 44.dp,
                noiseFactor = 0.04f,
                fallbackTint = HazeTint(Nocturne.Obsidian.copy(alpha = 0.97f)),
            ),
        )
    } else {
        Modifier.background(Nocturne.Obsidian)
    }

    // The gesture lives on this untransformed box, so drag distance and fling velocity are
    // measured in screen space, not inside the layer that is moving under the finger.
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val velocity = VelocityTracker()
                fun settle(vy: Float) {
                    if (currentPanel.value != NowPlayingPanel.Hero) return
                    if (progress.value < 0.80f || vy > 1400f) {
                        close()
                    } else {
                        scope.launch { progress.animateTo(1f, SheetSpring) }
                    }
                }
                detectVerticalDragGestures(
                    onDragStart = { velocity.resetTracking() },
                    onDragEnd = { settle(velocity.calculateVelocity().y) },
                    onDragCancel = { settle(0f) },
                    onVerticalDrag = { change, dy ->
                        velocity.addPosition(change.uptimeMillis, change.position)
                        if (currentPanel.value == NowPlayingPanel.Hero) {
                            scope.launch { progress.snapTo((progress.value - dy / size.height).coerceIn(0f, 1f)) }
                        }
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value
                    val away = (1f - p).coerceAtLeast(0f)
                    translationY = away * size.height
                    // Unclamped on purpose: the spring's overshoot rocks the pane a hair past flat.
                    rotationX = (1f - p) * 14f
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    cameraDistance = 18f * density
                    val corner = 36.dp.toPx() * away
                    clip = corner > 0.5f
                    if (clip) shape = RoundedCornerShape(topStart = corner, topEnd = corner)
                }
                .then(backdrop)
                .drawWithCache {
                    // Specular lip along the top edge of the pane.
                    val lip = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.22f),
                        1f to Color.Transparent,
                        endY = 1.5.dp.toPx(),
                    )
                    onDrawWithContent {
                        drawContent()
                        drawRect(lip, alpha = ((1f - progress.value) * 6f).coerceIn(0f, 1f))
                    }
                },
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
                // Grabber
                Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(4.dp).background(Color.White.copy(alpha = 0.22f), CircleShape))
                }

                // Top bar. The label sits on the sheet's centre line whatever the buttons either side.
                Box(Modifier.fillMaxWidth().height(52.dp)) {
                    GlassIconButton(
                        Icons.Rounded.KeyboardArrowDown,
                        "Minimize player",
                        modifier = Modifier.align(Alignment.CenterStart),
                        onClick = actions.onClose,
                    )
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Eyebrow("Now playing", color = Color.White)
                        if (state.queue.isNotEmpty()) {
                            Text(
                                "${state.currentIndex + 1} / ${state.queue.size}",
                                style = TimeStyle,
                                color = AppTheme.extended.textSoft,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Row(Modifier.align(Alignment.CenterEnd)) {
                        val soundOn = panel == NowPlayingPanel.Sound
                        GlassIconButton(
                            icon = Icons.Rounded.Equalizer,
                            description = when {
                                soundOn -> "Hide equalizer"
                                sound.enabled -> "Show equalizer, on"
                                else -> "Show equalizer, off"
                            },
                            // Amber while the panel is open, or while the equalizer is shaping the sound.
                            tint = if (soundOn || sound.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            onClick = { toggle(NowPlayingPanel.Sound) },
                        )
                        val queueOn = panel == NowPlayingPanel.Queue
                        GlassIconButton(
                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                            description = if (queueOn) "Hide queue" else "Show queue",
                            tint = if (queueOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            onClick = { toggle(NowPlayingPanel.Queue) },
                        )
                    }
                }

                // Stage: hero, queue and Sound share this space and cross over as their springs move.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            val skipAt = 56.dp.toPx()
                            detectHorizontalDragGestures(
                                onDragStart = { dragX = 0f },
                                onDragEnd = {
                                    if (currentPanel.value == NowPlayingPanel.Hero) {
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
                                // Arrives a beat behind the glass, and steps back when a panel opens.
                                val away = maxOf(queueIn.value, soundIn.value)
                                val arrive = ((progress.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                alpha = arrive * (1f - away * 2f).coerceIn(0f, 1f)
                                val sc = lerp(0.84f, 1f, arrive) * lerp(1f, 0.94f, away)
                                scaleX = sc
                                scaleY = sc
                            },
                        ) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                KineticGyroscope(Modifier.fillMaxSize())
                                SleepChip(sleepTimer, onClick = actions.onSleepTimer, modifier = Modifier.align(Alignment.TopStart))
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                                TrackMeta(state.title, state.artist, state.album, direction, Modifier.weight(1f))
                                GlassIconButton(
                                    Icons.Rounded.MoreHoriz,
                                    "Song options",
                                    modifier = Modifier.padding(start = 8.dp),
                                    onClick = actions.onSongOptions,
                                )
                            }
                            Spacer(Modifier.height(18.dp))
                        }
                    }
                    if (queueVisible) {
                        QueuePanel(
                            s = state,
                            actions = actions,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                alpha = ((queueIn.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                translationY = lerp(16.dp.toPx(), 0f, queueIn.value)
                            },
                        )
                    }
                    if (soundVisible) {
                        SoundPanel(
                            settings = sound,
                            actions = soundActions,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                alpha = ((soundIn.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                translationY = lerp(16.dp.toPx(), 0f, soundIn.value)
                            },
                        )
                    }
                }

                GlassScrubber(position, state.durationMs, actions.onSeek)
                TimeRow(position, state.durationMs)
                Spacer(Modifier.height(10.dp))

                // Transport: bare icons either side of the one solid object on the sheet.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToggleButton(
                        icon = Icons.Rounded.Shuffle,
                        description = if (state.shuffle) "Shuffle on" else "Shuffle off",
                        active = state.shuffle,
                        onClick = actions.onToggleShuffle,
                    )
                    TransportButton(Icons.Rounded.SkipPrevious, "Previous track", onClick = actions.onPrevious)
                    PlayPauseButton(playing = state.isPlaying, size = 72.dp, onClick = actions.onTogglePlay)
                    TransportButton(Icons.Rounded.SkipNext, "Next track", onClick = actions.onNext)
                    ToggleButton(
                        icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        description = when (state.repeatMode) {
                            Player.REPEAT_MODE_ALL -> "Repeat all"
                            Player.REPEAT_MODE_ONE -> "Repeat one"
                            else -> "Repeat off"
                        },
                        active = state.repeatMode != Player.REPEAT_MODE_OFF,
                        onClick = actions.onCycleRepeat,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun TimeRow(position: State<Long>, durationMs: Long) {
    val c = Color.White
    // The position ticks four times a second, the clock only changes once: recompose on that.
    val elapsed by remember(position) { derivedStateOf { formatTime(position.value) } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(elapsed, style = TimeStyle, color = c)
        Text(formatTime(durationMs), style = TimeStyle, color = c)
    }
}

/**
 * Title block. A fixed minimum height with the text sitting on the bottom keeps the sculpture
 * from shifting between one-line and two-line titles. On a skip the text slides a short way in
 * the direction of travel.
 */
@Composable
private fun TrackMeta(title: String, artist: String, album: String, direction: Int, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = Triple(title, artist, album),
        modifier = modifier.heightIn(min = 116.dp),
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
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (al.isNotEmpty()) {
                Text(
                    text = al,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
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
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Clip,
            onTextLayout = { if (it.hasVisualOverflow) overflow = true },
            modifier = modifier,
        )
    } else {
        Text(
            text = text,
            style = style,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = modifier.basicMarquee(),
        )
    }
}

/** Compact header (small gyroscope, title, artist) over the queue list. */
@Composable
private fun QueuePanel(s: PlayerUiState, actions: NowPlayingActions, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (s.currentIndex - 1).coerceAtLeast(0))
    val primary = MaterialTheme.colorScheme.primary
    val rowStyle = MaterialTheme.typography.bodyLarge
    val currentStyle = remember(rowStyle) { rowStyle.copy(fontWeight = FontWeight.ExtraBold) }
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            KineticGyroscope(Modifier.size(52.dp), halo = false, segments = 24)
            Column(Modifier.weight(1f)) {
                Text(
                    s.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    s.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Eyebrow("Queue · ${s.queue.size}", modifier = Modifier.padding(bottom = 8.dp), color = Color.White)
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
                        color = Color.White,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            q.title,
                            // All type on this sheet is white; the playing row is marked by
                            // weight and by the amber bars beside it.
                            style = if (current) currentStyle else rowStyle,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            q.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (current) EqBars(primary, Modifier.size(width = 16.dp, height = 14.dp))
                    Box(
                        Modifier.size(48.dp).pressClick(onClickLabel = "Remove from queue") { actions.onRemove(q.index) },
                        contentAlignment = Alignment.Center,
                    ) {
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

// ---- Preview ---------------------------------------------------------------------------------

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

@Preview(name = "Now Playing", widthDp = 390, heightDp = 844)
@Composable
private fun NowPlayingPreview() {
    ZmusicTheme {
        val position = remember { mutableStateOf(92_000L) }
        val progress = remember { Animatable(1f) }
        Expanded3DNowPlayingSheet(
            state = PreviewState,
            position = position,
            progress = progress,
            hazeState = null,
            actions = NowPlayingActions(),
            sound = EqSettings(enabled = true, gainsDb = EqPreset.Rock.gainsDb),
            soundActions = SoundActions(),
            sleepTimer = SleepTimer.EndOfSong,
        )
    }
}

@Preview(name = "Now Playing, Sound panel", widthDp = 390, heightDp = 844)
@Composable
private fun NowPlayingSoundPreview() {
    ZmusicTheme {
        val position = remember { mutableStateOf(92_000L) }
        val progress = remember { Animatable(1f) }
        Expanded3DNowPlayingSheet(
            state = PreviewState,
            position = position,
            progress = progress,
            hazeState = null,
            actions = NowPlayingActions(),
            sound = EqSettings(enabled = true, gainsDb = EqPreset.Electronic.gainsDb, preampDb = -2f, bassDb = 3f),
            soundActions = SoundActions(),
            sleepTimer = SleepTimer.Off,
            initialPanel = NowPlayingPanel.Sound,
        )
    }
}
