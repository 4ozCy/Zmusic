package com.zmusic.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow

/**
 * Play/pause. The shape itself carries the state: a circle when paused, a 16dp squircle while
 * playing. Critically damped, no shockwave rings.
 */
@Composable
fun PlayPauseButton(playing: Boolean, size: Dp = 72.dp, onClick: () -> Unit) {
    val corner by animateDpAsState(
        targetValue = if (playing) 16.dp else size / 2,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "playCorner",
    )
    Box(
        modifier = Modifier
            .size(size)
            .machinedClick(onClick)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = playing, label = "playpause") { isPlaying ->
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}

/** Bare icon with the machined press. No container. */
@Composable
fun TransportButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Box(modifier.size(56.dp).machinedClick(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(30.dp))
    }
}
