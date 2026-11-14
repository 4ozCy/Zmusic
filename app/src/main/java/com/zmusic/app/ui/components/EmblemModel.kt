package com.zmusic.app.ui.components

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The emblem variants of the Now Playing centrepiece ([GyroVariant.Eclipse], [GyroVariant.Sunwheel]
 * and [GyroVariant.Astrolabe]): animation state and the per-frame pose / project / shade / sort
 * pass. Plain Kotlin with no Compose or Android types, so it can be exercised off-device.
 *
 * An emblem is a stack of layers, laid out once by [EmblemBuilder]. Each layer is a rigid body.
 * Most of them face the viewer and lean a little off the stage's axis, the lean circling the way
 * a spinning coin's does, while they turn in their own plane; one or two tumble all the way over
 * like a gyroscope's hoops. Every vertex is rotated by its layer's matrix and projected with a
 * perspective divide, facets are lit by their true normal, and all items are painted far to near.
 *
 * View space is x right, y down, z toward the viewer, the same as [GyroModel].
 *
 * Audio mapping:
 * - mid    -> how fast everything turns
 * - bass   -> layers swell and lean further, outlines and halos brighten; a sharp kick throws a
 *             shockwave ring out through the stage and sets off [impact]
 * - treble -> how wide the sheen on lit facets opens, how bright the sparks are
 * - the twelve analysed bands -> the moving marks: the corona and tick circle of the Eclipse,
 *             the Sunwheel's gauge, the Astrolabe's degree scale and the rays of its starburst
 *
 * @param fine lay out the full emblem. False gives the cut-down one for small sizes.
 */
internal class EmblemModel private constructor(val variant: GyroVariant, fine: Boolean, g: EmblemBuilder) {
    constructor(variant: GyroVariant, fine: Boolean) : this(variant, fine, EmblemBuilder(variant, fine))

    // Vertices in their layer's own space. The spectrum, the sparks and the shockwave move some of them.
    private val px = g.x.trim()
    private val py = g.y.trim()
    private val pz = g.z.trim()
    private val vertexLayer = g.layer.trim()
    private val vertices = px.size

    /** Where each vertex lands on screen. */
    val sx = FloatArray(vertices)
    val sy = FloatArray(vertices)

    // The same vertices in view space, and the perspective scale each was drawn at.
    private val wx = FloatArray(vertices)
    private val wy = FloatArray(vertices)
    private val wz = FloatArray(vertices)
    private val ws = FloatArray(vertices)

    /**
     * What to draw. Item `i` is a [Emblem.POLY], [Emblem.LINE] or [Emblem.DOT] through the
     * vertices `idx[first[i]] until idx[first[i] + size[i]]`, in the colour [argb] (zero alpha
     * means "skip"). [width] is a line's stroke width or a dot's radius, in pixels. Items whose
     * [glow] is above zero also belong to that halo class.
     */
    val count = g.kind.n
    val kind = g.kind.trim()
    val first = g.first.trim()
    val size = g.size.trim()
    val idx = g.idx.trim()
    val glow = g.glow.trim()
    val argb = IntArray(count)
    val width = FloatArray(count)

    /** Item indices, far to near. Kept between frames, so the sort starts almost sorted. */
    val order = IntArray(count) { it }
    private val depth = FloatArray(count)
    private val mat = g.mat.trim()
    private val bias = g.bias.trim()
    private val baseWidth = g.width.trim()
    private val fade = FloatArray(count) { 1f }
    private val materials = g.materials.trim()

    /** Halo classes: class `c` (1 or more) is stroked [glowWidth]`[c - 1]` pixels wide in [glowArgb]`[c - 1]`. */
    private val glows = g.glows.trim()
    val glowCount = glows.size / Emblem.GLOW
    val glowArgb = IntArray(glowCount)
    val glowWidth = FloatArray(glowCount)

    private val dynVertex = g.dynVertex.trim()
    private val dyn = g.dyn.trim()

