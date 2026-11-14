package com.zmusic.app.ui.components

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Geometry, animation state and the per-frame transform / shade / sort pass. Plain Kotlin with no
 * Compose or Android types, so it can be exercised off-device.
 *
 * View space is x right, y down, z toward the viewer. A point projects to
 * `centre + (x, y) * unit * CAMERA / (CAMERA - z)`, i.e. a pinhole camera sitting [CAMERA] units
 * in front of the sculpture.
 */
internal class GyroModel(private val segments: Int, private val halo: Boolean) {
    private val tickCount = if (halo) TICKS else 0
    private val arcCount = if (halo) ARCS + DASHES else 0
    private val waveCount = if (halo) WAVE else 0

    /** Primitives `[0, tickStart)` are hoop faces (quads); `[tickStart, coreIndex)` are lines. */
    val tickStart = RINGS * segments

    /** Lines from here on are joined end to end (circles, shockwave) and want butt caps. */
    val arcStart = tickStart + tickCount
    private val waveStart = arcStart + arcCount
    val coreIndex = waveStart + waveCount
    val count = coreIndex + 1

    /** Screen-space points, 8 floats per primitive: a quad's four corners, or a line's two ends. */
    val xy = FloatArray(count * 8)

    /** Quad: its flat cel tone. Line: its colour; zero alpha means "skip". */
    val fill = IntArray(count)

    /** Quad only: the ink line drawn along both rims. */
    val edge = IntArray(count)
    val width = FloatArray(count)

    /** Primitive indices, far to near. Kept between frames, so the sort starts almost sorted. */
    val order = IntArray(count) { it }
    private val depth = FloatArray(count)

    /** One star glint per hoop, sitting on its specular highlight. Size 0 means "none". */
    val glintXY = FloatArray(RINGS * 2)
    val glintSize = FloatArray(RINGS)

    // Per-segment unit-circle samples: start, end (overlapping the next face a little, which hides the seam), middle.
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

    // Kick detector for the shockwave: bass against its own slow average.
    private var bassSlow = 0f
    private var waveAge = -1f

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
            cos1[j] = cos(a + step * 1.12f); sin1[j] = sin(a + step * 1.12f)
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

        ring(0, m0, RADIUS[0] * (1f + 0.10f * bass), t, treble)
        ring(1, m1, RADIUS[1] * (1f + 0.07f * bass + 0.05f * mid), t, treble)
        ring(2, m2, RADIUS[2] * (1f + 0.14f * bass), t, treble)
        if (halo) {
            halo(bands, tickWidth)
            circles(t, tickWidth)
            shockwave(bass, dt, tickWidth)
        }

