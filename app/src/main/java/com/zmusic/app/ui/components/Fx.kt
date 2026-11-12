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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.SignatureShape
import com.zmusic.app.ui.theme.SmallSignatureShape
import com.zmusic.app.ui.theme.trackTint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Shared building blocks. Animated values are read inside draw/graphicsLayer lambdas wherever
 * possible so frames redraw instead of recomposing (keeps it smooth on mid-range phones).
 * The spectrum and equalizer motion is decorative, driven by play state, not real audio analysis.
 */

/** Springy press feedback. */
fun Modifier.bouncyClick(onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
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

/** Small condensed caps label that sits above a headline. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier)
}

/** Fade + rise entrance, staggered by [delayMs]. */
@Composable
fun Reveal(delayMs: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(500, delayMs)) +
            slideInVertically(tween(550, delayMs, FastOutSlowInEasing)) { it / 6 },
    ) { content() }
}

/** Bars that dance while [playing] and rest flat when paused. */
@Composable
fun EqBars(playing: Boolean, color: Color, modifier: Modifier = Modifier, bars: Int = 4) {
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
        val minFrac = 0.16f
        for (i in 0 until bars) {
            val wave = 0.5f + 0.5f * sin(2f * PI.toFloat() * (phase * (1 + i % 3) + i * 0.37f))
            val h = size.height * (minFrac + (1f - minFrac) * wave * energy)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/** Soft drifting glow for Now Playing, coloured by the current song. */
@Composable
fun Aurora(tint: Color, tint2: Color, fadeTo: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "aurora")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
        label = "auroraPhase",
    )
    Canvas(modifier) {
        val a = phase * 2f * PI.toFloat()
        val w = size.width
        val c1 = Offset(w * (0.2f + 0.18f * cos(a)), size.height * (0.28f + 0.1f * sin(a)))
        val c2 = Offset(w * (0.85f + 0.12f * sin(a)), size.height * (0.18f + 0.12f * cos(a)))
        drawCircle(
            brush = Brush.radialGradient(listOf(tint.copy(alpha = 0.42f), Color.Transparent), center = c1, radius = w * 0.85f),
            radius = w * 0.85f,
            center = c1,
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(tint2.copy(alpha = 0.28f), Color.Transparent), center = c2, radius = w * 0.75f),
            radius = w * 0.75f,
            center = c2,
        )
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, fadeTo)))
    }
}

/** Mirrored spectrum, the lively centrepiece of Now Playing. */
@Composable
fun Spectrum(playing: Boolean, tint: Color, modifier: Modifier = Modifier, bars: Int = 40) {
    val accent = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "spectrum")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "spectrumPhase",
    )
    val energy by animateFloatAsState(if (playing) 1f else 0f, tween(700), label = "spectrumEnergy")
    Canvas(modifier) {
        val slot = size.width / bars
        val barW = slot * 0.5f
        val cy = size.height / 2f
        val brush = Brush.verticalGradient(listOf(tint, accent, tint))
        for (i in 0 until bars) {
            // Integer speed multipliers keep the loop seamless.
            val a = sin(2f * PI.toFloat() * (phase * (1 + i % 4) + i * 0.173f))
            val b = sin(2f * PI.toFloat() * (phase * (2 + i % 3) + i * 0.31f))
            val v = 0.5f + 0.25f * a + 0.25f * b
            val env = 0.3f + 0.7f * sin(PI.toFloat() * (i + 0.5f) / bars)
            val h = size.height * (0.06f + 0.94f * v * env * energy)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(i * slot + (slot - barW) / 2f, cy - h / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/** Cut-corner play/pause button. Glows softly while playing (only when big enough to matter). */
@Composable
fun PlayButton(playing: Boolean, size: Dp, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val glowOn = size >= 64.dp
    val t = rememberInfiniteTransition(label = "glow")
    val glow by t.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glowPulse",
    )
    val on by animateFloatAsState(if (playing && glowOn) 1f else 0f, tween(400), label = "glowOn")
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                if (on > 0.01f) {
                    val rad = this.size.minDimension * 0.9f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(primary.copy(alpha = glow * on), Color.Transparent),
                            center = center,
                            radius = rad,
                        ),
                        radius = rad,
                    )
                }
            }
            .clip(if (size >= 64.dp) SignatureShape else SmallSignatureShape)
            .background(primary)
            .bouncyClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = playing, label = "playpause") { isPlaying ->
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

@Composable
fun PrimaryButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .height(52.dp)
            .bouncyClick(onClick)
            .clip(SmallSignatureShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
        Text("  $label", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun GhostButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .height(52.dp)
            .bouncyClick(onClick)
            .clip(SmallSignatureShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, SmallSignatureShape)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        Text("  $label", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Typographic tile instead of a cover picture: song-coloured gradient, a giant italic initial,
 * title set in serif. Sizes are passed in so the strip can vary them (focal tile vs. the rest).
 */
@Composable
fun TypeTile(title: String, artist: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = trackTint(title + artist, isSystemInDarkTheme())
    val initial = title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "\u266A"
    Box(
        modifier = modifier
            .bouncyClick(onClick)
            .clip(SignatureShape)
            .background(
                Brush.verticalGradient(
                    listOf(tint.copy(alpha = 0.40f), MaterialTheme.colorScheme.surfaceContainer),
                ),
            )
            .border(1.dp, AppTheme.extended.hairline, SignatureShape),
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 140.sp, lineHeight = 140.sp, fontStyle = FontStyle.Italic),
            color = tint.copy(alpha = 0.32f),
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = (-6).dp),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).width(100.dp),
            )
        }
    }
}