    private val motes = g.motes
    private val moteVertex = g.moteVertex
    private val moteItem = g.moteItem
    private val mote = g.mote.trim()
    private val moteAngle = FloatArray(motes) { mote[it * Emblem.MOTE + 3] }
    private val moteCosTip = FloatArray(motes) { cos(mote[it * Emblem.MOTE + 1]) }
    private val moteSinTip = FloatArray(motes) { sin(mote[it * Emblem.MOTE + 1]) }
    private val moteCosNode = FloatArray(motes) { cos(mote[it * Emblem.MOTE + 2]) }
    private val moteSinNode = FloatArray(motes) { sin(mote[it * Emblem.MOTE + 2]) }

    private val shockItem = g.shockItem
    private val shockVertex = g.shockVertex
    private val shockCos = FloatArray(Emblem.SHOCK) { cos(it * TWO_PI / Emblem.SHOCK) }
    private val shockSin = FloatArray(Emblem.SHOCK) { sin(it * TWO_PI / Emblem.SHOCK) }
    private var shockThin = 1f

    // Per layer: a 3x3 matrix (row-major) and where its centre sits, both in view space.
    private val layout = FloatArray(g.layers * 12)
    private val spins = FloatArray(9)
    private var drive = 0.30f
    private var lastSeconds = -1f

    private val fx = ImpactFx(if (fine) ImpactFx.FOCUS else 0)

    /** See [ImpactFx.impact]. Always 0 at small sizes, which do not listen for kicks. */
    val impact: Float get() = fx.impact

    /** How far the shockwave has travelled, 0..1, or negative when none is out. */
    val wave: Float get() = if (fx.waveAge < 0f) -1f else fx.waveAge / ImpactFx.WAVE_SECONDS
    val focusLines: Int get() = fx.focusLines
    val focusXY: FloatArray get() = fx.focusXY
    val focusAlpha: Float get() = fx.focusAlpha

    /** Screen position of the emblem's core, and the perspective scale there. */
    var coreX = 0f
        private set
    var coreY = 0f
        private set
    var coreScale = 1f
        private set
    private val coreLayer = when (variant) {
        GyroVariant.Eclipse -> Emblem.E_CORE
        GyroVariant.Sunwheel -> Emblem.S_STAR
        else -> Emblem.A_BURST
    }

    /**
     * How far the emblem reaches from its core, in units, with room for perspective: whoever
     * draws it divides the half-size of its box by this to get the pixel size of a unit.
     */
    val reach: Float = when (variant) {
        GyroVariant.Eclipse -> if (fine) 1.62f else 1.42f
        GyroVariant.Sunwheel -> if (fine) 1.90f else 1.78f
        else -> if (fine) 1.52f else 1.14f
    }

    /** The light behind the emblem, as RGB: its colour at the core, and the colour it fades out through. */
    val backdropInner: Int = when (variant) {
        GyroVariant.Eclipse -> 0xFF1A33
        GyroVariant.Sunwheel -> 0xFFBF26
        else -> 0xFF9E40
    }
    val backdropOuter: Int = when (variant) {
        GyroVariant.Eclipse -> 0x800D40
        GyroVariant.Sunwheel -> 0xE6660D
        else -> 0x8C59F2
    }

    // Scratch matrices, row-major 3x3.
    private val stage = FloatArray(9)
    private val body = FloatArray(9)
    private val held = FloatArray(9)
    private val ta = FloatArray(9)
    private val tb = FloatArray(9)
    private val tc = FloatArray(9)

    private var cx = 0f
    private var cy = 0f
    private var unit = 1f

    /**
     * @param unit pixels per emblem unit; the main ring of every emblem has radius 1
     * @param lineUnit pixels per unit of stroke width
     */
    fun update(
        t: Float,
        bass: Float,
        mid: Float,
        treble: Float,
        bands: FloatArray,
        cx: Float,
        cy: Float,
        unit: Float,
        lineUnit: Float,
        frameCx: Float = cx,
        frameCy: Float = cy,
        frameHalfWidth: Float = unit * 2f,
        frameHalfHeight: Float = unit * 2f,
    ) {
        this.cx = cx
        this.cy = cy
        this.unit = unit

        var dt = if (lastSeconds < 0f) 0f else t - lastSeconds
        if (dt < 0f || dt > 0.1f) dt = 0.016f
        lastSeconds = t
        // The music sets where the speed is heading, not where it is.
        drive += (0.30f + 1.9f * mid + 0.5f * bass - drive) * min1(dt * 5f)

        if (fx.focusLines > 0) fx.listen(bass, dt)
        pose(t, dt, bass)
        spectrum(bands)
        orbit(t, dt, treble)
        shockwave()
        project()
        shade(bass, treble, lineUnit)
        sortFarToNear()
        fx.focus(frameCx, frameCy, frameHalfWidth, frameHalfHeight)
    }