        depth[coreIndex] = 0f
        sortFarToNear()
    }

    /**
     * One hoop, cel shaded: the Lambert term is snapped to three flat tones (shadow, base, light)
     * and the Blinn highlight to a hard white streak, so tone edges are crisp lines across the
     * band instead of a gradient. Treble widens the streak.
     */
    private fun ring(index: Int, m: FloatArray, radius: Float, t: Float, treble: Float) {
        val hw = HALF_WIDTH[index]
        val pal = index * 12
        val ink = argb(1f, TONES[pal], TONES[pal + 1], TONES[pal + 2])
        val base = index * segments
        var glintX = 0f
        var glintY = 0f
        var glintW = 0f
        var glintPeak = 0f
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
            val gloss = max(0f, nx * HX + ny * HY + nz * HZ).pow(28f)
            val spec = gloss * (0.55f + 0.90f * treble)

            // Tone index: 1 shadow, 2 base, 3 light. The inside wall sits one step down.
            var tone = if (diffuse > LIGHT_AT) 3 else if (diffuse > BASE_AT) 2 else 1
            var dim = 1f
            if (inside) {
                if (tone > 1) tone-- else dim = 0.72f
            }
            // Depth cue, kept slight so the tones stay flat: the far side sinks a little.
            dim *= 0.80f + 0.20f * ((z + 1.2f) / 2.4f).coerceIn(0f, 1f)
            if (!inside && spec > 0.5f) {
                fill[p] = HIGHLIGHT
            } else {
                val q = pal + tone * 3
                fill[p] = argb(1f, TONES[q] * dim, TONES[q + 1] * dim, TONES[q + 2] * dim)
            }
            edge[p] = ink

            if (!inside) {
                // Glint position: centroid of the highlight, so it glides instead of hopping faces.
                glintX += gloss * (xy[o] + xy[o + 4])
                glintY += gloss * (xy[o + 1] + xy[o + 5])
                glintW += gloss
                if (gloss > glintPeak) glintPeak = gloss
            }
        }
        val strength = ((glintPeak - 0.35f) / 0.55f).coerceIn(0f, 1f)
        if (halo && strength > 0f && glintW > 0f) {
            glintXY[index * 2] = glintX / (2f * glintW)
            glintXY[index * 2 + 1] = glintY / (2f * glintW)
            val twinkle = 0.78f + 0.22f * sin(t * 9f + index * 2.1f)
            glintSize[index] = unit * (0.10f + 0.17f * treble) * strength * strength * twinkle
        } else {
            glintSize[index] = 0f
        }
    }

    /** Spectrum ticks standing radially in the sculpture's own equatorial (XZ) plane. */
    private fun halo(bands: FloatArray, tickWidth: Float) {
        val half = tickCount / 2
        for (k in 0 until tickCount) {
            val p = tickStart + k
            val f = (if (k < half) k else tickCount - k).toFloat() / half
            val pos = f * (bands.size - 1)
            val lo = pos.toInt().coerceAtMost(bands.size - 2)
            val frac = pos - lo
            val level = bands[lo] * (1f - frac) + bands[lo + 1] * frac

            val outer = HALO_RADIUS + 0.03f + 0.30f * level
            val fog = flatLine(p, HALO_RADIUS, tickCos[k], tickSin[k], outer, tickCos[k], tickSin[k], tickWidth)
            // Quiet ticks are cool silver; loud ones warm up to amber.
            fill[p] = argb(
                (0.30f + 0.70f * level) * fog,
                0.96f + (0.95f - 0.96f) * level,
                0.96f + (0.72f - 0.96f) * level,
                0.97f + (0.42f - 0.97f) * level,
            )
        }
    }

    /**
     * The two guide circles just inside the ticks, which turn the halo into a drawn "magic
     * circle": one solid, one dashed and slowly counter-turning.
     */
    private fun circles(t: Float, tickWidth: Float) {
        val step = TWO_PI / ARCS
        for (k in 0 until ARCS) {
            val p = arcStart + k
            val a = k * step
            val fog = flatLine(p, CIRCLE_RADIUS, cos(a), sin(a), CIRCLE_RADIUS, cos(a + step), sin(a + step), tickWidth * 0.55f)
            fill[p] = argb(0.55f * fog, 0.80f, 0.82f, 0.98f)
        }
        val dash = TWO_PI / DASHES
        for (k in 0 until DASHES) {
            val p = arcStart + ARCS + k
            val a = k * dash - t * 0.35f
            val fog = flatLine(p, DASH_RADIUS, cos(a), sin(a), DASH_RADIUS, cos(a + dash * 0.5f), sin(a + dash * 0.5f), tickWidth * 0.55f)
            fill[p] = argb(0.50f * fog, 0.95f, 0.72f, 0.42f)
        }
    }

    /** A ring of light thrown outward through the halo plane on every kick. */
    private fun shockwave(bass: Float, dt: Float, tickWidth: Float) {
        bassSlow += (bass - bassSlow) * min1(dt * 2.5f)
        if (waveAge >= 0f) {
            waveAge += dt
            if (waveAge > WAVE_SECONDS) waveAge = -1f
        }
        if ((waveAge < 0f || waveAge > 0.28f) && bass - bassSlow > 0.13f) waveAge = 0f
        if (waveAge < 0f) {
            for (k in 0 until WAVE) fill[waveStart + k] = 0
            return
        }
        val u = waveAge / WAVE_SECONDS
        val ease = 1f - (1f - u) * (1f - u)
        val radius = HALO_RADIUS + 0.10f + 0.62f * ease
        val alpha = (1f - u) * (1f - u) * 0.75f
        val step = TWO_PI / WAVE
        for (k in 0 until WAVE) {
            val p = waveStart + k
            val a = k * step
            val fog = flatLine(p, radius, cos(a), sin(a), radius, cos(a + step), sin(a + step), tickWidth * (1.3f - 0.8f * u))
            fill[p] = argb(alpha * fog, 1f, 0.90f, 0.74f)
        }
    }

    /** A line lying in the halo plane, given in polar form. Stores its width and depth, returns its fog. */
    private fun flatLine(p: Int, r0: Float, c0: Float, s0: Float, r1: Float, c1: Float, s1: Float, lineWidth: Float): Float {
        val o = p * 8
        val scale = project(global, r0 * c0, 0f, r0 * s0, o)
        project(global, r1 * c1, 0f, r1 * s1, o + 2)
        val z = global[6] * (r0 * c0 + r1 * c1) * 0.5f + global[8] * (r0 * s0 + r1 * s1) * 0.5f
        depth[p] = z
        width[p] = lineWidth * scale
        return 0.35f + 0.65f * ((z + 1.4f) / 2.8f).coerceIn(0f, 1f)
    }

    private fun min1(v: Float): Float = if (v > 1f) 1f else v

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
        const val ARCS = 40
        const val DASHES = 24
        const val WAVE = 60
        const val WAVE_SECONDS = 0.75f
        const val CAMERA = 3.4f
        const val HALO_RADIUS = 1.30f
        const val CIRCLE_RADIUS = 1.245f
        const val DASH_RADIUS = 1.19f
        const val TWO_PI = 6.2831855f

        /** Lambert thresholds where the cel tone steps up. */
        const val BASE_AT = 0.20f
        const val LIGHT_AT = 0.60f

        /** The hard specular streak: warm paper white. */
        const val HIGHLIGHT = 0xFFFFFBF2.toInt()

        val RADIUS = floatArrayOf(1.00f, 0.76f, 0.52f)
        val HALF_WIDTH = floatArrayOf(0.080f, 0.070f, 0.060f)

        /** Base angular speed of each gimbal, radians per second. Opposed, so they never look locked together. */
        val SPEED = floatArrayOf(0.42f, -0.66f, 1.05f)

        /**
         * Cel palette, four RGB tones per hoop: ink line, shadow, base, light. Shadows lean cool
         * and lights lean warm, the way painted cels do. Hoops: moon silver, amber gold, indigo.
         */
        val TONES = floatArrayOf(
            0.20f, 0.21f, 0.38f, 0.42f, 0.44f, 0.66f, 0.76f, 0.78f, 0.90f, 0.95f, 0.95f, 0.98f,
            0.36f, 0.19f, 0.13f, 0.66f, 0.38f, 0.26f, 0.90f, 0.66f, 0.40f, 1.00f, 0.87f, 0.64f,
            0.14f, 0.14f, 0.40f, 0.27f, 0.28f, 0.68f, 0.44f, 0.46f, 0.90f, 0.68f, 0.74f, 1.00f,
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
