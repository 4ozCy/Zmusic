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
 * Each one is a stack of flat pieces turning in 3D, laid out and animated by [EmblemModel]; this
 * paints what the model worked out, back to front:
 *
 * 1. light behind the emblem (and the Eclipse's lens streak, the Astrolabe's nebula)
 * 2. halos: every glowing outline of one colour goes into a single path, stroked four times
 *    from wide and faint to narrow and strong, so overlapping outlines never bead
 * 3. the model's items, far to near: lit facets, outlines, dots
 * 4. what sits on top: the Eclipse's corona flash, the Sunwheel's glint and speed dashes, the
 *    Astrolabe's starburst core, and focus lines on an impact
 *
 * Cost: the model allocates nothing per frame, and the paths and paints here are reused. A frame
 * issues a few hundred small draw ops. It redraws from the tracker's frame tick, never recomposes.
 *
 * @param fine draw the full emblem with its halos and extras. False gives the cut-down one for
 *   small sizes.
 */
@Composable
internal fun KineticEmblem(variant: GyroVariant, modifier: Modifier = Modifier, fine: Boolean = true) {
    val tracker = LocalAudioTracker.current
    val model = remember(variant, fine) { EmblemModel(variant, fine) }
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
            val violet = fading(NebulaViolet, 0.30f)
            val blue = fading(NebulaBlue, 0.26f)
            val ember = Brush.radialGradient(
                0f to BurstHot.copy(alpha = 0.95f),
                0.35f to BurstGlow.copy(alpha = 0.45f),
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
            val star = starPath(waist = 0.085f)
            val streak = Path().apply {
                moveTo(-1f, 0f)
                lineTo(0f, -1f)
                lineTo(1f, 0f)
                lineTo(0f, 1f)
                close()
            }
            val focus = Path()
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
                    frameCx = halfWidth,
                    frameCy = halfHeight,
                    frameHalfWidth = halfWidth,
                    frameHalfHeight = halfHeight,
                )
                val impact = model.impact

                // 1. Light behind the emblem.
                val light = (0.45f + 0.55f * bass + 0.30f * impact).coerceAtMost(1f)
                disc(backdrop, centre, if (fine) unit * (1.9f + 0.4f * bass) else half * 1.25f, light)
                if (fine && model.variant == GyroVariant.Astrolabe) {
                    disc(violet, Offset(centre.x - 0.45f * unit, centre.y - 0.30f * unit), unit * 1.2f, light)
                    disc(blue, Offset(centre.x + 0.50f * unit, centre.y + 0.40f * unit), unit * 1.1f, light)
                }
                if (fine && model.variant == GyroVariant.Eclipse) {
                    // The lens streak: it belongs to the camera, so it stays level however the emblem turns.
                    val length = unit * (1.42f + 0.15f * bass + 0.50f * impact)
                    lozenge(streak, centre, length, unit * (0.045f + 0.060f * impact), StreakGlow, 0.22f + 0.30f * impact)
                    lozenge(streak, centre, length, unit * (0.010f + 0.016f * impact), StreakCore, 0.55f + 0.40f * impact)
                    lozenge(streak, centre, unit * 0.022f, length * 0.5f, StreakGlow, 0.16f + 0.30f * impact)
                }

                val native = drawContext.canvas.nativeCanvas
                val x = model.sx
                val y = model.sy
                val idx = model.idx

                // 2. Halos.
                if (fine) {
                    for (c in 0 until model.glowCount) {
                        shape.rewind()
                        for (i in 0 until model.count) {
                            if (model.glow[i] != c + 1 || model.argb[i] ushr 24 == 0) continue
                            val f = model.first[i]
                            shape.moveTo(x[idx[f]], y[idx[f]])
                            for (k in 1 until model.size[i]) shape.lineTo(x[idx[f + k]], y[idx[f + k]])
                            if (model.kind[i] == Emblem.POLY) shape.close()
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
                for (n in 0 until model.count) {
                    val i = model.order[n]
                    val argb = model.argb[i]
                    if (argb ushr 24 == 0) continue
                    val f = model.first[i]
                    val points = model.size[i]
                    when (model.kind[i]) {
                        Emblem.DOT -> {
                            fill.color = argb
                            native.drawCircle(x[idx[f]], y[idx[f]], model.width[i], fill)
                        }

                        Emblem.POLY -> {
                            shape.rewind()
                            shape.moveTo(x[idx[f]], y[idx[f]])
                            for (k in 1 until points) shape.lineTo(x[idx[f + k]], y[idx[f + k]])
                            shape.close()
                            fill.color = argb
                            native.drawPath(shape, fill)
                        }

                        else -> {
                            stroke.color = argb
                            stroke.strokeWidth = model.width[i]
                            if (points == 2) {
                                native.drawLine(x[idx[f]], y[idx[f]], x[idx[f + 1]], y[idx[f + 1]], stroke)
                            } else {
                                shape.rewind()
                                shape.moveTo(x[idx[f]], y[idx[f]])
                                for (k in 1 until points) shape.lineTo(x[idx[f + k]], y[idx[f + k]])
                                native.drawPath(shape, stroke)
                            }
                        }
                    }
                }

                // 4. What sits on top.
                val core = Offset(model.coreX, model.coreY)
                val coreUnit = unit * model.coreScale
                when (model.variant) {
                    GyroVariant.Eclipse -> if (impact > 0.01f) {
                        // The corona flashes round the dark core.
                        stroke.color = (CORONA and 0x00FFFFFF) or ((impact * 255f).toInt().coerceIn(0, 255) shl 24)
                        stroke.strokeWidth = lineUnit * (1.5f + 3f * impact)
                        native.drawCircle(core.x, core.y, coreUnit * 0.29f * (1f + 0.25f * (1f - impact)), stroke)
                    }

                    GyroVariant.Sunwheel -> {
                        drawStar(star, core, coreUnit * (0.07f + 0.05f * bass + 0.26f * impact), t * 20f, Color.White)
                        if (fine) {
                            // Speed dashes either side: a kick throws them outward.
                            val wave = model.wave
                            val near = unit * (1.66f + 0.06f * bass + (if (wave >= 0f) 0.30f * wave else 0f))
                            val far = near + unit * (0.13f + 0.10f * bass)
                            val alpha = if (wave >= 0f) 0.90f * (1f - 0.6f * wave) else 0.36f
                            for (side in -1..1 step 2) {
                                val dx = side * DASH_X
                                val dy = side * DASH_Y
                                stroke.color = (DASH_GLOW and 0x00FFFFFF) or ((alpha * 0.35f * 255f).toInt() shl 24)
                                stroke.strokeWidth = lineUnit * 7f
                                native.drawLine(centre.x + dx * near, centre.y + dy * near, centre.x + dx * far, centre.y + dy * far, stroke)
                                stroke.color = (DASH_CORE and 0x00FFFFFF) or ((alpha * 255f).toInt() shl 24)
                                stroke.strokeWidth = lineUnit * 2.4f
                                native.drawLine(centre.x + dx * near, centre.y + dy * near, centre.x + dx * far, centre.y + dy * far, stroke)
                            }
                        }
                    }

                    else -> {
                        // The starburst's heart: a warm bloom under two four-point stars, and a flat flare on an impact.
                        val swell = 1f + 0.45f * impact
                        val spin = t * 14f
                        disc(ember, core, coreUnit * (0.34f + 0.16f * bass) * swell, 1f)
                        drawStar(star, core, coreUnit * (0.12f + 0.10f * bass) * swell, spin + 45f, BurstWarm)
                        drawStar(star, core, coreUnit * (0.24f + 0.20f * bass) * swell, spin, Color.White)
                        if (impact > 0.01f) lozengeStar(star, core, coreUnit * (0.5f + 1.2f * impact), coreUnit * 0.22f * impact, impact)
                    }
                }
                drawFocusLines(model.focusXY, model.focusLines, model.focusAlpha, focus)
            }
        },
    )
}

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

