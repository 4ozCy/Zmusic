package com.zmusic.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.theme.Nocturne

/**
 * Play/pause. Solid alabaster, the brightest object on the screen, sitting in a warm halo that
 * swells with the bass. The shape carries the state: a circle when paused, a squircle while
 * playing, morphing on a spring.
 */
@Composable
fun PlayPauseButton(playing: Boolean, size: Dp = 72.dp, onClick: () -> Unit) {
    val tracker = LocalAudioTracker.current
    val corner by animateDpAsState(
        targetValue = if (playing) size * 0.28f else size / 2,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "playCorner",
    )
    Box(
        modifier = Modifier
            .size(size)
            .drawWithCache {
                val halo = Brush.radialGradient(
                    0f to Nocturne.Amber.copy(alpha = 0.42f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = 1f,
                )
                onDrawBehind {
                    tracker.frame.longValue
                    val bass = tracker.bass.value
                    val r = this.size.minDimension * (0.72f + 0.22f * bass)
                    translate(this.size.width / 2f, this.size.height / 2f) {
                        scale(r, r, pivot = Offset.Zero) {
                            drawCircle(halo, radius = 1f, center = Offset.Zero, alpha = 0.35f + 0.65f * bass)
                        }
                    }
                }
            }
            .machinedClick(onClick)
            .clip(RoundedCornerShape(corner))
            .background(Nocturne.Alabaster),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = playing, label = "playpause") { isPlaying ->
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Nocturne.Obsidian,
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}

/** Bare icon with the machined press. No container. */
@Composable
fun TransportButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(56.dp).machinedClick(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(30.dp))
    }
}

/**
 * Shuffle / repeat. On is the amber accent plus a small dot under the icon, so the state reads
 * without relying on colour alone. Both fade rather than snap.
 */
@Composable
fun ToggleButton(icon: ImageVector, description: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tint by animateColorAsState(
        targetValue = if (active) primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "toggleTint",
    )
    val dot = animateFloatAsState(if (active) 1f else 0f, tween(200), label = "toggleDot")
    Box(
        modifier
            .size(56.dp)
            .machinedClick(onClick)
            .drawBehind {
                drawCircle(primary, 2.dp.toPx(), Offset(size.width / 2f, size.height - 8.dp.toPx()), alpha = dot.value)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}
