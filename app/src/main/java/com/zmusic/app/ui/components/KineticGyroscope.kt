package com.zmusic.app.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.theme.Nocturne
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Now Playing centrepiece, standing where album art would be: an acoustic gyroscope in
 * polished metal.
 *
 * Three nested hoops, each a real cylindrical band in 3D, gimballed inside one another (outer
 * turns about X, the middle about Y inside the outer's frame, the inner about X inside the
 * middle's), floating over a tilted magic circle of spectrum ticks, with a four-point star
 * burning at the centre. The geometry is true 3D (every vertex is rotated by 3x3 matrices,
 * projected with a perspective divide and painted back to front after a depth sort). Each hoop
 * is shaded as one continuous piece: light is worked out where faces meet and blended across each
 * face, so tone and sheen run all the way round without a step; a bevel line runs along every
 * rim, and a star glint sits on each highlight. The magic circle's rings are single strokes.
 *
 * Audio mapping:
 * - mid    -> how fast the gimbals turn
 * - bass   -> hoop radius (they breathe outward on a kick), star size, outer glow strength;
 *             a sharp kick also throws a shockwave ring out through the magic circle
 * - treble -> how wide the sheen opens and how big the glints flare
 * - the twelve analysed bands -> tick lengths, mirrored left and right
 *
 * Cost: all geometry lives in arrays allocated once in [GyroModel]; a frame allocates next to
 * nothing (a gradient per halo circle) and issues several hundred small draw ops. It redraws from
 * the tracker's frame tick, never recomposes.
 *
 * @param halo draw the magic circle, glints and shockwave. Turn it off for small sizes (mini
 *   player, brand mark).
 * @param segments faces per hoop. 120 keeps the outline round at hero size; 36 is plenty below ~64dp.
 */
@Composable
fun KineticGyroscope(
    modifier: Modifier = Modifier,
    halo: Boolean = true,
    segments: Int = 120,
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
            // Blended shading needs per-corner colours, which the platform only draws on a
            // hardware canvas from Android 10. Older versions get one flat colour per face.
            val blended = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val meshPaint = Paint()
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

                // The far halves of the halo's circles pass behind the hoops, the near halves in front.
                if (halo) {
                    drawLoop(model, 0, near = false, path, GuideCircle)
                    drawLoop(model, 1, near = false, path, Shockwave)
                }

                val native = drawContext.canvas.nativeCanvas
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
                                    cap = StrokeCap.Round,
                                )
                            }
                        }

                        else -> {
                            if (blended) {
                                native.drawVertices(
                                    Canvas.VertexMode.TRIANGLES,
                                    12, model.mesh, i * 12,
                                    null, 0,
                                    model.meshColor, i * 6,
                                    null, 0, 0,
                                    meshPaint,
                                )
                            } else {
                                path.reset()
                                path.moveTo(xy[o], xy[o + 1])
                                path.lineTo(xy[o + 2], xy[o + 3])
                                path.lineTo(xy[o + 4], xy[o + 5])
                                path.lineTo(xy[o + 6], xy[o + 7])
                                path.close()
                                drawPath(path, Color(model.fill[i]))
                            }
                            // Bevel along both rims: finishes the edge, and keeps a hoop visible
                            // when it turns exactly edge-on to the viewer.
                            val line = Color(model.edge[i])
                            drawLine(line, Offset(xy[o], xy[o + 1]), Offset(xy[o + 2], xy[o + 3]), ink, StrokeCap.Round)
                            drawLine(line, Offset(xy[o + 6], xy[o + 7]), Offset(xy[o + 4], xy[o + 5]), ink, StrokeCap.Round)
                        }
                    }
                }

                if (halo) {
                    drawLoop(model, 0, near = true, path, GuideCircle)
                    drawLoop(model, 1, near = true, path, Shockwave)
                }

                // Glints ride on top of everything.
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
 * Half of one of the halo's circles as a single stroke, so there are no joints to show. Its
 * opacity fades with distance through one gradient shared by both halves.
 */
private fun DrawScope.drawLoop(model: GyroModel, index: Int, near: Boolean, path: Path, color: Color) {
    val nearAlpha = model.loopNearAlpha[index]
    val farAlpha = model.loopFarAlpha[index]
    val nearY = model.loopNearY[index]
    val farY = model.loopFarY[index]
    if ((nearAlpha <= 0f && farAlpha <= 0f) || abs(nearY - farY) < 1f) return
    val points = model.loopXY
    val half = (model.loopPoints - 1) / 2
    val first = index * model.loopStride + (if (near) 0 else half) * 2
    path.reset()
    path.moveTo(points[first], points[first + 1])
    for (k in 1..half) path.lineTo(points[first + k * 2], points[first + k * 2 + 1])
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            0f to color.copy(alpha = farAlpha),
            1f to color.copy(alpha = nearAlpha),
            startY = farY,
            endY = nearY,
        ),
        style = Stroke(width = model.loopWidth[index], join = StrokeJoin.Round),
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
private val GuideCircle = Color(0xFFCCD1FA)
private val Shockwave = Color(0xFFFFE6BD)