    /** Turns every layer. Angles are integrated, so a change in speed never makes one jump. */
    private fun pose(t: Float, dt: Float, bass: Float) {
        val impact = fx.impact
        val turn = drive * dt
        // The stage: tipped back so the emblem looms, swaying from side to side so the layers part.
        rotX(ta, STAGE_TILT + 0.07f * sin(t * 0.23f))
        rotY(tb, 0.40f * sin(t * 0.31f))
        mul(ta, tb, stage)
        set(0, stage, null, 1f)
        when (variant) {
            GyroVariant.Eclipse -> {
                spin(0, 0.30f * turn)
                spin(1, -0.50f * turn)
                spin(2, 0.20f * turn)
                spin(3, -0.10f * turn)
                spin(4, 0.55f * turn)
                // Star, flames and core lean together; the flames and the corona turn against the star.
                val lean = 0.10f + 0.08f * bass
                wobble(body, t * 0.50f, lean, spins[0])
                set(Emblem.E_STAR, stage, body, 1f + 0.05f * bass + 0.06f * impact)
                wobble(body, t * 0.50f, lean, spins[1])
                set(Emblem.E_HOOKS, stage, body, 1f + 0.10f * bass, lift = 0.05f)
                wobble(body, t * 0.50f, lean, spins[2])
                set(Emblem.E_CORE, stage, body, 1f + 0.05f * bass, lift = 0.10f)
                wobble(body, -t * 0.60f, 0.20f + 0.10f * impact, spins[3])
                set(Emblem.E_SILVER, stage, body, 1f + 0.03f * bass, lift = -0.04f)
                // The hoop of thorns tumbles all the way over.
                rotY(ta, t * 0.25f)
                rotX(tb, spins[4])
                mul(ta, tb, body)
                set(Emblem.E_THORN, stage, body, 1f + 0.04f * bass)
                set(Emblem.E_MOTES, stage, null, 1f)
            }

            GyroVariant.Sunwheel -> {
                spin(0, 1.10f * turn)
                spin(1, -0.20f * turn)
                spin(2, 0.08f * turn)
                spin(3, 0.80f * turn)
                val lean = 0.14f + 0.08f * bass
                wobble(body, t * 0.45f, lean, spins[1])
                set(Emblem.S_COMPASS, stage, body, 1f + 0.05f * bass + 0.07f * impact)
                wobble(body, t * 0.45f, lean, spins[0])
                set(Emblem.S_STAR, stage, body, 1f + 0.10f * bass + 0.12f * impact, lift = 0.14f)
                wobble(body, PI - t * 0.50f, 0.20f + 0.08f * impact, spins[2])
                set(Emblem.S_OUTER, stage, body, 1f + 0.03f * bass, keep = held)
                // The inner ring tumbles inside the outer one's frame, as a gimbal does.
                rotX(body, spins[3])
                set(Emblem.S_INNER, held, body, 1f + 0.03f * bass)
                set(Emblem.S_MOTES, stage, null, 1f)
            }

            else -> {
                spin(0, 0.10f * turn)
                spin(1, -0.16f * turn)
                spin(2, 1.20f * turn)
                spin(3, -0.90f * turn)
                spin(4, 1.60f * turn)
                spin(5, 0.30f * turn)
                spin(6, -0.22f * turn)
                spin(7, 0.70f * turn)
                spin(8, -1.05f * turn)
                val grow = 1f + 0.03f * bass
                wobble(body, t * 0.40f, 0.12f + 0.05f * bass, spins[0])
                set(Emblem.A_MAIN, stage, body, grow, keep = held)
                // The dials ride the main disc's rim and turn on their own centres, like gears.
                for (k in 0 until Emblem.A_DIALS) {
                    rotZ(body, spins[2 + k])
                    val a = Emblem.DIAL_ANGLE[k]
                    set(Emblem.A_DIAL + k, held, body, grow, 0.95f * grow * cos(a), 0.95f * grow * sin(a), 0f, lift = 0.10f)
                }
                wobble(body, t * 0.40f + 1f, 0.12f, spins[1])
                set(Emblem.A_FIGURE, stage, body, grow, lift = -0.10f)
                // Two smaller discs float off-centre, one in front of the main disc and one behind.
                wobble(body, -t * 0.30f, 0.18f, spins[5])
                set(Emblem.A_RING_A, stage, body, 1f + 0.05f * bass, 0.52f, -0.40f, 0.22f)
                wobble(body, t * 0.35f + 2f, 0.22f, spins[6])
                set(Emblem.A_RING_B, stage, body, 1f + 0.05f * bass, -0.62f, 0.58f, -0.24f)
                // Armillary rings: one tumbles, the other tumbles inside the first one's frame.
                rotY(ta, t * 0.20f)
                rotX(tb, spins[7])
                mul(ta, tb, body)
                set(Emblem.A_ARM_A, stage, body, 1f + 0.08f * bass, keep = held)
                rotY(body, spins[8])
                set(Emblem.A_ARM_B, held, body, 1f + 0.08f * bass)
                // The dust ring's plane stands steeply across the disc.
                rotZ(ta, 0.55f)
                rotX(tb, 1.12f)
                mul(ta, tb, body)
                set(Emblem.A_DUST, stage, body, 1f)
                rotY(ta, t * 0.21f)
                rotX(tb, t * 0.17f)
                mul(ta, tb, body)
                set(Emblem.A_BURST, stage, body, 1f + 0.10f * bass + 0.30f * impact)
            }
        }
    }

