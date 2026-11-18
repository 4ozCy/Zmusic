package com.zmusic.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.zmusic.app.playback.AudioFrequencyTracker
import com.zmusic.app.ui.theme.Nocturne

/**
 * The astronaut drifting in front of the black hole: a 3D model in a white EVA suit (see
 * assets/astronaut.mesh and scripts/astronaut_mesh.py), posed by [AstronautPose], lit by the disc
 * and drawn with OpenGL ES by [AstronautRenderer] on its own thread.
 *
 * Each frame this works out the pose and the camera on the main thread (cheap, and nothing
 * allocates), hands them to the renderer, and draws the newest picture it has finished. That
 * picture is a frame or so behind, which nobody can see at this speed. Like the rest of the
 * backdrop it redraws by reading [AudioFrequencyTracker.frame] in the draw lambda, so it never
 * recomposes and stops with the tracker when the app is not visible.
 */
@Composable
internal fun FloatingAstronaut(tracker: AudioFrequencyTracker, modifier: Modifier) {
    val assets = LocalContext.current.applicationContext.assets
    val renderer = remember { AstronautRenderer(assets) }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    val pose = remember { AstronautPose() }
    val frame = remember { AstronautFrame() }
    Box(
        modifier.drawBehind {
            tracker.frame.longValue // redraw every frame without recomposing
            val mesh = renderer.mesh
            if (mesh != null) {
                pose.update(tracker.seconds, size.width, size.height)
                frame.set(pose, mesh, size.width, tracker.bass.value, Palette)
                renderer.submit(frame)
            }
            val picture = renderer.latest() ?: return@drawBehind
            drawImage(picture.image, Offset(picture.left.toFloat(), picture.top.toFloat()))
        },
    )
}

/** The theme's amber, indigo and slate in linear light (squared, near enough), for the figure's lights. */
private val Palette: FloatArray = run {
    fun linear(c: Color) = floatArrayOf(c.red * c.red, c.green * c.green, c.blue * c.blue)
    linear(Nocturne.Amber) + linear(Nocturne.Indigo) + linear(Nocturne.Slate)
}
