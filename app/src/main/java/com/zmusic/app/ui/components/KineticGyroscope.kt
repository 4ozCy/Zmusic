package com.zmusic.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.theme.Nocturne
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Now Playing centrepiece, standing where album art would be: an acoustic gyroscope, drawn
 * like an anime cel.
 *
 * Three nested hoops, each a real cylindrical band in 3D, gimballed inside one another (outer
 * turns about X, the middle about Y inside the outer's frame, the inner about X inside the
 * middle's), floating over a tilted magic circle of spectrum ticks, with a four-point star
 * burning at the centre. The geometry is true 3D (every vertex is rotated by 3x3 matrices,
 * projected with a perspective divide and painted back to front after a depth sort) but the
 * shading is toon: flat shadow / base / light tones with hard edges, a hard white specular
 * streak, an ink line along every rim, and a star glint sitting on each highlight.
 *
 * Audio mapping:
 * - mid    -> how fast the gimbals turn
 * - bass   -> hoop radius (they breathe outward on a kick), star size, outer glow strength;
 *             a sharp kick also throws a shockwave ring out through the magic circle
 * - treble -> how wide the specular streaks open and how big the glints flare
 * - the twelve analysed bands -> tick lengths, mirrored left and right
 *
 * Cost: all geometry lives in arrays allocated once in [GyroModel]; a frame allocates nothing and
 * issues several hundred small draw ops. It redraws from the tracker's frame tick, never recomposes.
 *
 * @param halo draw the magic circle, glints and shockwave. Turn it off for small sizes (mini
 *   player, brand mark).
 * @param segments faces per hoop. 72 keeps the tone edges fine at hero size; 24 is plenty below ~64dp.
 */
@Composable
fun KineticGyroscope(
    modifier: Modifier = Modifier,
    halo: Boolean = true,
    segments: Int = 72,
) {
    val tracker = LocalAudioTracker.current
    val model = remember(segments, halo) { GyroModel(segments, halo) }
    Box(
        modifier.drawWithCache {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val unit = min(size.width, size.height) / 2f * (if (halo) 0.56f else 0.84f)
            val ink = max(1f, 1.5.dp.toPx() * (if (halo) 1f else 0.5f))
            // Unit-radius brush, placed with a transform each frame so it is never rebuilt.
            val glow = Brush.radialGradient(
                0f to Nocturne.Amber.copy(alpha = 0.34f),
                0.40f to Nocturne.Indigo.copy(alpha = 0.16f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val star = starPath(waist = 0.085f)
            val path = Path()
            onDrawBehind {
                tracker.frame.longValue // redraw every frame without recomposing
                val t = tracker.seconds
                val bass = tracker.bass.value
                model.update(
                    t = t,
                    bass = bass,
                    mid = tracker.mid.value,
                    treble = tracker.treble.value,
                    bands = tracker.bands,
                    cx = centre.x,
                    cy = centre.y,
                    unit = unit,
                    tickWidth = 2.dp.toPx(),
                )

                // Light spilling out behind the sculpture.
                val glowRadius = unit * (if (halo) 2.0f + 0.5f * bass else 1.5f + 0.4f * bass)
                withTransform({
                    translate(centre.x, centre.y)
                    scale(glowRadius, glowRadius, pivot = Offset.Zero)
                }) {
                    drawCircle(glow, radius = 1f, center = Offset.Zero, alpha = 0.30f + 0.60f * bass)
                }

                val xy = model.xy
                for (n in 0 until model.count) {
                    val i = model.order[n]
                    val o = i * 8
                    when {
                        i == model.coreIndex -> drawCore(star, centre, unit, bass, t)

                        i >= model.tickStart -> {
                            val argb = model.fill[i]
                            if (argb ushr 24 != 0) {
                                drawLine(
                                    color = Color(argb),
                                    start = Offset(xy[o], xy[o + 1]),
                                    end = Offset(xy[o + 2], xy[o + 3]),
                                    strokeWidth = model.width[i],
                                    // Round ends on the free-standing ticks; butt on chained arcs,
                                    // where translucent round caps would stack into beads.
                                    cap = if (i < model.arcStart) StrokeCap.Round else StrokeCap.Butt,
                                )
                            }
                        }

                        else -> {
                            path.reset()
                            path.moveTo(xy[o], xy[o + 1])
                            path.lineTo(xy[o + 2], xy[o + 3])
                            path.lineTo(xy[o + 4], xy[o + 5])
                            path.lineTo(xy[o + 6], xy[o + 7])
                            path.close()
                            drawPath(path, Color(model.fill[i]))
                            // Ink along both rims: the cel outline, and what keeps a hoop visible
                            // when it turns exactly edge-on to the viewer.
                            val line = Color(model.edge[i])
                            drawLine(line, Offset(xy[o], xy[o + 1]), Offset(xy[o + 2], xy[o + 3]), ink, StrokeCap.Round)
                            drawLine(line, Offset(xy[o + 6], xy[o + 7]), Offset(xy[o + 4], xy[o + 5]), ink, StrokeCap.Round)
                        }
                    }
                }

                // Glints ride on top of everything, like a highlight painted on the cel last.
                for (g in 0 until model.glintSize.size) {
                    val s = model.glintSize[g]
                    if (s > 0.5f) {
                        val at = Offset(model.glintXY[g * 2], model.glintXY[g * 2 + 1])
                        drawCircle(GlintHalo, radius = s * 0.34f, center = at)
                        drawStar(star, at, s, 0f, Color.White)
                    }
                }
            }
        },
    )
}

/**
 * The light at the centre: hard-edged discs instead of a soft bloom, under a slowly turning
 * four-point star with a smaller one set diagonally across it.
 */
private fun DrawScope.drawCore(star: Path, centre: Offset, unit: Float, bass: Float, t: Float) {
    val pulse = 0.5f + 0.5f * bass
    drawCircle(Nocturne.Amber, radius = unit * (0.17f + 0.13f * bass), center = centre, alpha = 0.16f + 0.10f * bass)
    drawCircle(Nocturne.Amber, radius = unit * (0.105f + 0.08f * bass), center = centre, alpha = 0.50f)
    val spin = t * 14f
    drawStar(star, centre, unit * (0.16f + 0.14f * bass), spin + 45f, CoreWarm)
    drawStar(star, centre, unit * (0.32f + 0.26f * bass), spin, Nocturne.Alabaster)
    drawCircle(Color.White, radius = unit * 0.055f * (0.8f + 0.4f * pulse), center = centre)
}

private fun DrawScope.drawStar(star: Path, at: Offset, radius: Float, degrees: Float, color: Color) {
    withTransform({
        translate(at.x, at.y)
        rotate(degrees, pivot = Offset.Zero)
        scale(radius, radius, pivot = Offset.Zero)
    }) {
        drawPath(star, color)
    }
}

/** A unit four-point sparkle: long arms on the axes, pinched to [waist] on the diagonals. */
private fun starPath(waist: Float): Path = Path().apply {
    for (k in 0 until 8) {
        val a = k * PI / 4.0
        val r = if (k % 2 == 0) 1f else waist
        val x = (cos(a) * r).toFloat()
        val y = (sin(a) * r).toFloat()
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val CoreWarm = Color(0xFFFFD9A0)
private val GlintHalo = Color(0x55FFE9C4)
