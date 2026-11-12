package com.zmusic.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.zmusic.app.playback.AudioLevels
import kotlin.math.exp

/**
 * Smooths the raw audio levels to display rate. Attack is fast, release is slower, so bars
 * follow transients without flicker. One instance per visible screen; the frame loop runs only
 * while something is playing (or still decaying) and never allocates.
 */
@Stable
class AudioReactor {
    /** Bumped every frame. Read it inside a draw/graphicsLayer lambda to redraw without recomposing. */
    val frame = mutableLongStateOf(0L)
    val bands = FloatArray(AudioLevels.BANDS)
    var bass = 0f
        private set
    private var last = 0L

    val settled: Boolean
        get() {
            if (bass > 0.002f) return false
            for (v in bands) if (v > 0.002f) return false
            return true
        }

    fun step(active: Boolean, nanos: Long) {
        val dt = ((nanos - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
        last = nanos
        val attack = 1f - exp(-dt * 38f)
        val release = 1f - exp(-dt * 9f)
        for (i in bands.indices) {
            val target = if (active) AudioLevels.bands[i] else 0f
            bands[i] += (target - bands[i]) * (if (target > bands[i]) attack else release)
        }
        val tb = if (active) AudioLevels.bass else 0f
        bass += (tb - bass) * (if (tb > bass) attack else release)
        frame.longValue = nanos
    }
}

@Composable
fun rememberAudioReactor(active: Boolean): AudioReactor {
    val reduce = rememberReduceMotion()
    val reactor = remember { AudioReactor() }
    LaunchedEffect(active, reduce) {
        if (reduce) return@LaunchedEffect
        while (true) {
            withFrameNanos { reactor.step(active, it) }
            if (!active && reactor.settled) break
        }
    }
    return reactor
}

/**
 * Hairline micro-tick spectrum. Heights come from the real analysed bands, interpolated across
 * the tick count. Monochrome, no gradients.
 */
@Composable
fun TickSpectrum(reactor: AudioReactor, color: Color, modifier: Modifier = Modifier, ticks: Int = 56) {
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE")
        val subscribe = reactor.frame.longValue
        val bands = reactor.bands
        val slot = size.width / ticks
        val stroke = 1.5.dp.toPx()
        val minH = 2.dp.toPx()
        val cy = size.height / 2f
        for (i in 0 until ticks) {
            val pos = i * (bands.size - 1f) / (ticks - 1f)
            val lo = pos.toInt().coerceAtMost(bands.size - 2)
            val f = pos - lo
            val v = bands[lo] * (1f - f) + bands[lo + 1] * f
            val h = minH + (size.height - minH) * v
            val x = i * slot + slot / 2f
            drawLine(color, Offset(x, cy - h / 2f), Offset(x, cy + h / 2f), stroke, StrokeCap.Round)
        }
    }
}

private val EQ_BANDS = intArrayOf(1, 5, 9)

/** Three small bars (low / mid / high) for the current song's row. Driven by the real analysis. */
@Composable
fun EqBars(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val reactor = rememberAudioReactor(playing)
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE")
        val subscribe = reactor.frame.longValue
        val slot = size.width / 3f
        val barW = slot * 0.5f
        for (i in 0 until 3) {
            val v = reactor.bands[EQ_BANDS[i]]
            val h = size.height * (0.16f + 0.84f * v)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}
