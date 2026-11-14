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
    private val dashCount = if (halo) DASHES else 0

    /** Primitives `[0, tickStart)` are hoop faces (quads); `[tickStart, coreIndex)` are lines. */
    val tickStart = RINGS * segments
    private val dashStart = tickStart + tickCount
    val coreIndex = dashStart + dashCount
    val count = coreIndex + 1

    /** Screen-space points, 8 floats per primitive: a quad's four corners, or a line's two ends. */
    val xy = FloatArray(count * 8)

    /** Quad: its flat colour (the average of its corners). Line: its colour; zero alpha means "skip". */
    val fill = IntArray(count)

    /**
     * The hoop faces again as a smooth-shaded triangle mesh: per face two triangles, 12 floats in
     * [mesh] at `face * 12` and 6 colours in [meshColor] at `face * 6`. Neighbouring faces share
     * their corner positions and colours exactly, so tone runs round a hoop without a single step.
     * Both arrays carry spare room at the end for platform versions that over-check lengths.
     */
    val mesh = FloatArray(RINGS * segments * 12 + 24)
    val meshColor = IntArray(RINGS * segments * 6 + 24)

    /** Quad only: the bevel line drawn along both rims. */
    val edge = IntArray(count)
    val width = FloatArray(count)

    /** Primitive indices, far to near. Kept between frames, so the sort starts almost sorted. */
    val order = IntArray(count) { it }
    private val depth = FloatArray(count)

    /** One star glint per hoop, sitting on its specular highlight. Size 0 means "none". */
    val glintXY = FloatArray(RINGS * 2)
    val glintSize = FloatArray(RINGS)

    /**
     * The halo's unbroken circles, each one polyline of [loopPoints] screen points starting at
     * `loop * loopStride` in [loopXY]: 0 is the guide circle, 1 the shockwave. The first half of
     * the points is the half nearer the viewer, the second half the far one, sharing their end
     * points. Opacity runs from [loopFarAlpha] at screen height [loopFarY] to [loopNearAlpha] at
     * [loopNearY]; both zero means "not showing".
     */
    val loopPoints = LOOP + 1
    val loopStride = loopPoints * 2
    val loopXY = FloatArray(LOOPS * loopStride)
    val loopNearAlpha = FloatArray(LOOPS)
    val loopFarAlpha = FloatArray(LOOPS)
    val loopNearY = FloatArray(LOOPS)
    val loopFarY = FloatArray(LOOPS)
    val loopWidth = FloatArray(LOOPS)
    private val loopCos = FloatArray(loopPoints)
    private val loopSin = FloatArray(loopPoints)

    // Shaded colour at each face boundary of the hoop being built.
    private val toneR = FloatArray(segments)
    private val toneG = FloatArray(segments)
    private val toneB = FloatArray(segments)

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
    private var drive = 0.30f
    private var lastSeconds = -1f

    // Kick detector for the shockwave: bass against its own slow average.
    private var bassSlow = 0f
    private var waveAge = -1f

    // Scratch matrices, row-major 3x3.
    private val tilt = FloatArray(9)
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
        for (k in 0 until loopPoints) {
            val a = (k * 2.0 * PI / LOOP).toFloat()
            loopCos[k] = cos(a); loopSin[k] = sin(a)
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
        // The speed itself is eased too: the music sets where it is heading, not where it is.
        drive += (0.30f + 1.9f * mid + 0.5f * bass - drive) * min1(dt * 5f)
        for (i in 0 until RINGS) angles[i] = (angles[i] + SPEED[i] * drive * dt) % TWO_PI

        // Whole sculpture: a slow yaw, then tipped so we look down onto it, with a gentle nod.
        rotX(tilt, -(0.50f + 0.10f * sin(t * 0.23f)))
        rotY(rot, t * 0.17f)
        mul(tilt, rot, global)

        // Gimbal chain: each hoop turns inside the frame of the one outside it.
        rotX(rot, angles[0]); mul(global, rot, m0)
        rotY(rot, angles[1]); mul(m0, rot, m1)
        rotX(rot, angles[2]); mul(m1, rot, m2)

        ring(0, m0, RADIUS[0] * (1f + 0.10f * bass), t, treble)
        ring(1, m1, RADIUS[1] * (1f + 0.07f * bass + 0.05f * mid), t, treble)
        ring(2, m2, RADIUS[2] * (1f + 0.14f * bass), t, treble)
        if (halo) {
            halo(bands, tickWidth)
            loop(0, CIRCLE_RADIUS, 0.55f, tickWidth * 0.55f)
            dashes(t, tickWidth)
            shockwave(bass, dt, tickWidth)
        }

        depth[coreIndex] = 0f
        sortFarToNear()
    }

    /**
     * One hoop, shaded as a single continuous piece of polished metal. Light is worked out at the
     * boundaries between faces and blended across each face, so tone never steps along the band.
     * The Lambert term runs through a smoothstep and blends shadow -> base -> light, and the Blinn
     * highlight feathers into the surface. Treble widens the sheen.
     */
    private fun ring(index: Int, m: FloatArray, radius: Float, t: Float, treble: Float) {
        val hw = HALF_WIDTH[index]
        val pal = index * 12
        val inkR = TONES[pal]
        val inkG = TONES[pal + 1]
        val inkB = TONES[pal + 2]
        val sheenGain = 0.55f + 0.90f * treble
        val base = index * segments
        var glintX = 0f
        var glintY = 0f
        var glintW = 0f
        var glintPeak = 0f
        for (j in 0 until segments) {
            val p = base + j
            val o = p * 8
            // Cylindrical band: the circle lies in the local XY plane, the band's width runs along local Z.
            project(m, radius * cos0[j], radius * sin0[j], -hw, xy, o)
            project(m, radius * cos1[j], radius * sin1[j], -hw, xy, o + 2)
            project(m, radius * cos1[j], radius * sin1[j], hw, xy, o + 4)
            project(m, radius * cos0[j], radius * sin0[j], hw, xy, o + 6)
            depth[p] = (m[6] * cosM[j] + m[7] * sinM[j]) * radius

            // Shade at the face's leading edge. The normal is the outward radial direction,
            // rotated into view space.
            val c = cos0[j]
            val s = sin0[j]
            var nx = m[0] * c + m[1] * s
            var ny = m[3] * c + m[4] * s
            var nz = m[6] * c + m[7] * s
            val z = nz * radius
            // A band is open: where its outside faces away we are looking at the inside wall.
            val inside = nz < 0f
            if (inside) {
                nx = -nx; ny = -ny; nz = -nz
            }
            val diffuse = nx * LX + ny * LY + nz * LZ
            val gloss = max(0f, nx * HX + ny * HY + nz * HZ).pow(GLOSS)

            // Continuous tone, 0 = shadow, 0.5 = base, 1 = light, wrapped a little past the
            // terminator so the unlit side falls off gently. Both walls are lit at this point
            // on the hoop: the one we see, and the one on the other side of the metal.
            val seen = smooth((diffuse + WRAP) / (1f + WRAP))
            val hidden = smooth((WRAP - diffuse) / (1f + WRAP))
            // The inside wall sits in the hoop's own shade.
            val side = if (inside) seen * INNER_TONE else seen
            val fold = (side + (if (inside) hidden else hidden * INNER_TONE)) * 0.5f
            // Where the band turns over (normal at right angles to the eye) the outside and
            // inside walls meet. Ease both toward their shared average there, so the turn is a
            // soft roll of the same ribbon instead of a hard join between two pieces.
            val facing = smooth(nz / FOLD)
            val k = fold + (side - fold) * facing
            // Depth cue: the far side sinks a little.
            val dim = (0.80f + 0.20f * ((z + 1.2f) / 2.4f).coerceIn(0f, 1f)) *
                (FOLD_DIM + ((if (inside) INNER_DIM else 1f) - FOLD_DIM) * facing)
            val q = if (k < 0.5f) pal + 3 else pal + 6
            val f = if (k < 0.5f) k * 2f else (k - 0.5f) * 2f
            var r = (TONES[q] + (TONES[q + 3] - TONES[q]) * f) * dim
            var g = (TONES[q + 1] + (TONES[q + 4] - TONES[q + 1]) * f) * dim
            var b = (TONES[q + 2] + (TONES[q + 5] - TONES[q + 2]) * f) * dim

            // Sheen: feathered into the surface rather than cut out of it.
            if (!inside) {
                val sheen = facing * smooth((gloss * sheenGain - 0.06f) / 0.62f)
                r += (HIGHLIGHT_R - r) * sheen
                g += (HIGHLIGHT_G - g) * sheen
                b += (HIGHLIGHT_B - b) * sheen

                // Glint position: centroid of the highlight, so it glides instead of hopping faces.
                glintX += gloss * (xy[o] + xy[o + 6])
                glintY += gloss * (xy[o + 1] + xy[o + 7])
                glintW += gloss
                if (gloss > glintPeak) glintPeak = gloss
            }
            toneR[j] = r
            toneG[j] = g
            toneB[j] = b
        }

        // Stitch the faces: each one runs from its own leading edge to the next face's.
        for (j in 0 until segments) {
            val p = base + j
            val jn = if (j + 1 == segments) 0 else j + 1
            val o = p * 8
            val on = (base + jn) * 8
            val v = p * 12
            mesh[v] = xy[o]; mesh[v + 1] = xy[o + 1]
            mesh[v + 2] = xy[on]; mesh[v + 3] = xy[on + 1]
            mesh[v + 4] = xy[on + 6]; mesh[v + 5] = xy[on + 7]
            mesh[v + 6] = xy[o]; mesh[v + 7] = xy[o + 1]
            mesh[v + 8] = xy[on + 6]; mesh[v + 9] = xy[on + 7]
            mesh[v + 10] = xy[o + 6]; mesh[v + 11] = xy[o + 7]
            val here = argb(1f, toneR[j], toneG[j], toneB[j])
            val next = argb(1f, toneR[jn], toneG[jn], toneB[jn])
            val c = p * 6
            meshColor[c] = here; meshColor[c + 1] = next; meshColor[c + 2] = next
            meshColor[c + 3] = here; meshColor[c + 4] = next; meshColor[c + 5] = here

            val r = (toneR[j] + toneR[jn]) * 0.5f
            val g = (toneG[j] + toneG[jn]) * 0.5f
            val b = (toneB[j] + toneB[jn]) * 0.5f
            fill[p] = argb(1f, r, g, b)
            // The rim follows the surface tone, pulled toward the ink colour: a bevel, not an
            // outline. It is still what keeps a hoop visible when it turns exactly edge-on.
            edge[p] = argb(1f, inkR + (r - inkR) * RIM, inkG + (g - inkG) * RIM, inkB + (b - inkB) * RIM)
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

    /** Hermite smoothstep of [v] clamped to 0..1. */
    private fun smooth(v: Float): Float {
        val x = if (v < 0f) 0f else if (v > 1f) 1f else v
        return x * x * (3f - 2f * x)
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
     * One unbroken circle in the halo plane. A circle about the sculpture's axis looks the same
     * however far the sculpture has yawed, so it is laid out in the tilted frame only: point 0 on
     * the right, the near half first, then the far half.
     */
    private fun loop(index: Int, radius: Float, alpha: Float, lineWidth: Float) {
        val base = index * loopStride
        for (k in 0 until loopPoints) {
            project(tilt, radius * loopCos[k], 0f, radius * loopSin[k], loopXY, base + k * 2)
        }
        val reach = tilt[8] * radius
        loopNearAlpha[index] = alpha * fog(reach)
        loopFarAlpha[index] = alpha * fog(-reach)
        loopNearY[index] = loopXY[base + (LOOP / 4) * 2 + 1]
        loopFarY[index] = loopXY[base + (3 * LOOP / 4) * 2 + 1]
        loopWidth[index] = lineWidth
    }

    /** The dashed guide circle, slowly counter-turning inside the solid one. */
    private fun dashes(t: Float, tickWidth: Float) {
        val dash = TWO_PI / DASHES
        for (k in 0 until DASHES) {
            val p = dashStart + k
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
            loopNearAlpha[1] = 0f
            loopFarAlpha[1] = 0f
            return
        }
        val u = waveAge / WAVE_SECONDS
        val ease = 1f - (1f - u) * (1f - u)
        loop(1, HALO_RADIUS + 0.10f + 0.62f * ease, (1f - u) * (1f - u) * 0.75f, tickWidth * (1.3f - 0.8f * u))
    }

    /** A line lying in the halo plane, given in polar form. Stores its width and depth, returns its fog. */
    private fun flatLine(p: Int, r0: Float, c0: Float, s0: Float, r1: Float, c1: Float, s1: Float, lineWidth: Float): Float {
        val o = p * 8
        val scale = project(global, r0 * c0, 0f, r0 * s0, xy, o)
        project(global, r1 * c1, 0f, r1 * s1, xy, o + 2)
        val z = global[6] * (r0 * c0 + r1 * c1) * 0.5f + global[8] * (r0 * s0 + r1 * s1) * 0.5f
        depth[p] = z
        width[p] = lineWidth * scale
        return fog(z)
    }

    /** Things in the halo plane fade as they recede. */
    private fun fog(z: Float): Float = 0.35f + 0.65f * ((z + 1.4f) / 2.8f).coerceIn(0f, 1f)

    private fun min1(v: Float): Float = if (v > 1f) 1f else v

    /** Rotates a local point by [m], applies the perspective divide, stores it in [out] at [at]. Returns the scale used. */
    private fun project(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, at: Int): Float {
        val vx = m[0] * x + m[1] * y + m[2] * z
        val vy = m[3] * x + m[4] * y + m[5] * z
        val vz = m[6] * x + m[7] * y + m[8] * z
        val s = CAMERA / (CAMERA - vz)
        out[at] = cx + vx * unit * s
        out[at + 1] = cy + vy * unit * s
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
        const val DASHES = 36

        /** Unbroken circles (guide circle, shockwave) and the points along each; a multiple of four. */
        const val LOOPS = 2
        const val LOOP = 128
        const val WAVE_SECONDS = 0.75f
        const val CAMERA = 3.4f
        const val HALO_RADIUS = 1.30f
        const val CIRCLE_RADIUS = 1.245f
        const val DASH_RADIUS = 1.19f
        const val TWO_PI = 6.2831855f

        /** How far the light wraps past the terminator. Softens the shadow side of a hoop. */
        const val WRAP = 0.35f

        /** Blinn exponent. Lower is a broader, softer sheen. */
        const val GLOSS = 14f

        /** Tone and brightness of the inside wall relative to the outside. */
        const val INNER_TONE = 0.72f
        const val INNER_DIM = 0.92f

        /** Brightness exactly on the turn-over: halfway between the two walls. */
        const val FOLD_DIM = 0.96f

        /** How far (in normal z) the turn-over blend reaches round the hoop. */
        const val FOLD = 0.45f

        /** How much of the surface tone the rim keeps; the rest is the hoop's ink colour. */
        const val RIM = 0.50f

        /** The sheen: warm paper white. */
        const val HIGHLIGHT_R = 1.00f
        const val HIGHLIGHT_G = 0.984f
        const val HIGHLIGHT_B = 0.949f

        val RADIUS = floatArrayOf(1.00f, 0.76f, 0.52f)
        val HALF_WIDTH = floatArrayOf(0.080f, 0.070f, 0.060f)

        /** Base angular speed of each gimbal, radians per second. Opposed, so they never look locked together. */
        val SPEED = floatArrayOf(0.42f, -0.66f, 1.05f)

        /**
         * Palette, four RGB tones per hoop: rim ink, shadow, base, light, blended continuously
         * across the band. Shadows lean cool and lights lean warm. Hoops: moon silver, amber
         * gold, indigo.
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
