package com.zmusic.app.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne

/*
 * Motion rules for this app:
 * - Everything you touch moves on a spring. Glass that floats (tiles, the dock lozenge, the play
 *   button, the sheet) is slightly underdamped so it lands with a small, physical overshoot.
 *   Plain text and list content stays well damped; type should not wobble.
 * - Every animation says something: press = physical detent, skip = direction of travel,
 *   visuals = the audio that is actually playing.
 * - Animated values are read inside graphicsLayer/draw lambdas so frames redraw, not recompose.
 */

/** True when the user turned system animations off. Decorative motion is skipped then. */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Light press feedback for general tappables. */
fun Modifier.pressClick(onClickLabel: String? = null, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = source,
            indication = null,
            onClickLabel = onClickLabel,
            role = Role.Button,
            onClick = onClick,
        )
}

/** Transport controls: a firm 0.94 detent plus a long-press haptic the moment it is pressed. */
fun Modifier.machinedClick(onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(pressed) {
        if (pressed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "detent",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
}

/**
 * Fade + short rise entrance, staggered by [delayMs]. Plays once per screen visit: the flag is
 * saved, so switching tabs or scrolling an item back into view does not replay it.
 */
@Composable
fun Reveal(delayMs: Int, content: @Composable () -> Unit) {
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(420, delayMs)) +
            slideInVertically(tween(480, delayMs, FastOutSlowInEasing)) { it / 8 },
    ) { content() }
}

/** Small condensed caps label above a headline. */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.extended.textSoft,
) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier)
}

/** 0.5dp rule at 10% ink. Use instead of boxes and borders. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(AppTheme.extended.hairline))
}

/**
 * Top of every tab: accent eyebrow, white display title, a short bar of light under it that
 * stretches with the bass, and an optional action on the right.
 */
@Composable
fun ScreenHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Eyebrow(eyebrow, color = MaterialTheme.colorScheme.primary)
            Text(
                text = title,
                style = MaterialTheme.typography.displayMedium,
                color = AppTheme.extended.text,
                modifier = Modifier.padding(top = 6.dp),
            )
            PulseBar(Modifier.padding(top = 14.dp))
        }
        action()
    }
}

/** The header's accent: an amber-to-indigo bar that is 40% long at rest and fills out on a kick. */
@Composable
private fun PulseBar(modifier: Modifier = Modifier) {
    val tracker = LocalAudioTracker.current
    Box(
        modifier.size(width = 76.dp, height = 3.dp).drawWithCache {
            val light = Brush.horizontalGradient(listOf(Nocturne.Amber, Nocturne.Indigo))
            val radius = CornerRadius(size.height / 2f)
            onDrawBehind {
                tracker.frame.longValue // redraw every frame without recomposing
                val w = size.width * (0.40f + 0.60f * tracker.bass.value)
                drawRoundRect(light, size = Size(w, size.height), cornerRadius = radius)
            }
        },
    )
}

/** Rescan the library. A small glass button that turns into a spinner while a scan is running. */
@Composable
fun RescanButton(scanning: Boolean, onRefresh: () -> Unit) {
    Box(
        modifier = Modifier
            .pressClick(onClickLabel = "Rescan library") { if (!scanning) onRefresh() }
            .size(48.dp)
            .padding(3.dp)
            .frostedGlassCard(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = scanning, label = "rescan") { busy ->
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "Rescan library",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Text-forward action: icon + label, no background, no border. Hierarchy comes from colour and weight. */
@Composable
fun TextAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (accent) MaterialTheme.colorScheme.primary else AppTheme.extended.text
    Row(
        modifier = modifier.heightIn(min = 48.dp).pressClick(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Dissolves scrolling content as it passes under the status bar instead of letting it collide
 * with the clock. [height] is the status bar inset; the fade finishes a little below it.
 * One offscreen layer for the whole list, masked with a cached gradient.
 */
fun Modifier.topFade(height: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val end = height.toPx() + 14.dp.toPx()
        val mask = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color.Black,
            startY = end * 0.40f,
            endY = end,
        )
        onDrawWithContent {
            drawContent()
            drawRect(mask, size = Size(size.width, end), blendMode = BlendMode.DstIn)
        }
    }