    private fun spin(index: Int, by: Float) {
        spins[index] = (spins[index] + by + TWO_PI) % TWO_PI
    }

    /**
     * The rotation of a body whose axis leans [lean] off its parent's, toward the direction
     * [phase], and which has turned [spin] about that axis.
     */
    private fun wobble(out: FloatArray, phase: Float, lean: Float, spin: Float) {
        rotZ(ta, phase)
        rotX(tb, lean)
        mul(ta, tb, tc)
        rotZ(ta, spin - phase)
        mul(tc, ta, out)
    }

    /**
     * Poses layer [l]: rotated by [rot] inside [parent], scaled, centred at ([tx], [ty], [tz]) in
     * the parent's frame and then moved [lift] along its own axis. [keep] receives its rotation,
     * for a layer that rides on this one.
     */
    private fun set(
        l: Int,
        parent: FloatArray,
        rot: FloatArray?,
        scale: Float,
        tx: Float = 0f,
        ty: Float = 0f,
        tz: Float = 0f,
        lift: Float = 0f,
        keep: FloatArray? = null,
    ) {
        val full = if (rot == null) {
            parent
        } else {
            mul(parent, rot, tc)
            tc
        }
        val o = l * 12
        for (i in 0 until 9) layout[o + i] = full[i] * scale
        layout[o + 9] = parent[0] * tx + parent[1] * ty + parent[2] * tz + full[2] * lift
        layout[o + 10] = parent[3] * tx + parent[4] * ty + parent[5] * tz + full[5] * lift
        layout[o + 11] = parent[6] * tx + parent[7] * ty + parent[8] * tz + full[8] * lift
        if (keep != null) full.copyInto(keep)
    }

    /** Slides the spectrum's marks out along their directions by their bands' levels. */
    private fun spectrum(bands: FloatArray) {
        for (k in dynVertex.indices) {
            val o = k * Emblem.DYN
            val at = dyn[o + 6] * (bands.size - 1)
            val low = at.toInt().coerceIn(0, bands.size - 2)
            val part = at - low
            val reach = dyn[o + 7] * (bands[low] * (1f - part) + bands[low + 1] * part)
            val v = dynVertex[k]
            px[v] = dyn[o] + dyn[o + 3] * reach
            py[v] = dyn[o + 1] + dyn[o + 4] * reach
            pz[v] = dyn[o + 2] + dyn[o + 5] * reach
        }
    }

