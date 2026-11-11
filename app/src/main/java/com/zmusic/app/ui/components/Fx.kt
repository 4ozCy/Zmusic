package com.zmusic.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.theme.AccentGradient
import com.zmusic.app.ui.theme.Mint
import com.zmusic.app.ui.theme.Violet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Visual effects. Everything animated here reads its animation values inside draw/graphicsLayer
 * blocks, so frames only redraw instead of recomposing. That keeps it smooth on mid-range phones.
 * NOTE: the equalizer/orb motion is decorative (driven by play state), not real audio analysis.
 */

/** Springy press feedback instead of a flat ripple. */
fun Modifier.bouncyClick(onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

/** Frosted-glass look: faint fill plus hairline border. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(20.dp)): Modifier =
    this
        .clip(shape)
        .background(Color.White.copy(alpha = 0.06f))
        .border(1.dp, Color.White.copy(alpha = 0.08f), shape)

/** Slow drifting colour glow, used behind headers. */
@Composable
fun Aurora(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "aurora")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing)),
        label = "auroraPhase",
    )
    Canvas(modifier) {
        val a = phase * 2f * PI.toFloat()
        val w = size.width
        val c1 = Offset(w * (0.2f + 0.15f * cos(a)), size.height * (0.25f + 0.12f * sin(a)))
        val c2 = Offset(w * (0.85f + 0.12f * sin(a)), size.height * (0.15f + 0.15f * cos(a)))
        drawCircle(
            brush = Brush.radialGradient(listOf(Mint.copy(alpha = 0.20f), Color.Transparent), center = c1, radius = w * 0.75f),
            radius = w * 0.75f,
            center = c1,
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(Violet.copy(alpha = 0.22f), Color.Transparent), center = c2, radius = w * 0.7f),
            radius = w * 0.7f,
            center = c2,
        )
    }
}

/** Animated bars. Flat when paused, dancing when [playing]. */
@Composable
fun EqBars(
    playing: Boolean,
    modifier: Modifier = Modifier,
    bars: Int = 4,
    brush: Brush = AccentGradient,
) {
    val t = rememberInfiniteTransition(label = "eq")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "eqPhase",
    )
    val energy by animateFloatAsState(if (playing) 1f else 0f, tween(500), label = "eqEnergy")
    Canvas(modifier) {
        val slot = size.width / bars
        val barW = slot * 0.55f
        val minFrac = 0.14f
        for (i in 0 until bars) {
            // Integer speed multipliers keep the loop seamless.
            val wave = 0.5f + 0.5f * sin(2f * PI.toFloat() * (phase * (1 + i % 3) + i * 0.37f))
            val h = size.height * (minFrac + (1f - minFrac) * wave * energy)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/** Cover-free centrepiece for Now Playing: glowing orb, spinning ring, live bars. */
@Composable
fun OrbVisualizer(playing: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "orbPhase",
    )
    val energy by animateFloatAsState(if (playing) 1f else 0f, tween(600), label = "orbEnergy")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = center
            val pulse = 1f + 0.05f * sin(phase * 4f * PI.toFloat()) * energy
            val glowR = r * 1.1f * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Mint.copy(alpha = 0.30f * (0.4f + 0.6f * energy)), Violet.copy(alpha = 0.12f), Color.Transparent),
                    center = c,
                    radius = glowR,
                ),
                radius = glowR,
                center = c,
            )
            drawCircle(color = Color.White.copy(alpha = 0.04f), radius = r * 0.78f, center = c)
            rotate(degrees = phase * 360f, pivot = c) {
                drawCircle(
                    brush = Brush.sweepGradient(listOf(Mint, Violet, Color.Transparent, Mint), center = c),
                    radius = r * 0.78f,
                    center = c,
                    style = Stroke(width = 3.dp.toPx()),
                    alpha = 0.4f + 0.6f * energy,
                )
            }
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = r * 0.78f,
                center = c,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        EqBars(playing, Modifier.fillMaxWidth(0.42f).fillMaxHeight(0.22f), bars = 9)
    }
}

/** Gradient play/pause button with a breathing glow while playing. */
@Composable
fun GradientPlayButton(playing: Boolean, size: Dp, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "glow")
    val glow by t.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glowPulse",
    )
    val on by animateFloatAsState(if (playing) 1f else 0f, tween(400), label = "glowOn")
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                val rad = this.size.minDimension * 0.85f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Mint.copy(alpha = glow * (0.25f + 0.75f * on)), Color.Transparent),
                        center = center,
                        radius = rad,
                    ),
                    radius = rad,
                )
            }
            .clip(CircleShape)
            .background(AccentGradient)
            .bouncyClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = playing, label = "playpause") { isPlaying ->
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color(0xFF00201B),
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** Initial-letter tile with a colour derived from [seed]. Not album art. */
@Composable
fun LetterTile(text: String, seed: String, modifier: Modifier = Modifier) {
    val hue = ((seed.hashCode() and 0x7fffffff) % 360).toFloat()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color.hsv(hue, 0.45f, 0.42f), Color.hsv((hue + 50f) % 360f, 0.5f, 0.26f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "\u266A",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}

/** Fade + rise entrance, staggered with [delayMs]. */
@Composable
fun Reveal(delayMs: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(450, delayMs)) + slideInVertically(tween(450, delayMs)) { it / 5 },
    ) { content() }
}