/** A four-point star flattened into a flare. */
private fun DrawScope.lozengeStar(star: Path, at: Offset, halfWidth: Float, halfHeight: Float, alpha: Float) =
    lozenge(star, at, halfWidth, halfHeight, Color.White, alpha)

private const val OPAQUE = 0xFF000000.toInt()

/** Halo passes, widest first: share of the class's width, and how much of its opacity each lays down. */
private val GLOW_WIDTH = floatArrayOf(1f, 0.70f, 0.45f, 0.25f)
private val GLOW_ALPHA = floatArrayOf(0.25f, 0.40f, 0.60f, 0.80f)

private val StreakGlow = Color(0xFFFF1A38)
private val StreakCore = Color(0xFFFF8085)
private const val CORONA = 0xD9EDFF
private val NebulaViolet = Color(0xFF9E5CFF)
private val NebulaBlue = Color(0xFF4080FF)
private val BurstHot = Color(0xFFFFF2D9)
private val BurstGlow = Color(0xFFFFA840)
private val BurstWarm = Color(0xFFFFD9A0)

/** The Sunwheel's speed dashes: the direction they lie along (unit vector), and their colours. */
private val DASH_X = cos(-0.30f)
private val DASH_Y = sin(-0.30f)
private const val DASH_GLOW = 0xFFB314
private const val DASH_CORE = 0xFFE66B