    /** Carries each spark round its orbit. They run faster as the music drives harder, and treble brightens them. */
    private fun orbit(t: Float, dt: Float, treble: Float) {
        val turn = dt * (0.25f + 0.45f * drive)
        for (i in 0 until motes) {
            val o = i * Emblem.MOTE
            moteAngle[i] = (moteAngle[i] + turn * mote[o + 4]) % TWO_PI
            val along = mote[o] * cos(moteAngle[i])
            val across = mote[o] * sin(moteAngle[i])
            val flat = across * moteCosTip[i]
            val v = moteVertex + i
            px[v] = along * moteCosNode[i] - flat * moteSinNode[i]
            py[v] = along * moteSinNode[i] + flat * moteCosNode[i]
            pz[v] = across * moteSinTip[i]
            fade[moteItem + i] = (0.50f + 0.30f * treble + 0.20f * sin(t * mote[o + 5] + mote[o + 3])).coerceIn(0f, 1f)
        }
    }

    /** A ring of light thrown outward across the stage on every kick. */
    private fun shockwave() {
        val u = wave
        if (u < 0f) {
            fade[shockItem] = 0f
            return
        }
        val radius = SHOCK_FROM + SHOCK_GROW * (1f - (1f - u) * (1f - u))
        for (k in 0 until Emblem.SHOCK) {
            px[shockVertex + k] = radius * shockCos[k]
            py[shockVertex + k] = radius * shockSin[k]
        }
        fade[shockItem] = (1f - u) * (1f - u) * 0.75f
        shockThin = 1.3f - 0.8f * u
    }

    /** Rotates every vertex by its layer's pose and applies the perspective divide. */
    private fun project() {
        for (i in 0 until vertices) {
            val o = vertexLayer[i] * 12
            val x = px[i]
            val y = py[i]
            val z = pz[i]
            val vx = layout[o] * x + layout[o + 1] * y + layout[o + 2] * z + layout[o + 9]
            val vy = layout[o + 3] * x + layout[o + 4] * y + layout[o + 5] * z + layout[o + 10]
            val vz = layout[o + 6] * x + layout[o + 7] * y + layout[o + 8] * z + layout[o + 11]
            val s = CAMERA / (CAMERA - vz)
            wx[i] = vx
            wy[i] = vy
            wz[i] = vz
            ws[i] = s
            sx[i] = cx + vx * unit * s
            sy[i] = cy + vy * unit * s
        }
        val o = coreLayer * 12
        coreScale = CAMERA / (CAMERA - layout[o + 11])
        coreX = cx + layout[o + 9] * unit * coreScale
        coreY = cy + layout[o + 10] * unit * coreScale
    }

