package com.zmusic.app.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.graphics.drawscope.clipRect
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

/** The sculpture on stage. The app shell draws a new one at random for every song. */
val LocalGyroVariant = compositionLocalOf { GyroVariant.Gyroscope }

/**
 * The Now Playing centrepiece, standing where album art would be. It is one of four sculptures
 * ([GyroVariant]): the gyroscope below, or one of the emblems drawn by [KineticEmblem]. When
 * [variant] changes, the old sculpture leaves in its own way and the new one arrives: the
 * gyroscope swells up out of the core; an emblem fades in and plays its own entrance.
 *
 * @param halo draw the sculpture at hero size, with everything round it. Turn it off for small
 *   sizes (mini player, brand mark).
 * @param segments faces per hoop of the gyroscope. 120 keeps the outline round at hero size; 36
 *   is plenty below ~64dp.
 */
@Composable
fun KineticGyroscope(
    modifier: Modifier = Modifier,
    halo: Boolean = true,
    segments: Int = 120,
    variant: GyroVariant = LocalGyroVariant.current,
) {
    AnimatedContent(
        targetState = variant,
        modifier = modifier,
        transitionSpec = {
            // An emblem plays its own entrance (see EmblemModel), so it only needs fading in.
            val enter = if (targetState == GyroVariant.Gyroscope) {
                fadeIn(tween(420, delayMillis = 140)) + scaleIn(tween(520, delayMillis = 140), initialScale = 0.55f)
            } else {
                fadeIn(tween(200, delayMillis = 120))
            }
            // Each leaves in character: the gyroscope bursts outward, the Eclipse falls into its own
            // core, the Sunwheel rolls away, the Astrolabe switches off like a hologram.
            val exit = when (initialState) {
                GyroVariant.Gyroscope -> fadeOut(tween(260)) + scaleOut(tween(320), targetScale = 1.35f)
                GyroVariant.Eclipse -> fadeOut(tween(280)) + scaleOut(tween(300), targetScale = 0.4f)
                GyroVariant.Sunwheel -> fadeOut(tween(340)) + slideOutHorizontally(tween(380)) { it / 3 }
                GyroVariant.Astrolabe -> fadeOut(tween(420))
            }
            // Unclipped: an outgoing sculpture may grow or roll past its box on the way out.
            (enter togetherWith exit).using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.Center,
        label = "gyro-variant",
    ) { shown ->
        if (shown == GyroVariant.Gyroscope) {
            Gyroscope(Modifier.fillMaxSize(), halo, segments)
        } else {
            KineticEmblem(shown, Modifier.fillMaxSize(), fine = halo)
        }
    }
}

/**
 * [GyroVariant.Gyroscope]: an acoustic gyroscope in polished metal, staged like the big skill in
 * an anime going off.
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
 * Round the hoops, at hero size only:
 * - a summoning circle on the floor below: double ring, a band of runes, a hexagram and a dial,
 *   turning against each other, with a pillar of light rising from its centre through the core
 * - an aura: six streams of light winding up round the sculpture, and sparks rising between them
 * - on a hard kick, the impact: lightning from the core to the hoops, focus lines in from the
 *   edge of the frame, and the core star stretching into a flare
 *
 * Audio mapping:
 * - mid    -> how fast the gimbals turn, the aura climbs and the summoning circle turns
 * - bass   -> hoop radius (they breathe outward on a kick), star size, outer glow strength, the
 *             pillar and the summoning circle's brightness; a sharp kick also throws a shockwave
 *             ring out through the magic circle and sets off the impact
 * - treble -> how wide the sheen opens and how big the glints flare
 * - the twelve analysed bands -> tick lengths, mirrored left and right
 *
 * Paused, the breathing input never kicks, so the scene idles: the circle turns and the aura
 * drifts, with no lightning.
 *
 * Cost: all geometry lives in arrays allocated once in [GyroModel]; a frame allocates next to
 * nothing (a gradient per halo circle) and issues several hundred small draw ops. It redraws from
 * the tracker's frame tick, never recomposes.
 *
 * With [halo] off it draws the hoops, the core and the light behind them, and nothing else.
 */
@Composable
private fun Gyroscope(modifier: Modifier, halo: Boolean, segments: Int) {
    val tracker = LocalAudioTracker.current
    val model = remember(segments, halo) { GyroModel(segments, halo) }
    Box(
        modifier.drawWithCache {
            val halfWidth = size.width / 2f
            val halfHeight = size.height / 2f
            // With the halo the scene reaches about 1.7 units either side of the core, 1.5 above
            // it and 2 below (the summoning circle), so the core sits above the middle of the box.
            val unit = if (halo) min(halfWidth / 1.72f, size.height / 3.6f) else min(halfWidth, halfHeight) * 0.84f
            val centre = Offset(halfWidth, if (halo) halfHeight - 0.25f * unit else halfHeight)
            val ink = max(1f, 1.5.dp.toPx() * (if (halo) 1f else 0.5f))
            // Unit-radius brush, placed with a transform each frame so it is never rebuilt.
            val glow = Brush.radialGradient(
                0f to Nocturne.Amber.copy(alpha = 0.34f),
                0.40f to Nocturne.Indigo.copy(alpha = 0.16f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            // Unit-radius brushes for the pillar of light and the pool of light under the summoning circle.
            val pillar = Brush.radialGradient(
                0f to Nocturne.Amber.copy(alpha = 0.60f),
                0.30f to Nocturne.Amber.copy(alpha = 0.22f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val pool = Brush.radialGradient(
                0f to Nocturne.Indigo.copy(alpha = 0.34f),
                0.70f to Nocturne.Indigo.copy(alpha = 0.10f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val sigilStroke = Stroke(width = max(1f, 1.1.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val boltGlow = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val boltCore = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Miter)
            val star = starPath(waist = 0.085f)
            val path = Path()
            // Blended shading needs per-corner colours, which the platform only draws on a
            // hardware canvas from Android 10. Older versions get one flat colour per face.
            val blended = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            // White, so that a platform which multiplies the corner colours by the paint's
            // colour leaves them as they are.
            val meshPaint = Paint().apply { color = android.graphics.Color.WHITE }
            // One face at a time is copied here and drawn from offset 0: the platform crashes
            // on a non-zero vertex offset when no texture coordinates are passed.
            val faceXY = FloatArray(12)
            val faceColor = IntArray(6)
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
                    frameCx = halfWidth,
                    frameCy = halfHeight,
                    frameHalfWidth = halfWidth,
                    frameHalfHeight = halfHeight,
                )
                val impact = model.impact

                // Light spilling out behind the sculpture.
                val glowRadius = unit * (if (halo) 2.0f + 0.5f * bass else 1.5f + 0.4f * bass)
                withTransform({
                    translate(centre.x, centre.y)
                    scale(glowRadius, glowRadius, pivot = Offset.Zero)
                }) {
                    drawCircle(glow, radius = 1f, center = Offset.Zero, alpha = (0.30f + 0.60f * bass + 0.30f * impact).coerceAtMost(1f))
                }

                // The floor lies under everything, so it goes down first.
                if (halo) drawFloor(model, path, pool, pillar, sigilStroke, unit, bass)

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
                        i == model.coreIndex -> drawCore(star, centre, unit, bass, t, impact)

                        i >= model.tickStart -> {
                            val argb = model.fill[i]
                            if (argb ushr 24 != 0) {
                                drawLine(
                                    color = Color(argb),
                                    start = Offset(xy[o], xy[o + 1]),
                                    end = Offset(xy[o + 2], xy[o + 3]),
                                    strokeWidth = model.width[i],
                                    cap = if (i >= model.streamStart && i < model.moteStart) StrokeCap.Butt else StrokeCap.Round,
                                )
                            }
                        }

                        else -> {
                            if (blended) {
                                System.arraycopy(model.mesh, i * 12, faceXY, 0, 12)
                                System.arraycopy(model.meshColor, i * 6, faceColor, 0, 6)
                                native.drawVertices(
                                    Canvas.VertexMode.TRIANGLES,
                                    12, faceXY, 0,
                                    null, 0,
                                    faceColor, 0,
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

                if (halo) drawImpact(model, path, boltGlow, boltCore)
            }
        },
    )
}

/**
 * The summoning circle on the floor: a pool of light, the circle itself as one stroked path (so
 * no joint shows anywhere on it), and the pillar of light rising from its centre.
 */
private fun DrawScope.drawFloor(model: GyroModel, path: Path, pool: Brush, pillar: Brush, stroke: Stroke, unit: Float, bass: Float) {
    val x = model.floorXY[0]
    val y = model.floorXY[1]
    val impact = model.impact
    val reach = unit * 1.45f * model.floorScale
    withTransform({
        translate(x, y)
        scale(reach, reach * model.floorSquash, pivot = Offset.Zero)
    }) {
        drawCircle(pool, radius = 1f, center = Offset.Zero, alpha = model.sigilAlpha)
    }

    path.reset()
    val points = model.sigilXY
    for (k in 0 until model.sigilPoints) {
        if (model.sigilStart[k]) path.moveTo(points[k * 2], points[k * 2 + 1]) else path.lineTo(points[k * 2], points[k * 2 + 1])
    }
    drawPath(path, SigilLine, alpha = model.sigilAlpha, style = stroke)

    // Only the part above the floor: the pillar stands on the circle.
    clipRect(top = -size.height, bottom = y) {
        withTransform({
            translate(x, y)
            scale(unit * 0.36f, unit * 3.0f, pivot = Offset.Zero)
        }) {
            drawCircle(pillar, radius = 1f, center = Offset.Zero, alpha = (0.14f + 0.30f * bass + 0.50f * impact).coerceAtMost(1f))
        }
    }
}

/** What a hard kick sets off on top of everything: lightning, then focus lines at the frame's edge. */
private fun DrawScope.drawImpact(model: GyroModel, path: Path, glow: Stroke, core: Stroke) {
    if (model.boltAlpha > 0f) {
        val points = model.boltXY
        path.reset()
        for (b in 0 until model.bolts) {
            val at = b * model.boltPoints * 2
            path.moveTo(points[at], points[at + 1])
            for (k in 1 until model.boltPoints) path.lineTo(points[at + k * 2], points[at + k * 2 + 1])
        }
        drawPath(path, BoltGlow, alpha = 0.55f * model.boltAlpha, style = glow)
        drawPath(path, Color.White, alpha = model.boltAlpha, style = core)
    }
    drawFocusLines(model.focusXY, model.focusLines, model.focusAlpha, path)
}

/** Focus lines at the frame's edge: [lines] thin wedges, one triangle (6 floats) each in [points]. */
internal fun DrawScope.drawFocusLines(points: FloatArray, lines: Int, alpha: Float, path: Path) {
    if (alpha <= 0f) return
    path.reset()
    for (i in 0 until lines) {
        val o = i * 6
        path.moveTo(points[o], points[o + 1])
        path.lineTo(points[o + 2], points[o + 3])
        path.lineTo(points[o + 4], points[o + 5])
        path.close()
    }
    drawPath(path, Color.White, alpha = alpha)
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
 * four-point star with a smaller one set diagonally across it. On an impact the stars swell and
 * a long flat flare cuts across them.
 */
private fun DrawScope.drawCore(star: Path, centre: Offset, unit: Float, bass: Float, t: Float, impact: Float) {
    val pulse = 0.5f + 0.5f * bass
    val swell = 1f + 0.7f * impact
    drawCircle(Nocturne.Amber, radius = unit * (0.17f + 0.13f * bass) * swell, center = centre, alpha = 0.16f + 0.10f * bass)
    drawCircle(Nocturne.Amber, radius = unit * (0.105f + 0.08f * bass), center = centre, alpha = 0.50f)
    val spin = t * 14f
    drawStar(star, centre, unit * (0.16f + 0.14f * bass) * swell, spin + 45f, CoreWarm)
    drawStar(star, centre, unit * (0.32f + 0.26f * bass) * swell, spin, Nocturne.Alabaster)
    if (impact > 0.01f) {
        withTransform({
            translate(centre.x, centre.y)
            scale(unit * (0.6f + 1.5f * impact), unit * 0.30f * impact, pivot = Offset.Zero)
        }) {
            drawPath(star, Color.White, alpha = impact)
        }
    }
    drawCircle(Color.White, radius = unit * 0.055f * (0.8f + 0.4f * pulse), center = centre)
}

internal fun DrawScope.drawStar(star: Path, at: Offset, radius: Float, degrees: Float, color: Color) {
    withTransform({
        translate(at.x, at.y)
        rotate(degrees, pivot = Offset.Zero)
        scale(radius, radius, pivot = Offset.Zero)
    }) {
        drawPath(star, color)
    }
}

/** A unit four-point sparkle: long arms on the axes, pinched to [waist] on the diagonals. */
internal fun starPath(waist: Float): Path = Path().apply {
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
private val SigilLine = Color(0xFFC9CCFF)
private val BoltGlow = Color(0xFF8C8FFF)
