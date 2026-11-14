package com.zmusic.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.theme.Nocturne
import com.zmusic.app.ui.theme.TimeStyle
import com.zmusic.app.util.formatTime
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Glass scrub bar. A frosted groove holds a hairline for the time still to come; the time already
 * played is a glowing filament that is a live waveform, not a decoration: its height follows the
 * bass, a faster ripple rides on it with the treble, and it travels toward the playhead. Paused,
 * it settles to a slow breathing line. The playhead is a bright bead in a warm halo.
 *
 * Dragging grows the bead, ticks a haptic each 1 % and floats a monospaced time pill above it.
 * Position and audio levels are read only inside the draw lambda, so neither playback ticks nor
 * frames recompose this.
 */
@Composable
fun GlassScrubber(
    positionMs: State<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tracker = LocalAudioTracker.current
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    val dragFraction = remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val inset = 20.dp

    val thumbT = animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.60f, stiffness = Spring.StiffnessMedium),
        label = "thumb",
    )

    Box(modifier.fillMaxWidth().height(52.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(vertical = 4.dp)
                .frostedGlassCard(CircleShape, surfaceAlpha = 0.08f)
                .padding(horizontal = inset)
                .onSizeChanged { widthPx = it.width }
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
                }
                .drawWithCache {
                    val filament = Brush.horizontalGradient(
                        0f to Nocturne.Amber.copy(alpha = 0.55f),
                        1f to Nocturne.Alabaster,
                    )
                    val halo = Brush.radialGradient(
                        0f to Nocturne.Amber.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                        center = Offset.Zero,
                        radius = 1f,
                    )
                    val wave = Path()
                    val core = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val bloom = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val stepPx = 3.dp.toPx()
                    val taper = 14.dp.toPx()
                    val wavelength = 34.dp.toPx()
                    onDrawBehind {
                        tracker.frame.longValue // redraw every frame without recomposing
                        val cy = size.height / 2f
                        val f = if (dragging) {
                            dragFraction.floatValue
                        } else if (durationMs > 0) {
                            (positionMs.value.toFloat() / durationMs).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val head = size.width * f
                        val bass = tracker.bass.value
                        val treble = tracker.treble.value
                        val t = tracker.seconds

                        // What is left to play: a still hairline.
                        drawLine(
                            Color.White.copy(alpha = 0.16f),
                            Offset(head, cy),
                            Offset(size.width, cy),
                            2.dp.toPx(),
                            StrokeCap.Round,
                        )

                        // What has played: the live filament. It is pinned flat at both ends so it
                        // leaves the left edge and meets the bead without a kink.
                        if (head > 1f) {
                            val amp = min(size.height * 0.30f, (1.dp.toPx() + 7.dp.toPx() * bass))
                            val ripple = 2.2.dp.toPx() * treble
                            wave.reset()
                            wave.moveTo(0f, cy)
                            var x = stepPx
                            while (x < head) {
                                val env = (min(x, head - x) / taper).coerceAtMost(1f)
                                val phase = x / wavelength * 6.2831855f
                                val y = cy + env * (amp * sin(phase - t * 5.2f) + ripple * sin(phase * 3.1f + t * 9f))
                                wave.lineTo(x, y)
                                x += stepPx
                            }
                            wave.lineTo(head, cy)
                            drawPath(wave, Nocturne.Amber, alpha = 0.10f + 0.22f * bass, style = bloom)
                            drawPath(wave, filament, style = core)
                        }

                        // Playhead.
                        val haloR = 13.dp.toPx() + 9.dp.toPx() * bass + 8.dp.toPx() * thumbT.value
                        translate(head, cy) {
                            scale(haloR, haloR, pivot = Offset.Zero) {
                                drawCircle(halo, radius = 1f, center = Offset.Zero, alpha = 0.55f + 0.45f * bass)
                            }
                        }
                        drawCircle(Nocturne.Alabaster, 4.dp.toPx() + 4.dp.toPx() * thumbT.value, Offset(head, cy))
                    }
                },
        )
        TimePill(dragFraction, durationMs, widthPx, thumbT, Modifier.align(Alignment.TopStart).padding(start = inset))
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
                val x = fraction.floatValue * widthPx - pillW / 2f
                IntOffset(x.roundToInt(), -32.dp.roundToPx())
            }
            .graphicsLayer { alpha = visibility.value.coerceIn(0f, 1f) }
            .onSizeChanged { pillW = it.width }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
