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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.theme.Nocturne
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Now Playing centrepiece, standing where album art would be: an acoustic gyroscope.
 *
 * Three nested metal hoops, each a real cylindrical band in 3D, gimballed inside one another
 * (outer turns about X, the middle about Y inside the outer's frame, the inner about X inside the
 * middle's), with a tilted Saturn-style halo of spectrum ticks around them and a light core at
 * the centre. Nothing here is an icon or a sprite: every vertex is rotated by 3x3 matrices,
 * projected with a perspective divide, shaded per face (Lambert plus a Blinn specular term from a
 * fixed key light) and painted back to front after a depth sort.
 *
 * Audio mapping:
 * - mid    -> how fast the gimbals turn
 * - bass   -> hoop radius (they breathe outward on a kick), core size, outer glow strength
 * - treble -> how hard the specular highlights flash
 * - the twelve analysed bands -> halo tick lengths, mirrored left and right
 *
 * Cost: all geometry lives in arrays allocated once in [GyroModel]; a frame allocates nothing and
 * issues a few hundred small draw ops. It redraws from the tracker's frame tick, never recomposes.
 *
 * @param halo draw the spectrum halo. Turn it off for small sizes (mini player, brand mark).
 * @param segments faces per hoop. 48 is smooth at hero size; 24 is plenty below ~64dp.
 */
@Composable
fun KineticGyroscope(
    modifier: Modifier = Modifier,
    halo: Boolean = true,
    segments: Int = 48,
) {
    val tracker = LocalAudioTracker.current
    val model = remember(segments, halo) { GyroModel(segments, halo) }
    Box(
        modifier.drawWithCache {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val unit = min(size.width, size.height) / 2f * (if (halo) 0.56f else 0.84f)
            val hairline = max(1f, 1.dp.toPx() * (if (halo) 1f else 0.6f))
            // Unit-radius brushes, placed with transforms each frame so no brush is rebuilt.
            val glow = Brush.radialGradient(
                0f to Nocturne.Amber.copy(alpha = 0.34f),
                0.40f to Nocturne.Indigo.copy(alpha = 0.16f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val core = Brush.radialGradient(
                0f to Nocturne.Alabaster.copy(alpha = 0.95f),
                0.30f to Nocturne.Amber.copy(alpha = 0.55f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val path = Path()
            onDrawBehind {
                tracker.frame.longValue // redraw every frame without recomposing
                val bass = tracker.bass.value
                model.update(
                    t = tracker.seconds,
                    bass = bass,
                    mid = tracker.mid.value,
                    treble = tracker.treble.value,
                    bands = tracker.bands,
                    cx = centre.x,
                    cy = centre.y,
                    unit = unit,
                    tickWidth = 1.6.dp.toPx(),
                )

                // Light spilling out behind the sculpture.
                val glowRadius = unit * (if (halo) 2.0f + 0.5f * bass else 1.5f + 0.4f * bass)
                translate(centre.x, centre.y) {
                    scale(glowRadius, glowRadius, pivot = Offset.Zero) {
                        drawCircle(glow, radius = 1f, center = Offset.Zero, alpha = 0.35f + 0.65f * bass)
                    }
                }

                val xy = model.xy
                for (n in 0 until model.count) {
                    val i = model.order[n]
                    val o = i * 8
                    when {
                        i == model.coreIndex -> {
                            val r = unit * (0.15f + 0.10f * bass)
                            translate(centre.x, centre.y) {
                                scale(r, r, pivot = Offset.Zero) {
                                    drawCircle(core, radius = 1f, center = Offset.Zero, alpha = 0.60f + 0.40f * bass)
                                }
                            }
                        }

                        i >= model.tickStart -> drawLine(
                            color = Color(model.fill[i]),
                            start = Offset(xy[o], xy[o + 1]),
                            end = Offset(xy[o + 2], xy[o + 3]),
                            strokeWidth = model.width[i],
                            cap = StrokeCap.Round,
                        )

                        else -> {
                            path.reset()
                            path.moveTo(xy[o], xy[o + 1])
                            path.lineTo(xy[o + 2], xy[o + 3])
                            path.lineTo(xy[o + 4], xy[o + 5])
                            path.lineTo(xy[o + 6], xy[o + 7])
                            path.close()
                            drawPath(path, Color(model.fill[i]))
                            // Both rims as hairlines: the polished edge of the band, and what keeps
                            // a hoop visible when it turns exactly edge-on to the viewer.
                            val rim = Color(model.edge[i])
                            drawLine(rim, Offset(xy[o], xy[o + 1]), Offset(xy[o + 2], xy[o + 3]), hairline)
                            drawLine(rim, Offset(xy[o + 6], xy[o + 7]), Offset(xy[o + 4], xy[o + 5]), hairline)
                        }
                    }
                }
            }
        },
    )
}

/**
 * Geometry, animation state and the per-frame transform / shade / sort pass.
 *
 * View space is x right, y down, z toward the viewer. A point projects to
 * `centre + (x, y) * unit * CAMERA / (CAMERA - z)`, i.e. a pinhole camera sitting [CAMERA] units
 * in front of the sculpture.
 */
private class GyroModel(private val segments: Int, halo: Boolean) {
    private val tickCount = if (halo) TICKS else 0
    val tickStart = RINGS * segments
    val coreIndex = tickStart + tickCount
    val count = coreIndex + 1

    /** Screen-space points, 8 floats per primitive: a quad's four corners, or a line's two ends. */
    val xy = FloatArray(count * 8)
    val fill = IntArray(count)
    val edge = IntArray(count)
    val width = FloatArray(count)

    /** Primitive indices, far to near. Kept between frames, so the sort starts almost sorted. */
    val order = IntArray(count) { it }
    private val depth = FloatArray(count)

    // Per-segment unit-circle samples: start, end (with a hair of overlap to hide seams), middle.
    private val cos0 = FloatArray(segments)
    private val sin0 = FloatArray(segments)
    private val cos1 = FloatArray(segments)
    private val sin1 = FloatArray(segments)
    private val cosM = FloatArray(segments)
    private val sinM = FloatArray(segments)
    private val tickCos = FloatArray(tickCount)
    private val tickSin = FloatArray(tickCount)

    private val angles = FloatArray(RINGS)
    private var lastSeconds = -1f

    // Scratch matrices, row-major 3x3.
    private val global = FloatArray(9)
    private val rot = FloatArray(9)
    private val m0 = FloatArray(9)
    private val m1 = FloatArray(9)
    private val m2 = FloatArray(9)

    private var cx = 0f
    private var cy = 0f
    private var unit = 1f

    init {
        val step = (2.0 * PI / segments).toFloat()
        for (j in 0 until segments) {
            val a = j * step
            cos0[j] = cos(a); sin0[j] = sin(a)
            cos1[j] = cos(a + step * 1.03f); sin1[j] = sin(a + step * 1.03f)
            cosM[j] = cos(a + step * 0.5f); sinM[j] = sin(a + step * 0.5f)
        }
        for (k in 0 until tickCount) {
            val a = (k * 2.0 * PI / tickCount).toFloat()
            tickCos[k] = cos(a); tickSin[k] = sin(a)
        }
    }

    fun update(
        t: Float,
        bass: Float,
        mid: Float,
        treble: Float,
        bands: FloatArray,
        cx: Float,
        cy: Float,
        unit: Float,
        tickWidth: Float,
    ) {
        this.cx = cx
        this.cy = cy
        this.unit = unit

        // Integrate the gimbal angles so a change in speed never makes a hoop jump.
        var dt = if (lastSeconds < 0f) 0f else t - lastSeconds
        if (dt < 0f || dt > 0.1f) dt = 0.016f
        lastSeconds = t
        val drive = 0.30f + 1.9f * mid + 0.5f * bass
        for (i in 0 until RINGS) angles[i] = (angles[i] + SPEED[i] * drive * dt) % TWO_PI

        // Whole sculpture: a slow yaw, then tipped so we look down onto it, with a gentle nod.
        rotX(global, -(0.50f + 0.10f * sin(t * 0.23f)))
        rotY(rot, t * 0.17f)
        mul(global, rot, m2)
        m2.copyInto(global)

        // Gimbal chain: each hoop turns inside the frame of the one outside it.
        rotX(rot, angles[0]); mul(global, rot, m0)
        rotY(rot, angles[1]); mul(m0, rot, m1)
        rotX(rot, angles[2]); mul(m1, rot, m2)

        ring(0, m0, RADIUS[0] * (1f + 0.10f * bass), treble)
        ring(1, m1, RADIUS[1] * (1f + 0.07f * bass + 0.05f * mid), treble)
        ring(2, m2, RADIUS[2] * (1f + 0.14f * bass), treble)
        halo(bands, tickWidth)

        depth[coreIndex] = 0f
        sortFarToNear()
    }

    private fun ring(index: Int, m: FloatArray, radius: Float, treble: Float) {
        val hw = HALF_WIDTH[index]
        val tr = TINT[index * 3]
        val tg = TINT[index * 3 + 1]
        val tb = TINT[index * 3 + 2]
        val base = index * segments
        for (j in 0 until segments) {
            val p = base + j
            val o = p * 8
            // Cylindrical band: the circle lies in the local XY plane, the band's width runs along local Z.
            project(m, radius * cos0[j], radius * sin0[j], -hw, o)
            project(m, radius * cos1[j], radius * sin1[j], -hw, o + 2)
            project(m, radius * cos1[j], radius * sin1[j], hw, o + 4)
            project(m, radius * cos0[j], radius * sin0[j], hw, o + 6)

            val c = cosM[j]
            val s = sinM[j]
            val z = (m[6] * c + m[7] * s) * radius
            depth[p] = z

            // The face normal is the outward radial direction, rotated into view space.
            var nx = m[0] * c + m[1] * s
            var ny = m[3] * c + m[4] * s
            var nz = m[6] * c + m[7] * s
            // A band is open: where its outside faces away we are looking at the inside wall.
            val inside = nz < 0f
            if (inside) {
                nx = -nx; ny = -ny; nz = -nz
            }
            val diffuse = max(0f, nx * LX + ny * LY + nz * LZ)
            var spec = max(0f, nx * HX + ny * HY + nz * HZ).pow(28f) * (0.55f + 0.90f * treble)
            var lit = 0.10f + 0.90f * diffuse
            if (inside) {
                lit *= 0.55f
                spec *= 0.35f
            }
            spec = min(spec, 1f)
            // Depth cue: the far side of a hoop sinks into the dark.
            val fog = 0.50f + 0.50f * ((z + 1.2f) / 2.4f).coerceIn(0f, 1f)

            var r = SHADOW + (tr - SHADOW) * lit
            var g = SHADOW + (tg - SHADOW) * lit
            var b = SHADOW + (tb - SHADOW) * lit
            r = (r + (1f - r) * spec) * fog
            g = (g + (1f - g) * spec) * fog
            b = (b + (1f - b) * spec) * fog
            fill[p] = argb(0.92f, r, g, b)
            edge[p] = argb((0.16f + 0.60f * spec + 0.14f * lit) * fog, 0.96f, 0.96f, 0.97f)
        }
    }

    /** Spectrum ticks standing radially in the sculpture's own equatorial (XZ) plane. */
    private fun halo(bands: FloatArray, tickWidth: Float) {
        val half = tickCount / 2
        for (k in 0 until tickCount) {
            val p = tickStart + k
            val o = p * 8
            val f = (if (k < half) k else tickCount - k).toFloat() / half
            val pos = f * (bands.size - 1)
            val lo = pos.toInt().coerceAtMost(bands.size - 2)
            val frac = pos - lo
            val level = bands[lo] * (1f - frac) + bands[lo + 1] * frac

            val inner = HALO_RADIUS
            val outer = HALO_RADIUS + 0.03f + 0.30f * level
            val scale = project(global, inner * tickCos[k], 0f, inner * tickSin[k], o)
            project(global, outer * tickCos[k], 0f, outer * tickSin[k], o + 2)
            val z = (global[6] * tickCos[k] + global[8] * tickSin[k]) * inner
            depth[p] = z
            val fog = 0.35f + 0.65f * ((z + 1.4f) / 2.8f).coerceIn(0f, 1f)
            // Quiet ticks are cool silver; loud ones warm up to amber.
            fill[p] = argb(
                (0.22f + 0.78f * level) * fog,
                0.96f + (0.886f - 0.96f) * level,
                0.96f + (0.663f - 0.96f) * level,
                0.97f + (0.420f - 0.97f) * level,
            )
            width[p] = tickWidth * scale
        }
    }

    /** Rotates a local point by [m], applies the perspective divide, stores it at [at]. Returns the scale used. */
    private fun project(m: FloatArray, x: Float, y: Float, z: Float, at: Int): Float {
        val vx = m[0] * x + m[1] * y + m[2] * z
        val vy = m[3] * x + m[4] * y + m[5] * z
        val vz = m[6] * x + m[7] * y + m[8] * z
        val s = CAMERA / (CAMERA - vz)
        xy[at] = cx + vx * unit * s
        xy[at + 1] = cy + vy * unit * s
        return s
    }

    /** Painter's algorithm order. Insertion sort: O(n) here because the order barely changes per frame. */
    private fun sortFarToNear() {
        for (a in 1 until count) {
            val item = order[a]
            val key = depth[item]
            var b = a - 1
            while (b >= 0 && depth[order[b]] > key) {
                order[b + 1] = order[b]
                b--
            }
            order[b + 1] = item
        }
    }

    private fun rotX(out: FloatArray, angle: Float) {
        val c = cos(angle)
        val s = sin(angle)
        out[0] = 1f; out[1] = 0f; out[2] = 0f
        out[3] = 0f; out[4] = c; out[5] = -s
        out[6] = 0f; out[7] = s; out[8] = c
    }

    private fun rotY(out: FloatArray, angle: Float) {
        val c = cos(angle)
        val s = sin(angle)
        out[0] = c; out[1] = 0f; out[2] = s
        out[3] = 0f; out[4] = 1f; out[5] = 0f
        out[6] = -s; out[7] = 0f; out[8] = c
    }

    /** out = a * b. [out] must not alias [a] or [b]. */
    private fun mul(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
            }
        }
    }

    private fun argb(a: Float, r: Float, g: Float, b: Float): Int =
        (channel(a) shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

    private fun channel(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    private companion object {
        const val RINGS = 3
        const val TICKS = 72
        const val CAMERA = 3.4f
        const val HALO_RADIUS = 1.30f
        const val SHADOW = 0.055f
        const val TWO_PI = 6.2831855f

        val RADIUS = floatArrayOf(1.00f, 0.76f, 0.52f)
        val HALF_WIDTH = floatArrayOf(0.075f, 0.065f, 0.055f)

        /** Base angular speed of each gimbal, radians per second. Opposed, so they never look locked together. */
        val SPEED = floatArrayOf(0.42f, -0.66f, 1.05f)

        /** Lit colour of each hoop: brushed steel, warm amber, cool indigo. */
        val TINT = floatArrayOf(
            0.86f, 0.87f, 0.90f,
            0.886f, 0.663f, 0.42f,
            0.56f, 0.58f, 0.92f,
        )

        // Key light from the upper left, in front. Unit vector from the surface toward the light.
        const val LX = -0.451f
        const val LY = -0.702f
        const val LZ = 0.551f

        // Blinn half vector between that light and the viewer (0, 0, 1).
        const val HX = -0.256f
        const val HY = -0.399f
        const val HZ = 0.881f
    }
}
