package com.zmusic.app.ui.components

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The emblem variants of the Now Playing centrepiece ([GyroVariant.Eclipse], [GyroVariant.Sunwheel]
 * and [GyroVariant.Astrolabe]): animation state and the per-frame pose / project / shade / sort
 * pass. Plain Kotlin with no Compose or Android types, so it can be exercised off-device.
 *
 * An emblem is a stack of layers, laid out once by [EmblemBuilder]. Each layer is a rigid body;
 * every vertex is rotated by its layer's matrix and projected with a perspective divide, facets
 * are lit by their true normal, and all items are painted far to near. Each emblem moves in its
 * own way and answers a kick with its own effect:
 *
 * - **Eclipse** is heavy clockwork. The star holds still, then ratchets a quarter turn on a kick
 *   (or after a few quiet seconds) and overshoots as it lands; the silver ring clicks a quarter
 *   turn the other way. Flames flicker, embers spiral down into the core, and the crown of thorns
 *   lies tilted round it and tightens on the kick. A kick cuts two crescent slashes across the
 *   emblem ([slashXY]) and shakes it.
 * - **Sunwheel** is a pinwheel. The star spins fast with afterimages trailing behind it and
 *   throws sparks off its arm tips; the leaves sway, the spears strain outward with the bass, and
 *   the gold outlines now and then buzz like a neon sign. A kick sends rays out of the core
 *   ([rayXY]), launches the spears and throws a burst of sparks.
 * - **Astrolabe** keeps time. The disc ticks round like a second hand with its dials geared to
 *   it, two planets run round tilted orbits, a light reads along the band of runes, and a comet
 *   crosses behind now and then. It draws itself in, line by line, when it arrives. A kick sends
 *   ripples out across the disc ([rippleXY]) and joins some of the gold dust into a constellation.
 *
 * Each also has its own entrance: the Eclipse's core opens and its star blooms out of it, the
 * Sunwheel spins up from a blur, the Astrolabe draws itself in.
 *
 * View space is x right, y down, z toward the viewer, the same as [GyroModel].
 *
 * @param fine lay out the full emblem, with its effects. False gives the cut-down one for small sizes.
 * @param intro play the entrance. Off when system animations are, since the clock never runs then.
 */
