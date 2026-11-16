package com.zmusic.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.launch

/*
 * Glass rules for this app.
 *
 * Glass is reserved for things that float: the dock, the mini player, the action pills, the
 * "Recently added" tiles, the Now Playing sheet. List rows never get a container.
 *
 * Two kinds of pane share one look:
 *
 * - Chrome (dock, mini player, sheet) passes a HazeState and gets a real live backdrop blur of
 *   whatever scrolls underneath it. Haze forbids a blurred child from living inside the blurred
 *   source, which is fine here: chrome is drawn as a sibling above the content.
 * - Panes inside the scrolling content (tiles, pills) pass no HazeState. The only thing behind
 *   them is the backdrop, and blurring it would cost a render pass per tile per frame. They get
 *   the same smoked tint, sheen and specular edge without the blur, which keeps list scrolling
 *   cheap. The black hole and stars show through these panes sharp, only tinted.
 */

/** Light catching the top edge of a pane and dying away down its sides. */
private val SpecularEdge = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.38f),
    0.30f to Color.White.copy(alpha = 0.12f),
    1f to Color.White.copy(alpha = 0.04f),
)

/**
 * A pane of smoked frosted glass.
 *
 * @param shape outline of the pane; content is clipped to it.
 * @param hazeState pass the app's [HazeState] for floating chrome to get a live backdrop blur.
 * Leave null for panes that sit inside the scrolling content (see the note at the top of this file).
 * @param surfaceAlpha strength of the white sheen, kept in `0.08f..0.14f`. The sheen is this
 * value at the top and fades to roughly half of it at the bottom, like light falling from above.
 * @param blurRadius backdrop blur radius when [hazeState] is set.
 */
fun Modifier.frostedGlassCard(
    shape: Shape = RoundedCornerShape(26.dp),
    hazeState: HazeState? = null,
    surfaceAlpha: Float = 0.11f,
    blurRadius: Dp = 28.dp,
): Modifier {
    val alpha = surfaceAlpha.coerceIn(0.08f, 0.14f)
    val body = if (hazeState != null) {
        Modifier.hazeChild(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = Nocturne.Obsidian,
                tint = HazeTint(Nocturne.Smoke.copy(alpha = 0.46f)),
                blurRadius = blurRadius,
                noiseFactor = 0.05f,
                // Devices without RenderEffect blur (below Android 12L) get nearly solid smoked glass.
                fallbackTint = HazeTint(Nocturne.Smoke.copy(alpha = 0.94f)),
            ),
        )
    } else {
        Modifier.background(Nocturne.Smoke.copy(alpha = 0.40f))
    }
    return this
        .clip(shape)
        .then(body)
        .background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = alpha),
                1f to Color.White.copy(alpha = alpha * 0.45f),
            ),
        )
        .border(1.dp, SpecularEdge, shape)
}

/**
 * Makes a pane feel like a physical tile: pressing it pushes the touched corner away from you
 * (`rotationX` / `rotationY` with a real perspective camera) and sinks it slightly, then it
 * springs back with a little overshoot. Scrolling away cancels the press and the click.
 *
 * Put it before [frostedGlassCard] in the chain so the whole pane, border included, tilts.
 */
fun Modifier.tilt3DOnPress(
    maxTiltDegrees: Float = 9f,
    pressedScale: Float = 0.965f,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }

    this
        .graphicsLayer {
            rotationX = tiltX.value
            rotationY = tiltY.value
            val s = 1f - (1f - pressedScale) * press.value
            scaleX = s
            scaleY = s
            // Compose multiplies by density itself; 14 gives visible but not cartoonish foreshortening.
            cameraDistance = 14f * density
        }
        .pointerInput(maxTiltDegrees, onLongClick != null) {
            val follow = spring<Float>(dampingRatio = 0.70f, stiffness = Spring.StiffnessMedium)
            val settle = spring<Float>(dampingRatio = 0.48f, stiffness = Spring.StiffnessMediumLow)
            detectTapGestures(
                onPress = { p ->
                    // -1..1 from the centre of the pane.
                    val nx = ((p.x / size.width) * 2f - 1f).coerceIn(-1f, 1f)
                    val ny = ((p.y / size.height) * 2f - 1f).coerceIn(-1f, 1f)
                    // Android: +rotationX sends the top edge back, +rotationY brings the right edge forward.
                    scope.launch { tiltX.animateTo(-ny * maxTiltDegrees, follow) }
                    scope.launch { tiltY.animateTo(-nx * maxTiltDegrees, follow) }
                    scope.launch { press.animateTo(1f, follow) }
                    tryAwaitRelease()
                    scope.launch { tiltX.animateTo(0f, settle) }
                    scope.launch { tiltY.animateTo(0f, settle) }
                    scope.launch { press.animateTo(0f, settle) }
                },
                onTap = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    click()
                },
                onLongPress = if (onLongClick == null) {
                    null
                } else {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        longClick?.invoke()
                    }
                },
            )
        }
        .semantics(mergeDescendants = true) {
            role = Role.Button
            onClick(label = onClickLabel) {
                click()
                true
            }
            if (onLongClick != null) {
                onLongClick(label = onLongClickLabel) {
                    longClick?.invoke()
                    true
                }
            }
        }
}

/**
 * Action pill: smoked frosted glass with white type, the same pane as every other button.
 * [prominent] marks the single most important action in a group by turning its icon amber (the
 * app's one accent), so use it once.
 */
@Composable
fun GlassPill(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    onClick: () -> Unit,
) {
    val content = AppTheme.extended.text
    Row(
        modifier = modifier
            .pressClick(onClick = onClick)
            .height(48.dp)
            .frostedGlassCard(CircleShape)
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (prominent) AppTheme.extended.amber else content,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Round glass button for a single icon. 48dp so it is a full touch target. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .pressClick(onClick = onClick)
            .size(48.dp)
            .padding(3.dp)
            .frostedGlassCard(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}
