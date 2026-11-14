package com.zmusic.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zmusic.app.playback.AudioFrequencyTracker
import com.zmusic.app.playback.AudioSession

/**
 * The one tracker the whole UI draws from, provided by the app shell, so a single frame loop
 * feeds every visual. The default is an idle tracker, which keeps previews working.
 */
val LocalAudioTracker = staticCompositionLocalOf { AudioFrequencyTracker() }

/**
 * Creates the app's [AudioFrequencyTracker] and ties it to the screen:
 *
 * - The Visualizer is attached on `ON_START` and released on `ON_STOP` and on dispose, so the
 *   native effect never outlives the visible UI. It is re-attached when the player's session id
 *   changes or when [captureAllowed] flips (the user granted `RECORD_AUDIO`).
 * - One `withFrameNanos` loop steps the smoothing. Compose pauses that clock while the activity
 *   is stopped, so nothing runs in the background.
 * - With system animations turned off the loop does not run at all and every visual stays still.
 */
@Composable
fun rememberAudioFrequencyTracker(playing: Boolean, captureAllowed: Boolean): AudioFrequencyTracker {
    val tracker = remember { AudioFrequencyTracker() }
    val reduceMotion = rememberReduceMotion()
    val sessionId by AudioSession.id.collectAsStateWithLifecycle()
    val isPlaying by rememberUpdatedState(playing)

    LifecycleStartEffect(sessionId, captureAllowed) {
        if (captureAllowed) tracker.attach(sessionId)
        onStopOrDispose { tracker.release() }
    }
    DisposableEffect(tracker) {
        onDispose { tracker.release() }
    }
    LaunchedEffect(tracker, reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        while (true) {
            withFrameNanos { tracker.step(it, isPlaying) }
        }
    }
    return tracker
}

private val EQ_BANDS = intArrayOf(1, 5, 9)

/** Three small bars (low / mid / high) marking the current song's row. Driven by the live analysis. */
@Composable
fun EqBars(color: Color, modifier: Modifier = Modifier) {
    val tracker = LocalAudioTracker.current
    Canvas(modifier) {
        tracker.frame.longValue // redraw every frame without recomposing
        val slot = size.width / 3f
        val barW = slot * 0.5f
        for (i in 0 until 3) {
            val v = tracker.bands[EQ_BANDS[i]]
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