internal class EmblemModel private constructor(
    val variant: GyroVariant,
    private val fine: Boolean,
    intro: Boolean,
    g: EmblemBuilder,
) {
    constructor(variant: GyroVariant, fine: Boolean, intro: Boolean = true) : this(variant, fine, intro, EmblemBuilder(variant, fine))

    // Vertices in their layer's own space. The spectrum, the sway, the planets and the sparks move some of them.
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

    /** How much of each line to draw, 0..1 from its first point. Below 1 only while the Astrolabe draws itself in. */
    val reveal = FloatArray(count) { 1f }

    /** Item indices, far to near. Kept between frames, so the sort starts almost sorted. */
    val order = IntArray(count) { it }
    private val depth = FloatArray(count)
    private val mat = g.mat.trim()
    private val bias = g.bias.trim()
    private val baseWidth = g.width.trim()
    private val fade = FloatArray(count) { 1f }
    private val materials = g.materials.trim()
    private val tag = g.tag.trim()
    private val tagged = (0 until count).filter { !tag[it].isNaN() }.toIntArray()
    private val itemLayer = IntArray(count) { vertexLayer[idx[first[it]]] }

    /** Halo classes: class `c` (1 or more) is stroked [glowWidth]`[c - 1]` pixels wide in [glowArgb]`[c - 1]`. */
    private val glows = g.glows.trim()
    val glowCount = glows.size / Emblem.GLOW
    val glowArgb = IntArray(glowCount)
    val glowWidth = FloatArray(glowCount)

    private val dynVertex = g.dynVertex.trim()
    private val dyn = g.dyn.trim()
    private val swayVertex = g.swayVertex.trim()
    private val sway = g.sway.trim()
    private val riderVertex = g.riderVertex.trim()
    private val rider = g.rider.trim()
    private val riderTurn = FloatArray(riderVertex.size)

    private val motes = g.motes
    private val moteVertex = g.moteVertex
    private val moteItem = g.moteItem
    private val mote = g.mote.trim()
    private val moteAngle = FloatArray(motes) { mote[it * Emblem.MOTE + 3] }
    private val moteLife = FloatArray(motes) { mote[it * Emblem.MOTE + 3] / TWO_PI }
    private val moteSpawn = FloatArray(motes) { SIXTH + (it % 3) * THIRD }
    private val moteGen = IntArray(motes)
    private val moteCosTip = FloatArray(motes) { cos(mote[it * Emblem.MOTE + 1]) }
    private val moteSinTip = FloatArray(motes) { sin(mote[it * Emblem.MOTE + 1]) }
    private val moteCosNode = FloatArray(motes) { cos(mote[it * Emblem.MOTE + 2]) }
    private val moteSinNode = FloatArray(motes) { sin(mote[it * Emblem.MOTE + 2]) }

    // Per layer: a 3x3 matrix (row-major) and where its centre sits, both in view space.
    private val layout = FloatArray(g.layers * 12)
    private val spins = FloatArray(4)
    private var drive = 0.30f
    private var lastSeconds = -1f

    /** Seconds since the emblem first drew, up to [INTRO_DONE]. Drives the entrances. */
    private var age = if (intro) 0f else INTRO_DONE

    // Kicks. Small sizes never listen, and no emblem shows the gyroscope's focus lines.
    private val fx = ImpactFx(0)

    /** See [ImpactFx.impact]. Always 0 at small sizes, which do not listen for kicks. */
    val impact: Float get() = fx.impact

    /** How strong this emblem's own kick effect is right now, 0..1. 0 when none is showing. */
    var burst = 0f
        private set

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

    // ---- Eclipse ----
    private val starTurn = FloatArray(2)
    private var starGoal = 0f
    private val ringTurn = FloatArray(2)
    private var ringGoal = 0f
    private var sinceStep = 0f
    private var offsetX = 0f
    private var offsetY = 0f

    /**
     * Eclipse: crescent slashes, [slashes] closed outlines of [slashPoints] screen points each,
     * slash `k` starting at `k * slashPoints * 2` in [slashXY]. [slashAlpha] zero means "none".
     */
    val slashes = if (variant == GyroVariant.Eclipse && fine) SLASHES else 0
    val slashPoints = SLASH_POINTS * 2
    val slashXY = FloatArray(slashes * slashPoints * 2)
    var slashAlpha = 0f
        private set
    private var slashAge = -1f
    private var slashAngle = 0f

    // ---- Sunwheel ----
    private var starAngle = 0f
    private var starSpeed = 0f
    private var precess = 0f

    /** Sunwheel: rays out of the core on a kick, one triangle (6 floats) each in [rayXY]. [rayAlpha] zero means "none". */
    val rays = if (variant == GyroVariant.Sunwheel && fine) RAYS else 0
    val rayXY = FloatArray(rays * 6)
    var rayAlpha = 0f
        private set

    // ---- Astrolabe ----
    private val mainTurn = FloatArray(2)
    private var mainGoal = 0f
    private var tick = 0f
    private var sweep = 0f
    private var revealed = false

    /**
     * Astrolabe: ripples across the disc on a kick, [ripples] closed polylines of [ripplePoints]
     * screen points each, ripple `k` starting at `k * ripplePoints * 2` in [rippleXY], each with
     * its own opacity in [rippleAlpha] (zero means "not showing").
     */
    val ripples = if (variant == GyroVariant.Astrolabe && fine) RIPPLES else 0
    val ripplePoints = RIPPLE_POINTS + 1
    val rippleXY = FloatArray(ripples * ripplePoints * 2)
    val rippleAlpha = FloatArray(ripples)
    private var rippleAge = -1f
    private val ringCos = FloatArray(ripplePoints) { cos(it * TWO_PI / RIPPLE_POINTS) }
    private val ringSin = FloatArray(ripplePoints) { sin(it * TWO_PI / RIPPLE_POINTS) }

    /** Astrolabe: the vertices (indices into [sx] and [sy]) a kick joins into a constellation, in order. [constellationAlpha] zero means "none". */
    val constellation = IntArray(if (ripples > 0 && motes >= CONSTELLATION * 5) CONSTELLATION else 0)
    var constellationAlpha = 0f
        private set
    private var constellationAge = -1f

    /** Astrolabe: a comet's head on screen, its tail (head to tail end, in pixels) and its opacity. [cometAlpha] zero means "none". */
    var cometX = 0f
        private set
    var cometY = 0f
        private set
    var cometTailX = 0f
        private set
    var cometTailY = 0f
        private set
    var cometAlpha = 0f
        private set
    private var cometClock = COMET_EVERY - 2.5f
    private var comets = 0

    // Scratch matrices, row-major 3x3.
    private val stage = FloatArray(9)
    private val body = FloatArray(9)
    private val held = FloatArray(9)
    private val ta = FloatArray(9)
    private val tb = FloatArray(9)
    private val tc = FloatArray(9)
    private val td = FloatArray(9)

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
    ) {
        this.cx = cx
        this.cy = cy
        this.unit = unit

        var dt = if (lastSeconds < 0f) 0f else t - lastSeconds
        if (dt < 0f || dt > 0.1f) dt = 0.016f
        lastSeconds = t
        age = min(age + dt, INTRO_DONE)
        // The music sets where the speed is heading, not where it is.
        drive += (0.30f + 1.9f * mid + 0.5f * bass - drive) * min1(dt * 5f)
        val struck = fine && fx.listen(bass, dt)

        when (variant) {
            GyroVariant.Eclipse -> eclipse(t, dt, bass, struck)
            GyroVariant.Sunwheel -> sunwheel(t, dt, bass, struck)
            else -> astrolabe(t, dt, bass, treble, struck)
        }
        bend(t, mid)
        spectrum(bands)
        project()
        shade(bass, treble, lineUnit)
        sortFarToNear()
        burst = when (variant) {
            GyroVariant.Eclipse -> slash(dt, struck)
            GyroVariant.Sunwheel -> sunRays()
            else -> {
                val ripple = ripple(dt, struck)
                stars(dt, struck)
                comet(dt)
                max(ripple, constellationAlpha)
            }
        }
    }

    // ---- Eclipse ----

    private fun eclipse(t: Float, dt: Float, bass: Float, struck: Boolean) {
        val impact = fx.impact
        val turn = drive * dt
        // Clockwork: the star and the ring hold, then step on a kick, or on their own in quiet music.
        sinceStep += dt
        if (struck || sinceStep > STEP_IDLE) {
            sinceStep = 0f
            starGoal += QUARTER_PI
            ringGoal -= HALF_PI
        }
        spring(starTurn, starGoal, dt, 90f, 11f)
        spring(ringTurn, ringGoal, dt, 70f, 10f)
        starGoal = rewind(starTurn, starGoal)
        ringGoal = rewind(ringTurn, ringGoal)
        spin(0, -0.45f * turn)
        spin(1, 0.25f * turn)
        spin(2, 0.35f * turn)

        // The kick jolts the whole emblem. The jolt changes direction every 30 ms and dies with the impact.
        val jolt = (fx.sinceImpact / 0.03f).toInt()
        val shake = impact * impact * SHAKE
        offsetX = shake * (Emblem.scatter(fx.strike * 31 + jolt * 2) - 0.5f) * 2f
        offsetY = shake * (Emblem.scatter(fx.strike * 31 + jolt * 2 + 1) - 0.5f) * 2f

        // Entrance: the core opens, then the star blooms out of it and the ring swings into place.
        val opening = backOut(age / 0.7f)
        val bloom = backOut((age - 0.15f) / 0.7f)
        val settle = easeOut(age / 0.9f)

        rotX(ta, 0.22f + 0.05f * sin(t * 0.21f))
        rotY(tb, 0.20f * sin(t * 0.27f))
        mul(ta, tb, stage)
        set(0, stage, null, 1f)
        rotZ(body, starTurn[0] + 0.04f * t)
        set(Emblem.E_STAR, stage, body, bloom * (1f + 0.05f * bass + 0.14f * impact))
        rotZ(body, spins[0])
        set(Emblem.E_HOOKS, stage, body, bloom * (1f + 0.10f * bass), lift = 0.05f)
        rotZ(body, spins[1])
        set(Emblem.E_CORE, stage, body, opening * (1f + 0.05f * bass + 0.22f * impact), lift = 0.10f)
        rotZ(body, ringTurn[0] - HALF_PI * (1f - settle))
        set(Emblem.E_SILVER, stage, body, (1.25f - 0.25f * settle) * (1f + 0.03f * bass), lift = -0.04f)
        // The crown lies tilted steeply round the core, turning in its own plane.
        rotZ(ta, 0.35f)
        rotX(tb, 1.18f)
        mul(ta, tb, td)
        rotZ(ta, spins[2])
        mul(td, ta, body)
        set(Emblem.E_THORN, stage, body, (0.6f + 0.4f * settle) * (1f + 0.06f * bass - 0.10f * impact))
        set(Emblem.E_MOTES, stage, null, 1f)

        // Flames lick: each flickers on its own two beats.
        for (i in tagged) {
            val seed = tag[i]
            fade[i] = 0.70f + 0.30f * (0.5f + 0.5f * sin(t * 11f + seed * 7f) * sin(t * 6.3f + seed * 3f))
        }

        // Embers spiral down into the core, faster as they fall, and vanish at its rim.
        val pull = dt * (0.22f + 0.30f * drive)
        for (i in 0 until motes) {
            val o = i * Emblem.MOTE
            moteLife[i] = (moteLife[i] + pull * mote[o + 4]) % 1f
            val life = moteLife[i]
            val radius = CORE_EDGE + (mote[o] - CORE_EDGE) * (1f - life)
            val a = mote[o + 2] + 2.6f * life * life + spins[1]
            val v = moteVertex + i
            px[v] = radius * cos(a)
            py[v] = radius * sin(a)
            pz[v] = mote[o + 1] * (1f - life)
            fade[moteItem + i] = smooth(life * 6f) * (1f - smooth((life - 0.82f) / 0.18f))
        }
    }

    /** The crescent slashes. Each is drawn in over 90 ms, like a blade passing, then fades. Returns its opacity. */
    private fun slash(dt: Float, struck: Boolean): Float {
        if (slashes == 0) return 0f
        if (struck) {
            slashAge = 0f
            slashAngle = TWO_PI * Emblem.scatter(fx.strike * 53 + 5)
        } else if (slashAge >= 0f) {
            slashAge += dt
            if (slashAge > SLASH_SECONDS) slashAge = -1f
        }
        if (slashAge < 0f) {
            slashAlpha = 0f
            return 0f
        }
        val u = clamp01(slashAge / SLASH_SECONDS)
        slashAlpha = (1f - u).pow(1.3f)
        val drawn = smooth(slashAge / 0.09f)
        val radius = (1.5f + 0.15f * u) * unit
        val thick = 0.11f * unit * (1f + 0.3f * u)
        for (k in 0 until slashes) {
            // A wide arc whose middle passes just by the core; the two cross like an X.
            val theta = slashAngle + k * 1.9f
            val arcX = coreX - 0.85f * radius * sin(theta)
            val arcY = coreY + 0.85f * radius * cos(theta)
            val middle = theta - HALF_PI
            val base = k * slashPoints * 2
            for (j in 0 until SLASH_POINTS) {
                val s = drawn * j / (SLASH_POINTS - 1)
                val a = middle - SLASH_SPAN / 2f + SLASH_SPAN * s
                // Pointed at both ends: at the start, and at the leading edge while it is still being drawn.
                val th = thick * sin(PI * s) * min(1f, (drawn - s) / 0.12f)
                val c = cos(a)
                val sn = sin(a)
                slashXY[base + j * 2] = arcX + (radius + th / 2f) * c
                slashXY[base + j * 2 + 1] = arcY + (radius + th / 2f) * sn
                val back = base + (slashPoints - 1 - j) * 2
                slashXY[back] = arcX + (radius - th / 2f) * c
                slashXY[back + 1] = arcY + (radius - th / 2f) * sn
            }
        }
        return slashAlpha
    }

    // ---- Sunwheel ----

    private fun sunwheel(t: Float, dt: Float, bass: Float, struck: Boolean) {
        val impact = fx.impact
        val turn = drive * dt
        // Entrance: it arrives spinning in a blur and slows to its working speed.
        val spinUp = 1f - clamp01(age / 1.1f)
        starSpeed = 1.4f + 2.6f * drive + 4f * impact + 16f * spinUp * spinUp
        starAngle = (starAngle + starSpeed * dt) % TWO_PI
        spin(0, -0.12f * turn)
        spin(1, 0.10f * turn)
        spin(2, 0.70f * turn)
        precess = (precess + dt * (0.6f + 0.5f * drive)) % TWO_PI

        val grow = backOut(age / 0.8f)
        val rings = easeOut((age - 0.1f) / 0.8f)

        rotX(ta, 0.46f + 0.05f * sin(t * 0.21f))
        rotY(tb, 0.30f * sin(t * 0.17f))
        mul(ta, tb, stage)
        set(0, stage, null, 1f)
        rotZ(body, spins[0])
        set(Emblem.S_COMPASS, stage, body, grow * (1f + 0.04f * bass))
        val starScale = grow * (1f + 0.10f * bass + 0.12f * impact)
        rotZ(body, starAngle)
        set(Emblem.S_STAR, stage, body, starScale, lift = 0.14f)
        // Afterimages hang back behind the spin, further the faster it goes.
        val lag = min(0.45f, starSpeed * 0.035f)
        for (k in 0 until Emblem.S_GHOSTS) {
            rotZ(body, starAngle - lag * (k + 1))
            set(Emblem.S_GHOST + k, stage, body, starScale, lift = 0.135f - 0.005f * k)
        }
        rotZ(body, spins[1])
        set(Emblem.S_OUTER, stage, body, (0.4f + 0.6f * rings) * (1f + 0.03f * bass))
        // The inner ring is tipped over, and the way it leans circles round like a settling coin.
        rotZ(ta, precess)
        rotX(tb, 0.50f + 0.12f * bass)
        mul(ta, tb, td)
        rotZ(ta, spins[2] - precess)
        mul(td, ta, body)
        set(Emblem.S_INNER, stage, body, (0.4f + 0.6f * rings) * (1f + 0.03f * bass))
        set(Emblem.S_MOTES, stage, null, 1f, lift = 0.14f)

        // Afterimages show only at speed. Now and then a gold outline buzzes like a neon sign.
        val trail = clamp01((starSpeed - 1.6f) / 3f)
        val slot = (t / FLICKER_SLOT).toInt()
        for (i in 0 until count) {
            val l = itemLayer[i]
            fade[i] = if (l >= Emblem.S_GHOST) {
                GHOST_FADE[l - Emblem.S_GHOST] * trail
            } else if (fine && glow[i] > 0 && kind[i] == Emblem.LINE && Emblem.scatter(slot * 131 + i * 977) < FLICKER) {
                0.35f
            } else {
                1f
            }
        }

        // Sparks fly off the arm tips along the spin and curve outward. A kick throws them all at once.
        val rate = dt * (0.8f + 0.5f * drive)
        val bright = clamp01(0.35f + 0.65f * (starSpeed - 1.4f) / 3f)
        val tip = ARM * starScale
        for (i in 0 until motes) {
            val o = i * Emblem.MOTE
            moteLife[i] += rate * mote[o + 4]
            if (struck || moteLife[i] >= 1f) {
                moteLife[i] = if (struck) 0.15f * Emblem.scatter(i * 13 + fx.strike) else moteLife[i] % 1f
                moteGen[i]++
                moteSpawn[i] = starAngle + SIXTH + (i % 3) * THIRD
            }
            val life = clamp01(moteLife[i])
            val from = moteSpawn[i]
            val heading = from + HALF_PI - 0.6f * life - 0.5f * (Emblem.scatter(i * 7 + moteGen[i]) - 0.5f)
            val travel = (0.45f + 0.47f * mote[o]) * (1f - (1f - life) * (1f - life))
            val v = moteVertex + i
            px[v] = tip * cos(from) + travel * cos(heading)
            py[v] = tip * sin(from) + travel * sin(heading)
            pz[v] = 0f
            fade[moteItem + i] = (1f - life).pow(1.5f) * bright
        }
    }

    /** Rays out of the core while the kick lasts, turning with the star and lengthening as they fade. Returns their opacity. */
    private fun sunRays(): Float {
        val impact = fx.impact
        if (rays == 0 || impact < 0.01f) {
            rayAlpha = 0f
            return 0f
        }
        rayAlpha = 0.55f * impact
        val length = unit * coreScale * (1.25f + 0.90f * (1f - impact))
        val half = unit * coreScale * 0.06f
        for (k in 0 until rays) {
            val a = starAngle + k * TWO_PI / rays
            val l = if (k % 2 == 0) length else length * 0.62f
            val c = cos(a)
            val s = sin(a)
            val o = k * 6
            rayXY[o] = coreX - s * half
            rayXY[o + 1] = coreY + c * half
            rayXY[o + 2] = coreX + s * half
            rayXY[o + 3] = coreY - c * half
            rayXY[o + 4] = coreX + c * l
            rayXY[o + 5] = coreY + s * l
        }
        return rayAlpha
    }

    // ---- Astrolabe ----

    private fun astrolabe(t: Float, dt: Float, bass: Float, treble: Float, struck: Boolean) {
        val impact = fx.impact
        val turn = drive * dt
        // A second hand: the disc ticks on, faster as the music drives harder. A kick jumps it three ticks.
        tick += dt * (0.9f + 0.8f * drive)
        if (tick >= 1f) {
            tick -= 1f
            mainGoal += TICK
        }
        if (struck) mainGoal += TICK * 3f
        spring(mainTurn, mainGoal, dt, 220f, 17f)
        mainGoal = rewind(mainTurn, mainGoal)
        spin(0, -0.10f * turn)
        spin(1, 0.25f * turn)
        spin(2, -0.20f * turn)
        sweep = (sweep + dt * (0.9f + 0.9f * drive)) % TWO_PI

        val settle = easeOut(age / INTRO_A)
        val grow = (0.92f + 0.08f * settle) * (1f + 0.03f * bass)

        rotX(ta, 0.30f + 0.06f * sin(t * 0.19f))
        rotY(tb, 0.32f * sin(t * 0.23f))
        mul(ta, tb, stage)
        set(0, stage, null, 1f)
        rotZ(body, mainTurn[0])
        set(Emblem.A_MAIN, stage, body, grow, keep = held)
        // The dials ride the disc's rim and turn as gears do, faster the smaller they are.
        for (k in 0 until Emblem.A_DIALS) {
            rotZ(body, -mainTurn[0] * Emblem.DIAL_RIM / Emblem.DIAL_RADIUS[k])
            val a = Emblem.DIAL_ANGLE[k]
            set(Emblem.A_DIAL + k, held, body, grow, Emblem.DIAL_RIM * grow * cos(a), Emblem.DIAL_RIM * grow * sin(a), 0f, lift = 0.10f)
        }
        rotZ(body, spins[0])
        set(Emblem.A_FIGURE, stage, body, grow, lift = -0.10f)
        // The two small discs bob as if floating.
        rotX(ta, 0.25f)
        rotZ(tb, spins[1])
        mul(ta, tb, body)
        set(Emblem.A_RING_A, stage, body, 1f + 0.05f * bass, 0.52f, -0.40f + 0.03f * sin(t * 0.8f), 0.22f + 0.05f * sin(t * 1.1f))
        rotY(ta, -0.22f)
        rotZ(tb, spins[2])
        mul(ta, tb, body)
        set(Emblem.A_RING_B, stage, body, 1f + 0.05f * bass, -0.62f, 0.58f + 0.03f * sin(t * 0.7f + 1f), -0.24f + 0.05f * sin(t * 0.9f + 2f))
        // Orbits: planes at fixed tilts whose nodes drift round.
        rotZ(ta, 0.4f + t * 0.05f)
        rotX(tb, 1.22f)
        mul(ta, tb, body)
        set(Emblem.A_ORBIT_A, stage, body, 1f + 0.06f * bass)
        rotZ(ta, -1.1f - t * 0.04f)
        rotX(tb, 1.38f)
        mul(ta, tb, body)
        set(Emblem.A_ORBIT_B, stage, body, 1f + 0.06f * bass)
        rotZ(ta, 0.55f)
        rotX(tb, 1.12f)
        mul(ta, tb, body)
        set(Emblem.A_DUST, stage, body, 1f)
        rotY(ta, t * 0.21f)
        rotX(tb, t * 0.17f)
        mul(ta, tb, body)
        set(Emblem.A_BURST, stage, body, 1f + 0.10f * bass + 0.30f * impact)

        // Planets run round their orbits.
        for (k in riderVertex.indices) {
            val o = k * Emblem.RIDER
            riderTurn[k] = (riderTurn[k] + dt * rider[o + 1] * (0.5f + 0.8f * drive)) % TWO_PI
            val a = rider[o + 2] + riderTurn[k]
            val v = riderVertex[k]
            px[v] = rider[o] * cos(a)
            py[v] = rider[o] * sin(a)
            pz[v] = 0f
        }

        // A light reads along the runes; a kick lights them all.
        for (i in tagged) {
            var d = abs(tag[i] - sweep) % TWO_PI
            if (d > PI) d = TWO_PI - d
            fade[i] = 0.40f + 0.60f * max(exp(-(d * d) / 0.12f), impact)
        }

        // Entrance: lines draw themselves in, one after another.
        if (age < INTRO_A) {
            for (i in 0 until count) {
                val start = 0.55f * i / count
                reveal[i] = clamp01((age / INTRO_A - start) / 0.45f)
            }
        } else if (!revealed) {
            reveal.fill(1f)
            revealed = true
        }

        // Gold dust on its slanted ring, each grain on its own orbit.
        val drift = dt * (0.25f + 0.45f * drive)
        for (i in 0 until motes) {
            val o = i * Emblem.MOTE
            moteAngle[i] = (moteAngle[i] + drift * mote[o + 4]) % TWO_PI
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

    /** Ripples out across the disc's plane, one after another, on a kick. Returns the strongest one's opacity. */
    private fun ripple(dt: Float, struck: Boolean): Float {
        if (ripples == 0) return 0f
        if (struck) {
            rippleAge = 0f
        } else if (rippleAge >= 0f) {
            rippleAge += dt
            if (rippleAge > RIPPLE_SECONDS + RIPPLE_GAP * (RIPPLES - 1)) rippleAge = -1f
        }
        var strongest = 0f
        for (k in 0 until ripples) {
            val u = if (rippleAge < 0f) -1f else (rippleAge - k * RIPPLE_GAP) / RIPPLE_SECONDS
            if (u <= 0f || u >= 1f) {
                rippleAlpha[k] = 0f
                continue
            }
            rippleAlpha[k] = (1f - u) * (1f - u) * 0.85f
            strongest = max(strongest, rippleAlpha[k])
            val radius = 0.12f + 1.15f * easeOut(u)
            val base = k * ripplePoints * 2
            for (j in 0 until ripplePoints) {
                toScreen(Emblem.A_MAIN, radius * ringCos[j], radius * ringSin[j], 0.02f, rippleXY, base + j * 2)
            }
        }
        return strongest
    }

    /** On a kick, picks a few grains of gold dust to join into a constellation, which then fades. */
    private fun stars(dt: Float, struck: Boolean) {
        if (constellation.isEmpty()) return
        if (struck) {
            constellationAge = 0f
            val start = (Emblem.scatter(fx.strike * 17 + 3) * motes).toInt().coerceIn(0, motes - 1)
            for (j in constellation.indices) constellation[j] = moteVertex + (start + j * 5) % motes
        } else if (constellationAge >= 0f) {
            constellationAge += dt
            if (constellationAge > CONSTELLATION_SECONDS) constellationAge = -1f
        }
        constellationAlpha = if (constellationAge < 0f) 0f else (1f - clamp01(constellationAge / CONSTELLATION_SECONDS)).pow(1.5f) * 0.8f
    }

    /** Every [COMET_EVERY] seconds a comet crosses behind the disc, from alternate sides. */
    private fun comet(dt: Float) {
        if (!fine) return
        cometClock += dt
        if (cometClock >= COMET_EVERY) {
            cometClock -= COMET_EVERY
            comets++
        }
        val u = cometClock / COMET_SECONDS
        if (comets == 0 || u >= 1f) {
            cometAlpha = 0f
            return
        }
        val side = if (comets % 2 == 0) 1f else -1f
        val fromX = cx - side * 1.9f * unit
        val fromY = cy - (1.1f + 0.5f * Emblem.scatter(comets * 7)) * unit
        val toX = cx + side * 1.7f * unit
        val toY = cy + (-0.2f + 0.6f * Emblem.scatter(comets * 7 + 1)) * unit
        cometX = fromX + (toX - fromX) * u
        cometY = fromY + (toY - fromY) * u
        val dx = toX - fromX
        val dy = toY - fromY
        val length = 0.55f * unit / sqrt(dx * dx + dy * dy)
        cometTailX = -dx * length
        cometTailY = -dy * length
        cometAlpha = smooth(u * 5f) * smooth((1f - u) * 5f) * 0.8f
    }

    // ---- shared ----

    private fun spin(index: Int, by: Float) {
        spins[index] = (spins[index] + by + TWO_PI) % TWO_PI
    }

    /** Moves a damped spring ([s] = position, velocity) toward [goal]: [k] stiffness, [d] damping. Small steps keep it stable whatever the frame time. */
    private fun spring(s: FloatArray, goal: Float, dt: Float, k: Float, d: Float) {
        var left = dt
        while (left > 0f) {
            val h = min(left, 1f / 120f)
            s[1] += ((goal - s[0]) * k - s[1] * d) * h
            s[0] += s[1] * h
            left -= h
        }
    }

    /** Takes whole turns off a spring and its goal together once they run large, so the floats keep their precision. Returns the new goal. */
    private fun rewind(s: FloatArray, goal: Float): Float {
        if (abs(goal) < REWIND) return goal
        val by = if (goal > 0f) REWIND else -REWIND
        s[0] -= by
        return goal - by
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

    /** Sways the vertices that sway: the Sunwheel's leaves, harder while it is still arriving. */
    private fun bend(t: Float, mid: Float) {
        if (swayVertex.isEmpty()) return
        val gust = 1f - clamp01(age / 1.1f)
        val amp = 0.045f + 0.04f * mid + 0.12f * gust
        for (k in swayVertex.indices) {
            val o = k * Emblem.SWAY
            val off = sway[o + 6] * amp * sin(t * 2.4f + sway[o + 7])
            val v = swayVertex[k]
            px[v] = sway[o] + sway[o + 3] * off
            py[v] = sway[o + 1] + sway[o + 4] * off
            pz[v] = sway[o + 2] + sway[o + 5] * off
        }
    }

    /** Slides the moving marks out along their directions by their bands' levels, and by the impact. */
    private fun spectrum(bands: FloatArray) {
        val impact = fx.impact
        for (k in dynVertex.indices) {
            val o = k * Emblem.DYN
            val at = dyn[o + 6] * (bands.size - 1)
            val low = at.toInt().coerceIn(0, bands.size - 2)
            val part = at - low
            val reach = dyn[o + 7] * (bands[low] * (1f - part) + bands[low + 1] * part) + dyn[o + 8] * impact
            val v = dynVertex[k]
            px[v] = dyn[o] + dyn[o + 3] * reach
            py[v] = dyn[o + 1] + dyn[o + 4] * reach
            pz[v] = dyn[o + 2] + dyn[o + 5] * reach
        }
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
            sx[i] = cx + (vx + offsetX) * unit * s
            sy[i] = cy + (vy + offsetY) * unit * s
        }
        val o = coreLayer * 12
        coreScale = CAMERA / (CAMERA - layout[o + 11])
        coreX = cx + (layout[o + 9] + offsetX) * unit * coreScale
        coreY = cy + (layout[o + 10] + offsetY) * unit * coreScale
    }

    /** One point of layer [l]'s own space, onto the screen, stored in [out] at [at]. */
    private fun toScreen(l: Int, x: Float, y: Float, z: Float, out: FloatArray, at: Int) {
        val o = l * 12
        val vx = layout[o] * x + layout[o + 1] * y + layout[o + 2] * z + layout[o + 9]
        val vy = layout[o + 3] * x + layout[o + 4] * y + layout[o + 5] * z + layout[o + 10]
        val vz = layout[o + 6] * x + layout[o + 7] * y + layout[o + 8] * z + layout[o + 11]
        val s = CAMERA / (CAMERA - vz)
        out[at] = cx + (vx + offsetX) * unit * s
        out[at + 1] = cy + (vy + offsetY) * unit * s
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
            // A line still drawing itself in shows in full colour, just shorter; anything else fades in.
            val shown = reveal[i]
            val alpha = materials[m + 1] * fade[i] * (if (kind[i] == Emblem.LINE) (if (shown > 0f) 1f else 0f) else shown)
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
        val halo = 0.75f + 0.45f * bass + 0.50f * impact
        for (c in 0 until glowCount) {
            val o = c * Emblem.GLOW
            glowArgb[c] = argb(glows[o + 4] * halo, glows[o], glows[o + 1], glows[o + 2])
            glowWidth[c] = glows[o + 3] * lineUnit
        }
    }

    /** Things that give off their own light dim as they recede. */
    private fun fog(z: Float): Float = 0.70f + 0.30f * ((z + 0.9f) / 1.3f).coerceIn(0f, 1f)

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    /** Hermite smoothstep of [v] clamped to 0..1. */
    private fun smooth(v: Float): Float {
        val x = clamp01(v)
        return x * x * (3f - 2f * x)
    }

    /** Fast, then settling: cubic ease-out of [v] clamped to 0..1. */
    private fun easeOut(v: Float): Float {
        val q = 1f - clamp01(v)
        return 1f - q * q * q
    }

    /** Past the mark and back, like something landing with a bounce: 0 at 0, 1 at 1, about 1.1 at its peak. */
    private fun backOut(v: Float): Float {
        val q = clamp01(v) - 1f
        return 1f + 2.7f * q * q * q + 1.7f * q * q
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
        const val HALF_PI = 1.5707964f
        const val QUARTER_PI = 0.7853982f
        const val THIRD = 2.0943952f
        const val SIXTH = 1.0471976f

        /** Once [age] reaches this every entrance is over. */
        const val INTRO_DONE = 2f

        /** How long the Astrolabe takes to draw itself in. */
        const val INTRO_A = 1.4f

        /** Turns a spring may run up before [rewind] takes them off. */
        const val REWIND = TWO_PI * 16f

        /** Eclipse: longest the clockwork waits for a kick before it steps anyway, how far a kick jolts it, the core's radius. */
        const val STEP_IDLE = 3.2f
        const val SHAKE = 0.035f
        const val CORE_EDGE = 0.27f

        /** Eclipse slashes: how many, points along each edge, the arc each spans (radians), how long one lasts. */
        const val SLASHES = 2
        const val SLASH_POINTS = 14
        const val SLASH_SPAN = 1.5f
        const val SLASH_SECONDS = 0.45f

        /** Sunwheel: rays, the star's arm length, how strong each afterimage is, and how often a neon outline buzzes. */
        const val RAYS = 12
        const val ARM = 0.56f
        val GHOST_FADE = floatArrayOf(0.30f, 0.17f, 0.08f)
        const val FLICKER = 0.003f
        const val FLICKER_SLOT = 0.07f

        /** Astrolabe: one tick of the disc (6 degrees), ripples, constellation and comet timing. */
        const val TICK = 0.10472f
        const val RIPPLES = 3
        const val RIPPLE_POINTS = 48
        const val RIPPLE_SECONDS = 0.9f
        const val RIPPLE_GAP = 0.12f
        const val CONSTELLATION = 5
        const val CONSTELLATION_SECONDS = 1.1f
        const val COMET_EVERY = 8.5f
        const val COMET_SECONDS = 1.4f

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
