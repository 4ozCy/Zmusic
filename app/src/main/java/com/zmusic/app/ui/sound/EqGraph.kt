package com.zmusic.app.ui.sound

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.zmusic.app.playback.eq.EQ_BAND_COUNT
import com.zmusic.app.playback.eq.EQ_BAND_MAX_DB
import com.zmusic.app.playback.eq.EQ_BAND_MIN_DB
import com.zmusic.app.playback.eq.EqDesign
import com.zmusic.app.playback.eq.EqSettings
import com.zmusic.app.playback.eq.eqBandLabel
import com.zmusic.app.ui.theme.Nocturne
import com.zmusic.app.ui.theme.TimeStyle
import com.zmusic.app.util.formatDb
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** The graph spans ±15 dB so a slider at ±12 never touches the edge and a bass boost still fits. */
private const val SPAN_DB = 15f
private const val CURVE_POINTS = 96

/**
 * Frequencies for the curve, log spaced so band `i` sits in the middle of column `i`:
 * 22 Hz at the left edge, 22 kHz at the right.
 */
private val CURVE_HZ = FloatArray(CURVE_POINTS) { j ->
    val x = j / (CURVE_POINTS - 1f)
    (31.25 * 2.0.pow(x * EQ_BAND_COUNT - 0.5)).toFloat()
}

/** Vertical position of [db] in a graph [height] px tall with [pad] px kept clear at each end. */
private fun yFor(db: Float, height: Float, pad: Float): Float =
    height / 2f - (db / SPAN_DB).coerceIn(-1f, 1f) * (height / 2f - pad)

private fun snap(db: Float): Float = ((db * 2f).roundToInt() / 2f).coerceIn(EQ_BAND_MIN_DB, EQ_BAND_MAX_DB)

/**
 * Ten vertical band sliders over the equalizer's real response curve: the same filter maths
 * the audio runs through, so the line passes through every slider and shows the bass boost on
 * top. Drag a column up or down to set its band (it moves by how far you drag, so it never
 * jumps), double-tap to put it back to 0 dB.
 */
@Composable
fun EqGraph(settings: EqSettings, onBandChange: (Int, Float) -> Unit, modifier: Modifier = Modifier) {
    val curve = remember(settings.gainsDb, settings.bassDb) {
        EqDesign.responseDb(EqSettings(enabled = true, gainsDb = settings.gainsDb, bassDb = settings.bassDb), CURVE_HZ)
    }
    var active by remember { mutableIntStateOf(-1) }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) { drawCurve(curve) }
        Row(Modifier.fillMaxSize()) {
            for (i in 0 until EQ_BAND_COUNT) {
                BandSlider(
                    index = i,
                    value = settings.gainsDb[i],
                    onChange = { onBandChange(i, it) },
                    onActive = { on -> active = if (on) i else -1 },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        if (active >= 0) {
            Text(
                text = "${eqBandLabel(active)} Hz  ${formatDb(settings.gainsDb[active])}",
                style = TimeStyle,
                color = Nocturne.Amber,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp),
            )
        }
    }
}

private fun DrawScope.drawCurve(curve: FloatArray) {
    val pad = 10.dp.toPx()
    val h = size.height
    val w = size.width
    val zero = yFor(0f, h, pad)
    // Grid: 0 dB, then ±6 and ±12 fainter.
    for (db in floatArrayOf(-12f, -6f, 6f, 12f)) {
        val y = yFor(db, h, pad)
        drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, y), Offset(w, y), 1f)
    }
    drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, zero), Offset(w, zero), 1.dp.toPx())

    val line = Path()
    val fill = Path().apply { moveTo(0f, zero) }
    for (j in curve.indices) {
        val x = w * j / (curve.size - 1f)
        val y = yFor(curve[j], h, pad)
        if (j == 0) line.moveTo(x, y) else line.lineTo(x, y)
        fill.lineTo(x, y)
    }
    fill.lineTo(w, zero)
    fill.close()
    drawPath(fill, Nocturne.Amber.copy(alpha = 0.10f))
    drawPath(line, Nocturne.Amber.copy(alpha = 0.22f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(
        line,
        Brush.horizontalGradient(listOf(Nocturne.Amber, Nocturne.Alabaster, Nocturne.Amber)),
        style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

@Composable
private fun BandSlider(
    index: Int,
    value: Float,
    onChange: (Float) -> Unit,
    onActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val active by rememberUpdatedState(onActive)
    val label = eqBandLabel(index)

    Box(
        modifier
            .pointerInput(Unit) {
                var start = 0f
                var travelled = 0f
                // What this drag last sent. The value passed in only catches up a frame later.
                var sent = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        start = current
                        sent = current
                        travelled = 0f
                        active(true)
                    },
                    onDragEnd = { active(false) },
                    onDragCancel = { active(false) },
                ) { pointer, dy ->
                    pointer.consume()
                    travelled += dy
                    val dbPerPx = 2f * SPAN_DB / (size.height - 2 * 10.dp.toPx()).coerceAtLeast(1f)
                    val next = snap(start - travelled * dbPerPx)
                    if (next != sent) {
                        // A tick on every whole dB, so you can count steps without looking.
                        if (floor(next) != floor(sent) || next == 0f) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        sent = next
                        change(next)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { change(0f) })
            }
            .semantics {
                contentDescription = "$label hertz"
                stateDescription = formatDb(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value, EQ_BAND_MIN_DB..EQ_BAND_MAX_DB, steps = 47)
                setProgress { target ->
                    change(snap(target))
                    true
                }
            }
            .drawBehind {
                val pad = 10.dp.toPx()
                val cx = size.width / 2f
                val zero = yFor(0f, size.height, pad)
                val y = yFor(value, size.height, pad)
                // Groove, the level from 0 dB to the thumb, then the thumb itself.
                drawLine(Color.White.copy(alpha = 0.10f), Offset(cx, pad), Offset(cx, size.height - pad), 1.dp.toPx())
                val barW = 3.dp.toPx()
                val top = minOf(zero, y)
                val bar = (zero - y).let { if (it < 0) -it else it }
                if (bar > 0.5f) {
                    drawRoundRect(
                        Nocturne.Amber.copy(alpha = 0.55f),
                        Offset(cx - barW / 2f, top),
                        Size(barW, bar),
                        CornerRadius(barW / 2f),
                    )
                }
                drawCircle(Nocturne.Amber.copy(alpha = 0.22f), 11.dp.toPx(), Offset(cx, y))
                drawCircle(Nocturne.Alabaster, 6.dp.toPx(), Offset(cx, y))
            },
    )
}
