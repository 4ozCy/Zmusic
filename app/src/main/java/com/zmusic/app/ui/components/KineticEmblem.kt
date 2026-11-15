package com.zmusic.app.ui.components

import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The emblem sculptures: [GyroVariant.Eclipse], [GyroVariant.Sunwheel] and [GyroVariant.Astrolabe].
 * Each one is a stack of flat pieces turning in 3D, laid out and animated by [EmblemModel], which
 * also says how each one moves and answers a kick. This paints what the model worked out, back
 * to front:
 *
 * 1. light behind the emblem: the Eclipse's corona and lens streak, the Sunwheel's sun and rays,
 *    the Astrolabe's nebula and comet
 * 2. halos: every glowing outline of one colour goes into a single path, stroked four times from
 *    wide and faint to narrow and strong, so overlapping outlines never bead
 * 3. the model's items, far to near: lit facets, outlines, dots. A line the Astrolabe is still
 *    drawing in is cut short where its pen has got to.
 * 4. what sits on top: the Eclipse's corona flash and crescent slashes, the Sunwheel's white-hot
 *    core and speed dashes, the Astrolabe's pin, ripples and constellation
 *
 * Cost: the model allocates nothing per frame, and the paths and paints here are reused. A frame
 * issues a few hundred small draw ops. It redraws from the tracker's frame tick, never recomposes.
 *
 * @param fine draw the full emblem with its halos and effects. False gives the cut-down one for
 *   small sizes.
 */