    /**
     * Works out every item's colour, width and depth. A lit facet takes its tone from its true
     * normal: shadow through base to light, with a sheen that treble opens. Everything else
     * gives off its own light and only dims a little as it recedes.
     */
    private fun shade(bass: Float, treble: Float, lineUnit: Float) {
        val impact = fx.impact
        val bright = min1(0.86f + 0.14f * bass + 0.25f * impact)
        val sheenGain = 0.55f + 0.90f * treble
        for (i in 0 until count) {
            val f = first[i]
            val n = size[i]
            val m = mat[i] * Emblem.MATERIAL
            val alpha = materials[m + 1] * fade[i]
            var z: Float
            var r: Float
            var g: Float
            var b: Float
            if (kind[i] == Emblem.POLY) {
                // Newell's method: the normal of a polygon that may not be quite flat.
                var nx = 0f
                var ny = 0f
                var nz = 0f
                z = 0f
                var p = idx[f + n - 1]
                for (k in 0 until n) {
                    val q = idx[f + k]
                    nx += (wy[p] - wy[q]) * (wz[p] + wz[q])
                    ny += (wz[p] - wz[q]) * (wx[p] + wx[q])
                    nz += (wx[p] - wx[q]) * (wy[p] + wy[q])
                    z += wz[q]
                    p = q
                }
                z /= n
                if (materials[m] == Emblem.LIT) {
                    val length = sqrt(nx * nx + ny * ny + nz * nz)
                    // Both faces are lit: whichever one is toward the viewer.
                    val flip = if (nz < 0f) -1f else 1f
                    val inverse = if (length > 1e-9f) flip / length else 0f
                    nx *= inverse
                    ny *= inverse
                    nz *= inverse
                    val tone = smooth((nx * LX + ny * LY + nz * LZ + WRAP) / (1f + WRAP))
                    val dim = 0.80f + 0.20f * ((z + 1.2f) / 2.4f).coerceIn(0f, 1f)
                    val q = if (tone < 0.5f) m + 2 else m + 5
                    val part = if (tone < 0.5f) tone * 2f else (tone - 0.5f) * 2f
                    r = (materials[q] + (materials[q + 3] - materials[q]) * part) * dim
                    g = (materials[q + 1] + (materials[q + 4] - materials[q + 1]) * part) * dim
                    b = (materials[q + 2] + (materials[q + 5] - materials[q + 2]) * part) * dim
                    val gloss = max(0f, nx * HX + ny * HY + nz * HZ).pow(GLOSS)
                    val sheen = materials[m + 11] * smooth((gloss * sheenGain - 0.06f) / 0.62f)
                    r += (1f - r) * sheen
                    g += (1f - g) * sheen
                    b += (1f - b) * sheen
                } else {
                    val level = bright * fog(z)
                    r = materials[m + 5] * level
                    g = materials[m + 6] * level
                    b = materials[m + 7] * level
                }
            } else {
                val head = idx[f]
                val tail = idx[f + n - 1]
                z = (wz[head] + wz[idx[f + n / 2]] + wz[tail]) / 3f
                width[i] = baseWidth[i] * lineUnit * (ws[head] + ws[tail]) * 0.5f
                val level = bright * fog(z)
                r = materials[m + 5] * level
                g = materials[m + 6] * level
                b = materials[m + 7] * level
            }
            depth[i] = z + bias[i]
            argb[i] = if (alpha <= 0f) 0 else argb(alpha, r, g, b)
        }
        width[shockItem] *= shockThin
        val halo = 0.75f + 0.45f * bass + 0.50f * impact
        for (c in 0 until glowCount) {
            val o = c * Emblem.GLOW
            glowArgb[c] = argb(glows[o + 4] * halo, glows[o], glows[o + 1], glows[o + 2])
            glowWidth[c] = glows[o + 3] * lineUnit
        }
    }

    /** Things that give off their own light dim as they recede. */
    private fun fog(z: Float): Float = 0.70f + 0.30f * ((z + 0.9f) / 1.3f).coerceIn(0f, 1f)

    /** Hermite smoothstep of [v] clamped to 0..1. */
    private fun smooth(v: Float): Float {
        val x = if (v < 0f) 0f else if (v > 1f) 1f else v
        return x * x * (3f - 2f * x)
    }

    private fun min1(v: Float): Float = if (v > 1f) 1f else v

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

    private fun rotZ(out: FloatArray, angle: Float) {
        val c = cos(angle)
        val s = sin(angle)
        out[0] = c; out[1] = -s; out[2] = 0f
        out[3] = s; out[4] = c; out[5] = 0f
        out[6] = 0f; out[7] = 0f; out[8] = 1f
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
        const val CAMERA = 3.4f
        const val TWO_PI = 6.2831855f
        const val PI = 3.1415927f

        /** How far the stage is tipped back, in radians. */
        const val STAGE_TILT = 0.36f

        /** The shockwave's radius when it starts, and how much further it travels. */
        const val SHOCK_FROM = 1.05f
        const val SHOCK_GROW = 0.50f

        /** How far the light wraps past the terminator, and the Blinn exponent of the sheen. */
        const val WRAP = 0.35f
        const val GLOSS = 14f

        // Key light from the upper left, in front, and the half vector between it and the viewer. As in GyroModel.
        const val LX = -0.451f
        const val LY = -0.702f
        const val LZ = 0.551f
        const val HX = -0.256f
        const val HY = -0.399f
        const val HZ = 0.881f
    }
}
