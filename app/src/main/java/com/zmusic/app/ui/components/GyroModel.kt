package com.zmusic.app.ui.components

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
    private val streamCount = if (halo) STREAMS * STREAM_SEGMENTS else 0
    private val moteCount = if (halo) MOTES else 0

    /** Primitives `[0, tickStart)` are hoop faces (quads); `[tickStart, coreIndex)` are lines. */
    val tickStart = RINGS * segments
    private val dashStart = tickStart + tickCount

    /**
     * Lines `[streamStart, moteStart)` are the aura streams. Each stream is a chain of short
     * lines that fade along its length, so they want flat ends: round ones would bead at the joints.
     */
    val streamStart = dashStart + dashCount
    val moteStart = streamStart + streamCount
    val coreIndex = moteStart + moteCount
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

    // Kicks, and what they set off besides the lightning. Small sizes never listen for them.
    private val fx = ImpactFx(if (halo) ImpactFx.FOCUS else 0)

    /**
     * The skill going off: see [ImpactFx.impact]. It drives the lightning, the focus lines, the
     * core flare and the flash of the summoning circle.
     */
    val impact: Float get() = fx.impact

    // Aura: streams of light winding up round the sculpture, and sparks rising between them.
    private val streamPhase = FloatArray(STREAMS) { it * 0.41f % 1f }
    private var auraTurn = 0f
    private val motePhase = FloatArray(moteCount) { rand(it * 7 + 1) }
    private val moteRate = FloatArray(moteCount) { 0.7f + 0.9f * rand(it * 7 + 2) }
    private val moteAngle = FloatArray(moteCount) { TWO_PI * rand(it * 7 + 3) }
    private val moteRadius = FloatArray(moteCount) { 1.16f + 0.34f * rand(it * 7 + 4) }

    /**
     * The summoning circle on the floor under the sculpture, as polylines of screen points: point
     * `k` is at `k * 2` in [sigilXY], and starts a new polyline where [sigilStart] is true. The
     * draw code strokes all of it as one path with opacity [sigilAlpha].
     */
    val sigilXY = FloatArray(if (halo) SIGIL_POINTS * 2 else 0)
    val sigilStart = BooleanArray(if (halo) SIGIL_POINTS else 0)
    var sigilPoints = 0
        private set
    var sigilAlpha = 0f
        private set
    private val sigilLocal = FloatArray(sigilXY.size)
    private val sigilGroup = IntArray(sigilStart.size)
    private val sigilTurn = FloatArray(SIGIL_GROUPS)

    /** Screen position of the summoning circle's centre, and how much the tilt flattens the circle. */
    val floorXY = FloatArray(2)
    var floorScale = 1f
        private set
    var floorSquash = 0.5f
        private set

    /**
     * Lightning from the core out to the hoops: [BOLTS] jagged polylines of [boltPoints] screen
     * points each, bolt `b` starting at `b * boltPoints * 2` in [boltXY]. [boltAlpha] zero means
     * "none showing".
     */
    val bolts = if (halo) BOLTS else 0
    val boltPoints = BOLT_POINTS
    val boltXY = FloatArray(bolts * BOLT_POINTS * 2)
    var boltAlpha = 0f
        private set
    private val boltRing = IntArray(bolts)
    private val boltSeg = IntArray(bolts)
    private var boltAge = -1f
    private var boltPeak = 0f

    /**
     * Focus lines: thin wedges pointing in at the sculpture from the edge of the frame, one
     * triangle (6 floats) each in [focusXY]. [focusAlpha] zero means "none showing".
     */
    val focusLines: Int get() = fx.focusLines
    val focusXY: FloatArray get() = fx.focusXY
    val focusAlpha: Float get() = fx.focusAlpha

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

    // Rotation of the summoning-circle group being projected.
    private var turnCos = 1f
    private var turnSin = 0f

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
        if (halo) buildSigil()
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
        frameCx: Float = cx,
        frameCy: Float = cy,
        frameHalfWidth: Float = unit * 2f,
        frameHalfHeight: Float = unit * 2f,
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
            kick(bass, dt)
            shockwave(tickWidth)
            aura(dt, bass, tickWidth)
            sigil(dt, bass)
            lightning(dt)
            fx.focus(frameCx, frameCy, frameHalfWidth, frameHalfHeight)
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

    /** Listens for kicks. One that sets off an impact also picks where the lightning lands. */
    private fun kick(bass: Float, dt: Float) {
        val struck = fx.listen(bass, dt)
        if (struck) {
            boltAge = 0f
            for (b in 0 until bolts) {
                boltRing[b] = b % RINGS
                boltSeg[b] = (rand(fx.strike * 31 + b * 7) * segments).toInt().coerceAtMost(segments - 1)
            }
        }
        // The lightning is as strong as the impact at its peak, which can come a few frames after it starts.
        if (struck || (fx.sinceImpact < ImpactFx.RISE && fx.impact > boltPeak)) boltPeak = fx.impact
    }

    /** A ring of light thrown outward through the halo plane on every kick. */
    private fun shockwave(tickWidth: Float) {
        if (fx.waveAge < 0f) {
            loopNearAlpha[1] = 0f
            loopFarAlpha[1] = 0f
            return
        }
        val u = fx.waveAge / ImpactFx.WAVE_SECONDS
        val ease = 1f - (1f - u) * (1f - u)
        loop(1, HALO_RADIUS + 0.10f + 0.62f * ease, (1f - u) * (1f - u) * 0.75f, tickWidth * (1.3f - 0.8f * u))
    }

    /**
     * The aura. Six streams of light climb from the summoning circle in a helix round the
     * sculpture, each a bright head dragging a tail that thins and fades, and sparks rise
     * straight up between them. Both keep outside the sphere the hoops sweep, so sorting them by
     * depth against the hoop faces is always right. They climb faster as the music drives harder.
     */
    private fun aura(dt: Float, bass: Float, tickWidth: Float) {
        val climb = dt * (0.10f + 0.20f * drive)
        auraTurn = (auraTurn + climb * 1.6f) % TWO_PI
        val glow = 0.65f + 0.35f * bass
        for (i in 0 until STREAMS) {
            streamPhase[i] = (streamPhase[i] + climb * STREAM_RATE[i]) % 1f
            // The head runs past the top by the stream's own length, so the tail leaves before it restarts.
            val head = streamPhase[i] * (1f + STREAM_LENGTH)
            val turn = auraTurn + i * TWO_PI / STREAMS
            val tint = i * 3
            for (k in 0 until STREAM_SEGMENTS) {
                val p = streamStart + i * STREAM_SEGMENTS + k
                val s0 = head - STREAM_LENGTH * k / STREAM_SEGMENTS
                val s1 = head - STREAM_LENGTH * (k + 1) / STREAM_SEGMENTS
                if (s1 >= 1f || s0 <= 0f) {
                    fill[p] = 0
                    continue
                }
                val a = min(s0, 1f)
                val b = max(s1, 0f)
                val a0 = turn + a * STREAM_TURNS * TWO_PI
                val a1 = turn + b * STREAM_TURNS * TWO_PI
                val tail = 1f - k.toFloat() / STREAM_SEGMENTS
                val fog = spaceLine(
                    p,
                    STREAM_RADIUS * cos(a0), FLOOR + (STREAM_TOP - FLOOR) * a, STREAM_RADIUS * sin(a0),
                    STREAM_RADIUS * cos(a1), FLOOR + (STREAM_TOP - FLOOR) * b, STREAM_RADIUS * sin(a1),
                    tickWidth * (0.35f + 1.35f * tail),
                )
                // Fades in off the floor and out again before the top.
                val mid = (a + b) * 0.5f
                val ends = smooth(mid * 8f) * smooth((1f - mid) * 4f)
                fill[p] = argb(tail * (2f - tail) * ends * fog * glow, STREAM_TINT[tint], STREAM_TINT[tint + 1], STREAM_TINT[tint + 2])
            }
        }
        val length = 0.04f + 0.04f * drive
        for (i in 0 until moteCount) {
            val p = moteStart + i
            motePhase[i] = (motePhase[i] + climb * moteRate[i]) % 1f
            val u = motePhase[i]
            val a = moteAngle[i] - auraTurn
            val x = moteRadius[i] * cos(a)
            val z = moteRadius[i] * sin(a)
            val y = FLOOR + (MOTE_TOP - FLOOR) * u
            val fog = spaceLine(p, x, y, z, x, y + length, z, tickWidth * 0.6f)
            fill[p] = argb(sin(u * PI.toFloat()) * fog * glow, 1f, 0.86f, 0.62f)
        }
    }

    /**
     * Lays the summoning circle out once, flat, in its own plane: `sigilLocal` holds (x, z) per
     * point. Three groups turn independently: 0 is the outer rings and the band of runes, 1 the
     * hexagram with a node on each point, 2 the small dial under the core.
     */
    private fun buildSigil() {
        circle(0, 0f, 0f, 1.30f, 72)
        circle(0, 0f, 0f, 1.24f, 72)
        circle(0, 0f, 0f, 1.05f, 64)
        // Runes: each sits in its own cell of the band between the rings, drawn from a short
        // table of strokes given as (across, outward) pairs within the cell.
        val cell = TWO_PI / RUNES
        for (k in 0 until RUNES) {
            val rune = (k * 7 + 3) % RUNE_COUNT
            var joined = false
            for (q in 0 until RUNE_STROKES) {
                val o = (rune * RUNE_STROKES + q) * 2
                val across = RUNE_TABLE[o]
                if (across < 0f) {
                    joined = false
                    continue
                }
                val a = (k + 0.26f + 0.48f * across) * cell
                val r = 1.095f + 0.10f * RUNE_TABLE[o + 1]
                point(0, r * cos(a), r * sin(a), start = !joined)
                joined = true
            }
        }
        // Hexagram: two triangles inscribed in the inner ring, and the circle that fits inside them.
        for (tri in 0 until 2) {
            for (k in 0..3) {
                val a = tri * TWO_PI / 6f + k * TWO_PI / 3f
                point(1, 1.05f * cos(a), 1.05f * sin(a), start = k == 0)
            }
        }
        for (k in 0 until 6) {
            val a = k * TWO_PI / 6f
            circle(1, 1.05f * cos(a), 1.05f * sin(a), 0.055f, 10)
        }
        circle(1, 0f, 0f, 0.525f, 48)
        circle(2, 0f, 0f, 0.34f, 32)
        for (k in 0 until 12) {
            val a = k * TWO_PI / 12f
            point(2, 0.38f * cos(a), 0.38f * sin(a), start = true)
            point(2, 0.47f * cos(a), 0.47f * sin(a), start = false)
        }
    }

    private fun point(group: Int, x: Float, z: Float, start: Boolean) {
        if (sigilPoints == sigilStart.size) return
        sigilLocal[sigilPoints * 2] = x
        sigilLocal[sigilPoints * 2 + 1] = z
        sigilGroup[sigilPoints] = group
        sigilStart[sigilPoints] = start
        sigilPoints++
    }

    /** A closed circle of [n] sides. It runs one side past its start, so the stroke has no open end. */
    private fun circle(group: Int, x: Float, z: Float, radius: Float, n: Int) {
        for (k in 0..n + 1) {
            val a = k * TWO_PI / n
            point(group, x + radius * cos(a), z + radius * sin(a), start = k == 0)
        }
    }

    /** Turns the summoning circle's groups, swells it with the bass, and projects it onto the floor. */
    private fun sigil(dt: Float, bass: Float) {
        val pace = dt * (0.5f + 0.5f * drive)
        val swell = 1f + 0.04f * bass + 0.05f * impact
        for (k in 0 until sigilPoints) {
            val g = sigilGroup[k]
            if (k == 0 || g != sigilGroup[k - 1]) {
                sigilTurn[g] = (sigilTurn[g] + SIGIL_SPEED[g] * pace + TWO_PI) % TWO_PI
                turnCos = cos(sigilTurn[g]) * swell
                turnSin = sin(sigilTurn[g]) * swell
            }
            val x = sigilLocal[k * 2]
            val z = sigilLocal[k * 2 + 1]
            project(tilt, x * turnCos - z * turnSin, FLOOR, x * turnSin + z * turnCos, sigilXY, k * 2)
        }
        sigilAlpha = (0.30f + 0.38f * bass + 0.32f * impact).coerceAtMost(1f)
        floorScale = project(tilt, 0f, FLOOR, 0f, floorXY, 0)
        floorSquash = abs(tilt[5])
    }

    /**
     * Lightning. For a fifth of a second after [impact] goes off, a bolt jumps from the core to a
     * point on each hoop, and one more shoots on past the outer hoop. Each bolt follows its
     * landing point as the hoop turns, and its kinks are redrawn every 45 ms so it crackles.
     */
    private fun lightning(dt: Float) {
        if (boltAge < 0f) {
            boltAlpha = 0f
            return
        }
        boltAge += dt
        if (boltAge > BOLT_SECONDS) {
            boltAge = -1f
            boltAlpha = 0f
            return
        }
        val u = boltAge / BOLT_SECONDS
        boltAlpha = (1f - u * u) * boltPeak
        val flicker = (boltAge / 0.045f).toInt()
        for (b in 0 until bolts) {
            val o = (boltRing[b] * segments + boltSeg[b]) * 8
            val reach = if (b >= RINGS) 1.6f else 1f
            val dx = ((xy[o] + xy[o + 6]) * 0.5f - cx) * reach
            val dy = ((xy[o + 1] + xy[o + 7]) * 0.5f - cy) * reach
            val at = b * BOLT_POINTS * 2
            for (k in 0 until BOLT_POINTS) {
                val f = k / (BOLT_POINTS - 1f)
                // Sideways kink, as a fraction of the bolt's length. None at the core.
                val kink = if (k == 0) 0f else (rand(fx.strike * 131 + b * 17 + flicker * 7 + k) - 0.5f) * 0.24f
                boltXY[at + k * 2] = cx + dx * f - dy * kink
                boltXY[at + k * 2 + 1] = cy + dy * f + dx * kink
            }
        }
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

    /**
     * A line between two points of the tilted (not yawed) frame. Stores its width and depth,
     * returns its fog.
     */
    private fun spaceLine(p: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, lineWidth: Float): Float {
        val o = p * 8
        val scale = project(tilt, x0, y0, z0, xy, o)
        project(tilt, x1, y1, z1, xy, o + 2)
        val z = tilt[7] * (y0 + y1) * 0.5f + tilt[8] * (z0 + z1) * 0.5f
        depth[p] = z
        width[p] = lineWidth * scale
        return fog(z)
    }

    /** Things in the halo plane fade as they recede. */
    private fun fog(z: Float): Float = 0.35f + 0.65f * ((z + 1.4f) / 2.8f).coerceIn(0f, 1f)

    private fun min1(v: Float): Float = if (v > 1f) 1f else v

    /** A repeatable scatter value in 0 until 1 for [seed]. */
    private fun rand(seed: Int): Float {
        var x = seed * 747796405 + 1013904223
        x = ((x ushr ((x ushr 28) + 4)) xor x) * 277803737
        x = x xor (x ushr 22)
        return (x ushr 8) / 16777216f
    }

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
        const val CAMERA = 3.4f
        const val HALO_RADIUS = 1.30f
        const val CIRCLE_RADIUS = 1.245f
        const val DASH_RADIUS = 1.19f
        const val TWO_PI = 6.2831855f

        /**
         * Height of the summoning circle's plane below the core (y points down). The hoops sweep a
         * sphere of radius 1.10 at their fullest, so the circle clears them.
         */
        const val FLOOR = 1.15f
        const val SIGIL_POINTS = 640
        const val SIGIL_GROUPS = 3
        const val RUNES = 20

        const val STREAMS = 6
        const val STREAM_SEGMENTS = 18

        /** Share of the climb that one stream covers from head to tail. */
        const val STREAM_LENGTH = 0.38f

        /** Turns of the helix over the whole climb. Low, so a stream reads as rising, not as another hoop. */
        const val STREAM_TURNS = 0.32f
        const val STREAM_RADIUS = 1.30f
        const val STREAM_TOP = -1.30f
        const val MOTES = 22
        const val MOTE_TOP = -1.20f

        const val BOLTS = 4
        const val BOLT_POINTS = 9
        const val BOLT_SECONDS = 0.20f

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

        /** Turning speed of each summoning-circle group, radians per second at full drive. */
        val SIGIL_SPEED = floatArrayOf(-0.30f, 0.22f, -0.80f)

        /** How fast each stream climbs relative to the others, and its colour: amber, lilac and warm white in turn. */
        val STREAM_RATE = floatArrayOf(1.00f, 0.83f, 1.21f, 0.92f, 1.12f, 0.76f)
        val STREAM_TINT = floatArrayOf(
            1.00f, 0.82f, 0.56f,
            0.80f, 0.82f, 1.00f,
            1.00f, 0.95f, 0.86f,
            1.00f, 0.82f, 0.56f,
            0.80f, 0.82f, 1.00f,
            1.00f, 0.95f, 0.86f,
        )

        /**
         * Six made-up runes. Each is up to [RUNE_STROKES] points given as (across, outward), both
         * 0..1 within the rune's cell; consecutive points are joined, and a negative pair lifts the pen.
         */
        const val RUNE_COUNT = 6
        const val RUNE_STROKES = 6
        val RUNE_TABLE = floatArrayOf(
            0f, 0f, 0f, 1f, 1f, 0.55f, 0f, 0.35f, -1f, -1f, -1f, -1f,
            0.5f, 0f, 0.5f, 1f, -1f, -1f, 0f, 0.75f, 1f, 0.30f, -1f, -1f,
            0f, 0f, 1f, 1f, -1f, -1f, 1f, 0f, 0f, 1f, -1f, -1f,
            0f, 0f, 0f, 1f, 0.5f, 0.45f, 1f, 1f, 1f, 0f, -1f, -1f,
            0.5f, 0f, 0.5f, 1f, 1f, 0.60f, -1f, -1f, 0.5f, 1f, 0f, 0.60f,
            0f, 0f, 0f, 1f, 1f, 0.75f, -1f, -1f, 0f, 0.5f, 1f, 0.25f,
        )

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
