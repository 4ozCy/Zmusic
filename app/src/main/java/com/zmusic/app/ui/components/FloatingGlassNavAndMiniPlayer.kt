package com.zmusic.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.theme.Nocturne
import dev.chrisbanes.haze.HazeState

/** One tab in the dock. */
@Immutable
data class DockDestination(val route: String, val label: String, val icon: ImageVector)

/** Sizes of the floating chrome, shared with the screens so their lists can pad past it. */
object FloatingChrome {
    val DockHeight = 64.dp
    val MiniPlayerHeight = 64.dp
    val Gap = 10.dp
    val EdgePadding = 16.dp
    val BottomPadding = 10.dp

    /** How much room scrolling content must leave at the bottom, above the system navigation bar. */
    fun reservedHeight(miniPlayerVisible: Boolean): Dp =
        BottomPadding + DockHeight + (if (miniPlayerVisible) Gap + MiniPlayerHeight else 0.dp) + 20.dp
}

/**
 * The app's floating chrome: a glass mini player stacked on a glass navigation pill, docked above
 * the system navigation bar. Both are real frosted panes: they blur whatever scrolls beneath them
 * through [hazeState], so this must be placed as a sibling above the content that carries
 * `Modifier.haze(hazeState)`, not inside it.
 *
 * @param position playback position in ms; read only while drawing the progress filament.
 */
@Composable
fun FloatingGlassNavAndMiniPlayer(
    hazeState: HazeState,
    destinations: List<DockDestination>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    playerState: PlayerUiState,
    position: State<Long>,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = FloatingChrome.EdgePadding,
                end = FloatingChrome.EdgePadding,
                bottom = FloatingChrome.BottomPadding,
            ),
    ) {
        // The mini player rises out of the dock when the first song starts, on a spring.
        AnimatedVisibility(
            visible = playerState.hasMedia,
            enter = expandVertically(spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessMediumLow), expandFrom = Alignment.Bottom) +
                scaleIn(spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.86f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                fadeIn(tween(180)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium), shrinkTowards = Alignment.Bottom) +
                scaleOut(tween(160), targetScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                fadeOut(tween(140)),
        ) {
            GlassMiniPlayer(
                hazeState = hazeState,
                state = playerState,
                position = position,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onOpen = onOpenPlayer,
                modifier = Modifier.padding(bottom = FloatingChrome.Gap),
            )
        }
        GlassDock(hazeState, destinations, selectedIndex, onSelect)
    }
}

/**
 * Navigation pill. A brighter glass lozenge sits behind the selected tab and slides between tabs
 * on an underdamped spring, so it arrives with a small, physical overshoot.
 */
@Composable
private fun GlassDock(
    hazeState: HazeState,
    destinations: List<DockDestination>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val slot = animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
        label = "dockSlot",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(FloatingChrome.DockHeight)
            .frostedGlassCard(CircleShape, hazeState = hazeState, surfaceAlpha = 0.10f)
            .drawWithCache {
                val pad = 6.dp.toPx()
                val h = size.height - pad * 2
                val radius = CornerRadius(h / 2f)
                val rim = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.40f),
                    1f to Color.White.copy(alpha = 0.03f),
                    startY = pad,
                    endY = pad + h,
                )
                val rimStroke = Stroke(1.dp.toPx())
                onDrawBehind {
                    val w = size.width / destinations.size
                    val topLeft = Offset(slot.value * w + pad, pad)
                    val lozenge = Size(w - pad * 2, h)
                    drawRoundRect(Color.White.copy(alpha = 0.11f), topLeft, lozenge, radius)
                    drawRoundRect(rim, topLeft, lozenge, radius, style = rimStroke)
                }
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            destinations.forEachIndexed { i, d ->
                DockItem(d, isSelected = i == selectedIndex, modifier = Modifier.weight(1f)) { onSelect(i) }
            }
        }
    }
}

@Composable
private fun DockItem(d: DockDestination, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "dockTint",
    )
    val lift = animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "dockLift",
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .pressClick(onClick = onClick)
            .semantics {
                role = Role.Tab
                selected = isSelected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = d.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp).graphicsLayer {
                val s = 1f + 0.10f * lift.value
                scaleX = s
                scaleY = s
                translationY = -1.5.dp.toPx() * lift.value
            },
        )
        Text(
            text = d.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Collapsed player: a small live gyroscope where a cover would be, the title, two bare icons and
 * a glowing progress filament along the bottom edge of the glass. Tap or flick it upward to open
 * Now Playing.
 */
@Composable
private fun GlassMiniPlayer(
    hazeState: HazeState,
    state: PlayerUiState,
    position: State<Long>,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open by rememberUpdatedState(onOpen)
    val duration by rememberUpdatedState(state.durationMs)
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(FloatingChrome.MiniPlayerHeight)
            .pressClick(onClickLabel = "Open player") { open() }
            .pointerInput(Unit) {
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = { if (travelled < -24.dp.toPx()) open() },
                    onVerticalDrag = { _, dy -> travelled += dy },
                )
            }
            .frostedGlassCard(shape, hazeState = hazeState, surfaceAlpha = 0.12f)
            .drawWithCache {
                val inset = 22.dp.toPx()
                val y = size.height - 1.5.dp.toPx()
                val stroke = 2.dp.toPx()
                val filament = Brush.horizontalGradient(
                    0f to Nocturne.Amber.copy(alpha = 0.35f),
                    1f to Nocturne.Alabaster,
                    startX = inset,
                    endX = size.width - inset,
                )
                onDrawWithContent {
                    drawContent()
                    val f = if (duration > 0) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    val end = inset + (size.width - inset * 2) * f
                    drawLine(Color.White.copy(alpha = 0.10f), Offset(inset, y), Offset(size.width - inset, y), stroke, StrokeCap.Round)
                    if (f > 0f) {
                        drawLine(Nocturne.Amber.copy(alpha = 0.22f), Offset(inset, y), Offset(end, y), stroke * 3f, StrokeCap.Round)
                        drawLine(filament, Offset(inset, y), Offset(end, y), stroke, StrokeCap.Round)
                    }
                }
            }
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KineticGyroscope(Modifier.size(44.dp), halo = false, segments = 24)
        // Crossfade the text on track change so the bar never jumps.
        Crossfade(
            targetState = state.title to state.artist,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            label = "mini-meta",
        ) { (title, artist) ->
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.size(48.dp).machinedClick(onTogglePlay), contentAlignment = Alignment.Center) {
            Crossfade(targetState = state.isPlaying, label = "mini-playpause") { isPlaying ->
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Box(Modifier.size(48.dp).machinedClick(onNext), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = "Next track",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