@Composable
internal fun KineticEmblem(variant: GyroVariant, modifier: Modifier = Modifier, fine: Boolean = true) {
    val tracker = LocalAudioTracker.current
    // With animations off the clock never runs, so an entrance would never finish: skip it.
    val still = rememberReduceMotion()
    val model = remember(variant, fine, still) { EmblemModel(variant, fine, intro = !still) }
    Box(
        modifier.drawWithCache {
            val halfWidth = size.width / 2f
            val halfHeight = size.height / 2f
            val half = min(halfWidth, halfHeight)
            val unit = half / model.reach
            val centre = Offset(halfWidth, halfHeight)
            val lineUnit = 1.dp.toPx() * (if (fine) 1f else 0.5f)
            // Unit-radius brushes, placed with a transform each frame so they are never rebuilt.
            val inner = Color(OPAQUE or model.backdropInner)
            val outer = Color(OPAQUE or model.backdropOuter)
            val backdrop = Brush.radialGradient(
                0f to inner.copy(alpha = 0.33f),
                0.45f to outer.copy(alpha = 0.13f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            // The Eclipse's light is a corona: dark in the middle, brightest in a ring behind the silver.
            val corona = Brush.radialGradient(
                0f to Color.Transparent,
                0.42f to Color.Transparent,
                0.62f to inner.copy(alpha = 0.34f),
                0.78f to outer.copy(alpha = 0.12f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val violet = fading(NebulaViolet, 0.30f)
            val blue = fading(NebulaBlue, 0.26f)
            val ember = Brush.radialGradient(
                0f to OrbHot.copy(alpha = 0.95f),
                0.35f to OrbGlow.copy(alpha = 0.45f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val sun = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.90f),
                0.30f to SunGlow.copy(alpha = 0.50f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val fill = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            val stroke = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val shape = android.graphics.Path()
            val streak = Path().apply {
                moveTo(-1f, 0f)
                lineTo(0f, -1f)
                lineTo(1f, 0f)
                lineTo(0f, 1f)
                close()
            }
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
                    lineUnit = lineUnit,
                )
                val impact = model.impact
                val native = drawContext.canvas.nativeCanvas

                // 1. Light behind the emblem.
                val light = (0.45f + 0.55f * bass + 0.30f * impact).coerceAtMost(1f)
                if (model.variant == GyroVariant.Eclipse) {
                    disc(corona, centre, unit * (1.62f + 0.12f * bass + 0.10f * impact), light)
                } else {
                    disc(backdrop, centre, if (fine) unit * (1.9f + 0.4f * bass) else half * 1.25f, light)
                }
                when (model.variant) {
                    GyroVariant.Eclipse -> if (fine) {
                        // The lens streak belongs to the camera, so it stays level however the emblem turns.
                        val length = unit * (1.42f + 0.15f * bass + 0.50f * impact)
                        lozenge(streak, centre, length, unit * (0.045f + 0.060f * impact), StreakGlow, 0.22f + 0.30f * impact)
                        lozenge(streak, centre, length, unit * (0.010f + 0.016f * impact), StreakCore, 0.55f + 0.40f * impact)
                        lozenge(streak, centre, unit * 0.022f, length * 0.5f, StreakGlow, 0.16f + 0.30f * impact)
                    }

                    GyroVariant.Sunwheel -> if (model.rayAlpha > 0f) {
                        shape.rewind()
                        val rays = model.rayXY
                        for (k in 0 until model.rays) {
                            val o = k * 6
                            shape.moveTo(rays[o], rays[o + 1])
                            shape.lineTo(rays[o + 2], rays[o + 3])
                            shape.lineTo(rays[o + 4], rays[o + 5])
                            shape.close()
                        }
                        fill.color = tint(RAY, model.rayAlpha)
                        native.drawPath(shape, fill)
                    }

                    else -> if (fine) {
                        disc(violet, Offset(centre.x - 0.45f * unit, centre.y - 0.30f * unit), unit * 1.2f, light)
                        disc(blue, Offset(centre.x + 0.50f * unit, centre.y + 0.40f * unit), unit * 1.1f, light)
                        if (model.cometAlpha > 0f) {
                            // A tail that thins and fades in steps, and a bright head.
                            val hx = model.cometX
                            val hy = model.cometY
                            for (s in 0 until COMET_STEPS) {
                                val a0 = s / COMET_STEPS.toFloat()
                                val a1 = (s + 1) / COMET_STEPS.toFloat()
                                stroke.color = tint(COMET, model.cometAlpha * (1f - a0))
                                stroke.strokeWidth = lineUnit * (0.5f + 3f * (1f - a0))
                                native.drawLine(
                                    hx + model.cometTailX * a0, hy + model.cometTailY * a0,
                                    hx + model.cometTailX * a1, hy + model.cometTailY * a1,
                                    stroke,
                                )
                            }
                            fill.color = tint(WHITE, model.cometAlpha)
                            native.drawCircle(hx, hy, lineUnit * 2.4f, fill)
                        }
                    }
                }

                // 2. Halos.
                if (fine) {
                    for (c in 0 until model.glowCount) {
                        shape.rewind()
                        for (i in 0 until model.count) {
                            if (model.glow[i] == c + 1 && model.argb[i] ushr 24 != 0) trace(model, i, shape)
                        }
                        val argb = model.glowArgb[c]
                        val alpha = argb ushr 24
                        for (pass in GLOW_WIDTH.indices) {
                            stroke.color = (argb and 0x00FFFFFF) or (min(255, (alpha * GLOW_ALPHA[pass]).toInt()) shl 24)
                            stroke.strokeWidth = model.glowWidth[c] * GLOW_WIDTH[pass]
                            native.drawPath(shape, stroke)
                        }
                    }
                }

                // 3. The emblem itself, far to near.
                val x = model.sx
                val y = model.sy
                val idx = model.idx
                for (n in 0 until model.count) {
                    val i = model.order[n]
                    val argb = model.argb[i]
                    if (argb ushr 24 == 0) continue
                    val f = model.first[i]
                    when (model.kind[i]) {
                        Emblem.DOT -> {
                            fill.color = argb
                            native.drawCircle(x[idx[f]], y[idx[f]], model.width[i], fill)
                        }

                        Emblem.POLY -> {
                            shape.rewind()
                            trace(model, i, shape)
                            fill.color = argb
                            native.drawPath(shape, fill)
                        }

                        else -> {
                            stroke.color = argb
                            stroke.strokeWidth = model.width[i]
                            if (model.size[i] == 2 && model.reveal[i] >= 1f) {
                                native.drawLine(x[idx[f]], y[idx[f]], x[idx[f + 1]], y[idx[f + 1]], stroke)
                            } else {
                                shape.rewind()
                                trace(model, i, shape)
                                native.drawPath(shape, stroke)
                            }
                        }
                    }
                }

                // 4. What sits on top.
                val core = Offset(model.coreX, model.coreY)
                val coreUnit = unit * model.coreScale
                when (model.variant) {
                    GyroVariant.Eclipse -> {
                        if (impact > 0.01f) {
                            // The corona flashes round the dark core.
                            stroke.color = tint(CORONA, impact)
                            stroke.strokeWidth = lineUnit * (1.5f + 3f * impact)
                            native.drawCircle(core.x, core.y, coreUnit * 0.29f * (1f + 0.25f * (1f - impact)), stroke)
                        }
                        if (model.slashAlpha > 0f) {
                            // Crescent slashes: a red glow round a pale blade.
                            shape.rewind()
                            val points = model.slashXY
                            val per = model.slashPoints
                            for (k in 0 until model.slashes) {
                                val base = k * per * 2
                                shape.moveTo(points[base], points[base + 1])
                                for (j in 1 until per) shape.lineTo(points[base + j * 2], points[base + j * 2 + 1])
                                shape.close()
                            }
                            stroke.color = tint(SLASH_GLOW, 0.45f * model.slashAlpha)
                            stroke.strokeWidth = lineUnit * 10f
                            native.drawPath(shape, stroke)
                            fill.color = tint(SLASH_CORE, model.slashAlpha)
                            native.drawPath(shape, fill)
                        }
                    }

                    GyroVariant.Sunwheel -> {
                        // A white-hot core under the spinning star.
                        disc(sun, core, coreUnit * (0.20f + 0.10f * bass + 0.25f * impact), 1f)
                        fill.color = tint(WHITE, 1f)
                        native.drawCircle(core.x, core.y, coreUnit * (0.035f + 0.02f * bass), fill)
                        if (fine) {
                            // Speed dashes either side: a kick throws them outward.
                            val near = unit * (1.66f + 0.06f * bass + 0.30f * impact)
                            val far = near + unit * (0.13f + 0.10f * bass)
                            val alpha = 0.36f + 0.54f * impact
                            for (side in -1..1 step 2) {
                                val dx = side * DASH_X
                                val dy = side * DASH_Y
                                stroke.color = tint(DASH_GLOW, alpha * 0.35f)
                                stroke.strokeWidth = lineUnit * 7f
                                native.drawLine(centre.x + dx * near, centre.y + dy * near, centre.x + dx * far, centre.y + dy * far, stroke)
                                stroke.color = tint(DASH_CORE, alpha)
                                stroke.strokeWidth = lineUnit * 2.4f
                                native.drawLine(centre.x + dx * near, centre.y + dy * near, centre.x + dx * far, centre.y + dy * far, stroke)
                            }
                        }
                    }

                    else -> {
                        // The pin at the top of the axle: a warm bloom and a white point under the crosshair.
                        val swell = 1f + 0.3f * impact
                        disc(ember, core, coreUnit * (0.34f + 0.16f * bass) * swell, 1f)
                        fill.color = tint(WHITE, 1f)
                        native.drawCircle(core.x, core.y, coreUnit * (0.045f + 0.03f * bass) * swell, fill)

                        for (k in 0 until model.ripples) {
                            val alpha = model.rippleAlpha[k]
                            if (alpha <= 0f) continue
                            shape.rewind()
                            val points = model.rippleXY
                            val base = k * model.ripplePoints * 2
                            shape.moveTo(points[base], points[base + 1])
                            for (j in 1 until model.ripplePoints) shape.lineTo(points[base + j * 2], points[base + j * 2 + 1])
                            stroke.color = tint(RIPPLE, alpha)
                            stroke.strokeWidth = lineUnit * 1.4f
                            native.drawPath(shape, stroke)
                        }

                        if (model.constellationAlpha > 0f) {
                            val stars = model.constellation
                            shape.rewind()
                            shape.moveTo(x[stars[0]], y[stars[0]])
                            for (j in 1 until stars.size) shape.lineTo(x[stars[j]], y[stars[j]])
                            stroke.color = tint(CONSTELLATION, model.constellationAlpha)
                            stroke.strokeWidth = lineUnit * 0.9f
                            native.drawPath(shape, stroke)
                            fill.color = tint(WHITE, model.constellationAlpha)
                            for (j in stars.indices) native.drawCircle(x[stars[j]], y[stars[j]], lineUnit * 2.2f, fill)
                        }
                    }
                }
            }
        },
    )
}

/**
 * Adds item [i] of [model] to [path]: a polygon closed, a line only as far as its reveal has
 * got. A dot adds nothing that strokes.
 */
private fun trace(model: EmblemModel, i: Int, path: android.graphics.Path) {
    val x = model.sx
    val y = model.sy
    val idx = model.idx
    val f = model.first[i]
    val n = model.size[i]
    path.moveTo(x[idx[f]], y[idx[f]])
    val reveal = model.reveal[i]
    if (model.kind[i] == Emblem.LINE && reveal < 1f) {
        val along = reveal * (n - 1)
        val whole = along.toInt()
        for (k in 1..whole) path.lineTo(x[idx[f + k]], y[idx[f + k]])
        val part = along - whole
        if (whole < n - 1 && part > 0f) {
            val a = idx[f + whole]
            val b = idx[f + whole + 1]
            path.lineTo(x[a] + (x[b] - x[a]) * part, y[a] + (y[b] - y[a]) * part)
        }
        return
    }
    for (k in 1 until n) path.lineTo(x[idx[f + k]], y[idx[f + k]])
    if (model.kind[i] == Emblem.POLY) path.close()
}

/** [rgb] with [alpha] (0..1) as one ARGB int for a Paint. */
private fun tint(rgb: Int, alpha: Float): Int = (rgb and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)

/** A unit-radius glow of one colour that fades to nothing at its edge. */
private fun fading(color: Color, alpha: Float): Brush = Brush.radialGradient(
    0f to color.copy(alpha = alpha),
    1f to color.copy(alpha = 0f),
    center = Offset.Zero,
    radius = 1f,
)

private fun DrawScope.disc(brush: Brush, at: Offset, radius: Float, alpha: Float) {
    withTransform({
        translate(at.x, at.y)
        scale(radius, radius, pivot = Offset.Zero)
    }) {
        drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = alpha.coerceIn(0f, 1f))
    }
}

/** The unit diamond [shape], stretched to [halfWidth] by [halfHeight]. */
private fun DrawScope.lozenge(shape: Path, at: Offset, halfWidth: Float, halfHeight: Float, color: Color, alpha: Float) {
    withTransform({
        translate(at.x, at.y)
        scale(halfWidth, halfHeight, pivot = Offset.Zero)
    }) {
        drawPath(shape, color, alpha = alpha.coerceIn(0f, 1f))
    }
}

private const val OPAQUE = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFF

/** Halo passes, widest first: share of the class's width, and how much of its opacity each lays down. */
private val GLOW_WIDTH = floatArrayOf(1f, 0.70f, 0.45f, 0.25f)
private val GLOW_ALPHA = floatArrayOf(0.25f, 0.40f, 0.60f, 0.80f)

// Eclipse.
private val StreakGlow = Color(0xFFFF1A38)
private val StreakCore = Color(0xFFFF8085)
private const val CORONA = 0xD9EDFF
private const val SLASH_GLOW = 0xFF1A38
private const val SLASH_CORE = 0xFFE0E4

// Sunwheel.
private val SunGlow = Color(0xFFFFB020)
private const val RAY = 0xFFC233

/** The speed dashes: the direction they lie along (unit vector), and their colours. */
private val DASH_X = cos(-0.30f)
private val DASH_Y = sin(-0.30f)
private const val DASH_GLOW = 0xFFB314
private const val DASH_CORE = 0xFFE66B

// Astrolabe.
private val NebulaViolet = Color(0xFF9E5CFF)
private val NebulaBlue = Color(0xFF4080FF)
private val OrbHot = Color(0xFFFFF2D9)
private val OrbGlow = Color(0xFFFFA840)
private const val RIPPLE = 0xC4D6FF
private const val CONSTELLATION = 0xFFDF9E
private const val COMET = 0xE0EAFF
private const val COMET_STEPS = 8
