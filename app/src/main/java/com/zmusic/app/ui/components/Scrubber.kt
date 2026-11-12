package com.zmusic.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.theme.TimeStyle
import com.zmusic.app.util.formatTime
import kotlin.math.roundToInt

/**
 * Precision scrubber. A 2dp hairline while paused grows to a 4dp active bar while playing.
 * Dragging expands a thumb, ticks a haptic each 1%, and floats a monospaced time pill above it.
 * Position is read only inside draw lambdas, so playback ticks never recompose this.
 */
@Composable
fun Scrubber(
    positionMs: State<Long>,
    durationMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    val dragFraction = remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableIntStateOf(0) }

    val thickness by animateDpAsState(
        targetValue = if (playing) 4.dp else 2.dp,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
        label = "thickness",
    )
    val thumbT = animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
        label = "thumb",
    )

    Box(modifier.fillMaxWidth().height(48.dp).onSizeChanged { widthPx = it.width }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(durationMs) {
                    detectTapGestures { o ->
                        onSeek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                    }
                }
                .pointerInput(durationMs) {
                    var lastStep = -1
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            dragFraction.floatValue = (o.x / size.width).coerceIn(0f, 1f)
                            lastStep = (dragFraction.floatValue * 100).toInt()
                            dragging = true
                        },
                        onDragEnd = {
                            onSeek((dragFraction.floatValue * durationMs).toLong())
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ ->
                            val f = (change.position.x / size.width).coerceIn(0f, 1f)
                            dragFraction.floatValue = f
                            val step = (f * 100).toInt()
                            if (step != lastStep) {
                                lastStep = step
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                    )
                }
                .semantics {
                    contentDescription = "Seek bar"
                    setProgress { target ->
                        onSeek((target.coerceIn(0f, 1f) * durationMs).toLong())
                        true
                    }
                },
        ) {
            val cy = size.height / 2f
            val h = thickness.toPx()
            val f = if (dragging) {
                dragFraction.floatValue
            } else if (durationMs > 0) {
                (positionMs.value.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            drawLine(track, Offset(0f, cy), Offset(size.width, cy), h, StrokeCap.Round)
            if (f > 0f) drawLine(primary, Offset(0f, cy), Offset(size.width * f, cy), h, StrokeCap.Round)
            val r = thumbT.value * 9.dp.toPx()
            if (r > 0.5f) drawCircle(primary, r, Offset(size.width * f, cy))
        }
        TimePill(dragFraction, durationMs, widthPx, thumbT, Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun TimePill(
    fraction: FloatState,
    durationMs: Long,
    widthPx: Int,
    visibility: State<Float>,
    modifier: Modifier,
) {
    var pillW by remember { mutableIntStateOf(0) }
    Text(
        text = formatTime((fraction.floatValue * durationMs).toLong()),
        style = TimeStyle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .offset {
                val x = (fraction.floatValue * widthPx - pillW / 2f)
                    .coerceIn(0f, (widthPx - pillW).toFloat().coerceAtLeast(0f))
                IntOffset(x.roundToInt(), -34.dp.roundToPx())
            }
            .graphicsLayer { this.alpha = visibility.value }
            .onSizeChanged { pillW = it.width }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
