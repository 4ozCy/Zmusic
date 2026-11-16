package com.zmusic.app.ui.sound

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne
import com.zmusic.app.ui.theme.TimeStyle
import com.zmusic.app.util.formatDb
import kotlin.math.roundToInt

/**
 * One labelled level in dB: a hairline groove with the stretch between 0 dB and the value lit
 * in amber, so a cut and a boost read differently at a glance. Tap or drag anywhere along it.
 * Vertical swipes pass through to the scrolling panel.
 */
@Composable
fun LevelSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onChange)
    val span = range.endInclusive - range.start
    fun snap(v: Float) = ((v * 2f).roundToInt() / 2f).coerceIn(range.start, range.endInclusive)
    fun valueAt(x: Float, width: Float, pad: Float) =
        snap(range.start + ((x - pad) / (width - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f) * span)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = formatDb(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps = (span * 2).roundToInt() - 1)
                setProgress { target ->
                    change(snap(target))
                    true
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.extended.text,
            modifier = Modifier.width(92.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .pointerInput(range) {
                    val pad = 9.dp.toPx()
                    detectTapGestures { change(valueAt(it.x, size.width.toFloat(), pad)) }
                }
                .pointerInput(range) {
                    val pad = 9.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { change(valueAt(it.x, size.width.toFloat(), pad)) },
                    ) { pointer, _ ->
                        pointer.consume()
                        change(valueAt(pointer.position.x, size.width.toFloat(), pad))
                    }
                }
                .drawBehind {
                    val pad = 9.dp.toPx()
                    val cy = size.height / 2f
                    val usable = size.width - 2 * pad
                    fun xFor(v: Float) = pad + (v - range.start) / span * usable
                    val stroke = 3.dp.toPx()
                    drawLine(Color.White.copy(alpha = 0.12f), Offset(pad, cy), Offset(size.width - pad, cy), stroke, StrokeCap.Round)
                    val zero = xFor(0f.coerceIn(range.start, range.endInclusive))
                    val x = xFor(value)
                    if (kotlin.math.abs(x - zero) > 0.5f) {
                        drawLine(Nocturne.Amber, Offset(zero, cy), Offset(x, cy), stroke, StrokeCap.Round)
                    }
                    // Mark 0 dB when it sits inside the range, so you can find flat again.
                    if (range.start < 0f) drawCircle(Color.White.copy(alpha = 0.35f), 2.dp.toPx(), Offset(zero, cy))
                    drawCircle(Nocturne.Amber.copy(alpha = 0.22f), 12.dp.toPx(), Offset(x, cy))
                    drawCircle(Nocturne.Alabaster, 7.dp.toPx(), Offset(x, cy))
                },
        )
        Text(
            text = formatDb(value),
            style = TimeStyle,
            color = AppTheme.extended.textSoft,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
    }
}
