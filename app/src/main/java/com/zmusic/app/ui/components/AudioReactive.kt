package com.zmusic.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zmusic.app.playback.AudioLevels
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Smooths the raw audio levels to display rate. Attack is fast, release is slower, so bars
 * follow transients without flicker. The frame loop runs only while something is playing (or
 * still decaying) and never allocates.
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
 * The app's signature visual: a ring of hairline ticks whose lengths come from the real analysed
 * spectrum (mirrored left/right), with a soft glow that breathes with low-end energy. Everything
 * is sized from the canvas, so it stays crisp at 48dp in the mini player and at full size on Now Playing.
 */
@Composable
fun RadialSpectrum(reactor: AudioReactor, tint: Color, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier.drawWithCache {
            val s = minOf(size.width, size.height)
            val c = center
            val outer = s / 2f
            val inner = outer * 0.60f
            val maxLen = outer - inner
            val stroke = (s * 0.0065f).coerceAtLeast(1f)
            val n = if (s < 120.dp.toPx()) 36 else 72
            val half = n / 2
            val glowR = inner * 1.5f
            val glow = Brush.radialGradient(listOf(tint.copy(alpha = 0.55f), Color.Transparent), center = c, radius = glowR)
            onDrawBehind {
                @Suppress("UNUSED_VARIABLE")
                val subscribe = reactor.frame.longValue
                val kick = ((reactor.bass - 0.3f) / 0.5f).coerceIn(0f, 1f)
                drawCircle(glow, radius = glowR, center = c, alpha = 0.45f + 0.55f * kick)
                drawCircle(accent.copy(alpha = 0.22f), radius = inner, center = c, style = Stroke(stroke))
                val bands = reactor.bands
                for (i in 0 until n) {
                    val a = (i * 2.0 * PI / n - PI / 2).toFloat()
                    val f = (if (i < half) i else n - i).toFloat() / half
                    val pos = f * (bands.size - 1)
                    val lo = pos.toInt().coerceAtMost(bands.size - 2)
                    val frac = pos - lo
                    val v = bands[lo] * (1f - frac) + bands[lo + 1] * frac
                    val len = stroke * 2f + maxLen * v
                    val ca = cos(a)
                    val sa = sin(a)
                    drawLine(
                        color = accent.copy(alpha = 0.40f + 0.60f * v),
                        start = Offset(c.x + ca * inner, c.y + sa * inner),
                        end = Offset(c.x + ca * (inner + len), c.y + sa * (inner + len)),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        },
    )
}

private val EQ_BANDS = intArrayOf(1, 5, 9)

/** Three small bars (low / mid / high) marking the current song's row. Driven by the real analysis. */
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
