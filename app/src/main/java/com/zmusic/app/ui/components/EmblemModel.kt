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
 * An emblem is a stack of flat layers, laid out once by [EmblemBuilder]. Every vertex is placed
 * by its layer's pose and projected with a perspective divide, and all items are painted far to
 * near. None of them moves like the gyroscope: nothing tumbles, nothing is tipped over like a
 * hoop, and the camera never nods or sways. Each has its own camera, its own way of moving, its
 * own way of being lit, and its own answer to a kick:
 *
 * - **Eclipse** faces you flat and moves like heavy clockwork. The star holds still, then ratchets
 *   a quarter turn on a kick (or after a few quiet seconds) and overshoots as it lands; the flames
 *   trail it on a looser spring and the silver ring clicks a quarter turn the other way. Its depth
 *   is a punch: on a kick the star leaps toward you while the ring is knocked back, the thorns
 *   snap outward, and the whole emblem rolls and shakes. Embers spiral down into the core. Facets
 *   are cel-shaded in two flat tones. A kick also cuts two crescent slashes across it ([slashXY]).
 * - **Sunwheel** is a neon sign, flat-lit. The star spins like a pinwheel, fast as the music is
 *   loud, with afterimages behind it and sparks flying off its tips; spin throws the spears and
 *   leaves outward, the leaves sway, the two rings turn against each other, a level meter fills
 *   between them, and the neon now and then buzzes. Its depth is a flip: every few seconds a kick
 *   turns the whole sign over, a full turn about a line across its face. A kick also sends rays out
 *   of the core ([rayXY]) and throws a burst of sparks.
 * - **Astrolabe** is a hologram of engraved plates stacked in depth. The viewpoint drifts in a slow
 *   loop so the plates slide across one another, and the stack breathes apart with the bass. The
 *   disc ticks like a second hand with its dials geared to it, planets run round flat orbits on
 *   their own plates, a light reads along the runes, gold dust drifts between the plates, and a
 *   comet crosses behind now and then. A kick sends ripples across the disc ([rippleXY]) and joins
 *   some of the dust into a constellation.
 *
 * Each also enters in its own way: the Eclipse's core opens and its star blooms out of it, the
 * Sunwheel spins up from a blur, the Astrolabe draws itself in line by line as its plates part.
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

    /** Per item: how much the spectrum swells it this frame, 1 = as built. */
    private val grow = FloatArray(count) { 1f }
    private val pulseItem = g.pulseItem.trim()
    private val pulse = g.pulse.trim()

    /** Halo classes: class `c` (1 or more) is stroked [glowWidth]`[c - 1]` pixels wide in [glowArgb]`[c - 1]`. */
    private val glows = g.glows.trim()
    val glowCount = glows.size / Emblem.GLOW
    val glowArgb = IntArray(glowCount)
    val glowWidth = FloatArray(glowCount)

    /**
     * The items of each halo class in item order, worked out once so drawing a halo need not
     * look through every item: class `c` (1 or more) is `glowMembers[glowStart[c - 1] until glowStart[c]]`.
     */
    val glowStart = IntArray(glowCount + 1)
    val glowMembers: IntArray = run {
        for (i in 0 until count) if (glow[i] in 1..glowCount) glowStart[glow[i]]++
        for (c in 1..glowCount) glowStart[c] += glowStart[c - 1]
        val members = IntArray(glowStart[glowCount])
        val next = glowStart.copyOf()
        for (i in 0 until count) if (glow[i] in 1..glowCount) members[next[glow[i] - 1]++] = i
        members
    }

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

    /** The light behind the emblem, as RGB: its colour at its brightest, and the colour it fades out through. */
    val backdropInner: Int = when (variant) {
        GyroVariant.Eclipse -> 0xFF2E45
        GyroVariant.Sunwheel -> 0xFFBF26
        else -> 0xFF9E40
    }
    val backdropOuter: Int = when (variant) {
        GyroVariant.Eclipse -> 0x8C1030
        GyroVariant.Sunwheel -> 0xE6660D
        else -> 0x8C59F2
    }

    // ---- camera ----

    /** The Eclipse's shake, in units. */
    private var offsetX = 0f
    private var offsetY = 0f

    /** The Astrolabe's drifting viewpoint: how far across each unit of depth slides. */
    private var shearX = 0f
    private var shearY = 0f

    // ---- Eclipse ----
    private val starTurn = FloatArray(2)
    private var starGoal = 0f
    private val flameTurn = FloatArray(2)
    private val ringTurn = FloatArray(2)
    private var ringGoal = 0f
    private val roll = FloatArray(2)
    private var sinceStep = 0f

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
    private var flipAge = -1f
    private var flipAxis = 0f
    private var sinceFlip = 10f
    private var meter = 0f
    private var peak = 0f

    /** Sunwheel: rays out of the core on a kick, one triangle (6 floats) each in [rayXY]. [rayAlpha] zero means "none". */
    val rays = if (variant == GyroVariant.Sunwheel && fine) RAYS else 0
    val rayXY = FloatArray(rays * 6)
    var rayAlpha = 0f
        private set

    // ---- Astrolabe ----
    private val mainTurn = FloatArray(2)
    private var mainGoal = 0f
    private val dialTurn = FloatArray(Emblem.A_DIALS)
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
            GyroVariant.Eclipse -> eclipse(t, dt, bass, mid, struck)
            GyroVariant.Sunwheel -> sunwheel(t, dt, bass, mid, treble, struck)
            else -> astrolabe(t, dt, bass, treble, struck)
        }
        bend(t, mid)
        spectrum(bands)
        project()
        shade(bass, lineUnit)
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

    private fun eclipse(t: Float, dt: Float, bass: Float, mid: Float, struck: Boolean) {
        val impact = fx.impact
        // Clockwork: the star and the ring hold, then step on a kick, or on their own in quiet music.
        sinceStep += dt
        if (struck || sinceStep > STEP_IDLE) {
            sinceStep = 0f
            starGoal += QUARTER_PI
            ringGoal -= HALF_PI
        }
        spring(starTurn, starGoal, dt, 90f, 11f)
        spring(ringTurn, ringGoal, dt, 70f, 10f)
        // The flames follow the star on a looser spring, so they trail its step and swing past.
        spring(flameTurn, starTurn[0], dt, 30f, 5f)
        val starShift = rewindBy(starGoal)
        starGoal -= starShift
        starTurn[0] -= starShift
        flameTurn[0] -= starShift
        val ringShift = rewindBy(ringGoal)
        ringGoal -= ringShift
        ringTurn[0] -= ringShift
        spin(0, 0.25f * drive * dt)

        // A kick rolls the whole emblem a few degrees, one way or the other, and it springs back...
        if (struck) roll[1] += if (Emblem.scatter(fx.strike * 7 + 1) < 0.5f) ROLL_KICK else -ROLL_KICK
        spring(roll, 0f, dt, 60f, 9f)
        // ...and shakes it. The jolt changes direction every 30 ms and dies with the impact.
        val jolt = (fx.sinceImpact / 0.03f).toInt()
        val shake = impact * impact * SHAKE
        offsetX = shake * (Emblem.scatter(fx.strike * 31 + jolt * 2) - 0.5f) * 2f
        offsetY = shake * (Emblem.scatter(fx.strike * 31 + jolt * 2 + 1) - 0.5f) * 2f

        // Entrance: the core opens, then the star blooms out of it and the ring swings into place.
        val opening = backOut(age / 0.7f)
        val bloom = backOut((age - 0.15f) / 0.7f)
        val settle = easeOut(age / 0.9f)
        // Depth: on a kick the star leaps toward you and the ring is knocked back.
        val punch = PUNCH * impact

        rotZ(stage, roll[0])
        set(0, stage, null, 1f)
        rotZ(body, starTurn[0])
        set(Emblem.E_STAR, stage, body, bloom * (1f + 0.04f * bass), lift = punch)
        rotZ(body, flameTurn[0])
        set(Emblem.E_HOOKS, stage, body, bloom * (1f + 0.08f * mid), lift = 0.05f + 0.8f * punch)
        rotZ(body, spins[0])
        set(Emblem.E_CORE, stage, body, opening * (1f + 0.05f * bass + 0.12f * impact), lift = 0.10f + 0.6f * punch)
        rotZ(body, ringTurn[0] - HALF_PI * (1f - settle))
        set(Emblem.E_SILVER, stage, body, (1.25f - 0.25f * settle) * (1f + 0.03f * bass), lift = -0.04f - 0.5f * punch)
        // The thorns lie flat round the star and tighten on a kick.
        set(Emblem.E_THORN, stage, null, (0.6f + 0.4f * settle) * (1f + 0.05f * bass - 0.08f * impact), lift = -0.02f - 0.3f * punch)
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
            val a = mote[o + 2] + 2.6f * life * life + spins[0]
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

    private fun sunwheel(t: Float, dt: Float, bass: Float, mid: Float, treble: Float, struck: Boolean) {
        val impact = fx.impact
        val turn = drive * dt
        // Entrance: it arrives spinning in a blur and slows to its working speed.
        val spinUp = 1f - clamp01(age / 1.1f)
        // The louder the music, the faster it spins; a kick whips it on for a moment.
        starSpeed = 1.4f + 2.6f * drive + 4f * impact + 16f * spinUp * spinUp
        starAngle = (starAngle + starSpeed * dt) % TWO_PI
        val whirl = clamp01((starSpeed - 1.6f) / 3f)
        spin(0, -0.12f * turn)
        spin(1, 0.18f * turn)
        spin(2, -0.30f * turn)

        // Now and then a kick flips the whole sign over: a full turn about a line across its face.
        sinceFlip += dt
        if (struck && sinceFlip > FLIP_GAP) {
            sinceFlip = 0f
            flipAge = 0f
            flipAxis = TWO_PI * Emblem.scatter(fx.strike * 11 + 3)
        }
        var flip = 0f
        if (flipAge >= 0f) {
            flipAge += dt
            if (flipAge >= FLIP_SECONDS) flipAge = -1f else flip = TWO_PI * smooth(flipAge / FLIP_SECONDS)
        }
        rotZ(ta, flipAxis)
        rotX(tb, flip)
        mul(ta, tb, td)
        rotZ(ta, -flipAxis)
        mul(td, ta, stage)
        set(0, stage, null, 1f)

        val grow = backOut(age / 0.8f)
        val rings = easeOut((age - 0.1f) / 0.8f)
        // Spin throws the spears and leaves outward.
        rotZ(body, spins[0])
        set(Emblem.S_COMPASS, stage, body, grow * (1f + 0.04f * bass + 0.06f * whirl))
        val starScale = grow * (1f + 0.10f * bass + 0.12f * impact)
        rotZ(body, starAngle)
        set(Emblem.S_STAR, stage, body, starScale, lift = 0.14f)
        // Afterimages hang back behind the spin, further the faster it goes.
        val lag = min(0.45f, starSpeed * 0.035f)
        for (k in 0 until Emblem.S_GHOSTS) {
            rotZ(body, starAngle - lag * (k + 1))
            set(Emblem.S_GHOST + k, stage, body, starScale, lift = 0.135f - 0.005f * k)
        }
        // The two rings turn against each other.
        rotZ(body, spins[1])
        set(Emblem.S_OUTER, stage, body, (0.4f + 0.6f * rings) * (1f + 0.03f * bass))
        rotZ(body, spins[2])
        set(Emblem.S_INNER, stage, body, (0.4f + 0.6f * rings) * (1f + 0.03f * bass))
        set(Emblem.S_GAUGE, stage, null, 0.4f + 0.6f * rings)
        set(Emblem.S_MOTES, stage, null, 1f, lift = 0.14f)

        // The meter climbs fast and falls slowly, and its peak hangs, then drifts down.
        val loud = clamp01(0.5f * bass + 0.3f * mid + 0.2f * treble)
        meter += (loud - meter) * min1(dt * (if (loud > meter) 18f else 3f))
        peak = max(peak - dt * 0.35f, meter)

        // Afterimages show only at speed. Treble makes the neon buzz more often.
        val slot = (t / FLICKER_SLOT).toInt()
        val buzz = FLICKER * (0.5f + treble)
        for (i in 0 until count) {
            val l = itemLayer[i]
            fade[i] = if (l >= Emblem.S_GHOST && l < Emblem.S_GHOST + Emblem.S_GHOSTS) {
                GHOST_FADE[l - Emblem.S_GHOST] * whirl
            } else if (l == Emblem.S_GAUGE) {
                gauge(tag[i])
            } else if (fine && glow[i] > 0 && kind[i] == Emblem.LINE && Emblem.scatter(slot * 131 + i * 977) < buzz) {
                0.35f
            } else {
                1f
            }
        }

        // Sparks fly off the arm tips along the spin and curve outward. A kick throws them all at once.
        val rate = dt * (0.8f + 0.5f * drive)
        val bright = clamp01(0.35f + 0.65f * whirl)
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

    /** How lit a meter segment [fromBottom] of the way up is: full below the level, a bright mark at the peak, faint above. */
    private fun gauge(fromBottom: Float): Float {
        val lit = smooth((meter - fromBottom) / 0.04f + 0.5f)
        val off = fromBottom - peak
        val mark = exp(-(off * off) / 0.0006f)
        return 0.16f + 0.84f * max(lit, mark)
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
        val before = mainTurn[0]
        spring(mainTurn, mainGoal, dt, 220f, 17f)
        // The dials are geared to the disc: they turn the other way, faster the smaller they are.
        val moved = mainTurn[0] - before
        for (k in 0 until Emblem.A_DIALS) {
            dialTurn[k] = (dialTurn[k] - moved * Emblem.DIAL_RIM / Emblem.DIAL_RADIUS[k]) % TWO_PI
        }
        val shift = rewindBy(mainGoal)
        mainGoal -= shift
        mainTurn[0] -= shift
        spin(0, -0.10f * turn)
        spin(1, 0.25f * turn)
        spin(2, -0.20f * turn)
        spin(3, 0.12f * turn)
        sweep = (sweep + dt * (0.9f + 0.9f * drive)) % TWO_PI

        // The viewpoint drifts in a slow loop, so the plates slide across one another like a hologram's layers.
        shearX = 0.20f * sin(t * 0.37f)
        shearY = 0.14f * sin(t * 0.29f + 1f)
        // The stack opens out of a single plate as it arrives, then breathes apart with the bass.
        val settle = easeOut(age / INTRO_A)
        val spread = settle * (1f + 0.5f * bass + 0.4f * impact)

        identity(stage)
        set(0, stage, null, 1f)
        rotZ(body, mainTurn[0])
        set(Emblem.A_MAIN, stage, body, 1f, keep = held)
        // The dials ride the disc's rim, a little in front of it.
        for (k in 0 until Emblem.A_DIALS) {
            rotZ(body, dialTurn[k])
            val a = Emblem.DIAL_ANGLE[k]
            set(Emblem.A_DIAL + k, held, body, 1f, Emblem.DIAL_RIM * cos(a), Emblem.DIAL_RIM * sin(a), 0f, lift = 0.10f * spread)
        }
        rotZ(body, spins[0])
        set(Emblem.A_FIGURE, stage, body, 1f, lift = -0.30f * spread)
        rotZ(body, spins[1])
        set(Emblem.A_RING_A, stage, body, 1f + 0.05f * bass, 0.52f, -0.40f, 0.30f * spread + 0.04f * sin(t * 0.9f))
        rotZ(body, spins[2])
        set(Emblem.A_RING_B, stage, body, 1f + 0.05f * bass, -0.62f, 0.58f, -0.26f * spread + 0.04f * sin(t * 0.7f + 1f))
        set(Emblem.A_ORBIT_A, stage, null, 1f, lift = 0.22f * spread)
        set(Emblem.A_ORBIT_B, stage, null, 1f, lift = -0.18f * spread)
        set(Emblem.A_DUST, stage, null, 1f)
        rotZ(body, spins[3])
        set(Emblem.A_BURST, stage, body, 1f + 0.10f * bass + 0.25f * impact, lift = 0.40f * spread)

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

        // Gold dust drifts round between the plates, each grain at its own depth.
        val drift = dt * (0.25f + 0.45f * drive)
        for (i in 0 until motes) {
            val o = i * Emblem.MOTE
            moteAngle[i] = (moteAngle[i] + drift * mote[o + 4]) % TWO_PI
            val v = moteVertex + i
            px[v] = mote[o] * cos(moteAngle[i])
            py[v] = mote[o] * sin(moteAngle[i])
            pz[v] = mote[o + 1] * spread
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

    /** Every [COMET_EVERY] seconds a comet crosses behind the plates, from alternate sides. */
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

    /**
     * Whole turns to take off a spring's goal (and everything that follows it) once it runs
     * large, so the floats keep their precision. 0 until then.
     */
    private fun rewindBy(goal: Float): Float = if (abs(goal) < REWIND) 0f else if (goal > 0f) REWIND else -REWIND

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

    /** Slides the moving marks along their directions by their bands' levels and the impact, and swells the swelling dots. */
    private fun spectrum(bands: FloatArray) {
        val impact = fx.impact
        for (k in dynVertex.indices) {
            val o = k * Emblem.DYN
            val reach = dyn[o + 7] * level(bands, dyn[o + 6]) + dyn[o + 8] * impact
            val v = dynVertex[k]
            px[v] = dyn[o] + dyn[o + 3] * reach
            py[v] = dyn[o + 1] + dyn[o + 4] * reach
            pz[v] = dyn[o + 2] + dyn[o + 5] * reach
        }
        for (k in pulseItem.indices) {
            val o = k * Emblem.PULSE
            grow[pulseItem[k]] = 1f + pulse[o + 1] * level(bands, pulse[o])
        }
    }

    /** The spectrum's level at [position], 0..1 from the lowest band to the highest, blending neighbours. */
    private fun level(bands: FloatArray, position: Float): Float {
        val at = position * (bands.size - 1)
        val low = at.toInt().coerceIn(0, bands.size - 2)
        val part = at - low
        return bands[low] * (1f - part) + bands[low + 1] * part
    }

    /** Places every vertex by its layer's pose, slides it by the viewpoint's drift, and applies the perspective divide. */
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
            sx[i] = cx + (vx + vz * shearX + offsetX) * unit * s
            sy[i] = cy + (vy + vz * shearY + offsetY) * unit * s
        }
        val o = coreLayer * 12
        val z = layout[o + 11]
        coreScale = CAMERA / (CAMERA - z)
        coreX = cx + (layout[o + 9] + z * shearX + offsetX) * unit * coreScale
        coreY = cy + (layout[o + 10] + z * shearY + offsetY) * unit * coreScale
    }

    /** One point of layer [l]'s own space, onto the screen, stored in [out] at [at]. */
    private fun toScreen(l: Int, x: Float, y: Float, z: Float, out: FloatArray, at: Int) {
        val o = l * 12
        val vx = layout[o] * x + layout[o + 1] * y + layout[o + 2] * z + layout[o + 9]
        val vy = layout[o + 3] * x + layout[o + 4] * y + layout[o + 5] * z + layout[o + 10]
        val vz = layout[o + 6] * x + layout[o + 7] * y + layout[o + 8] * z + layout[o + 11]
        val s = CAMERA / (CAMERA - vz)
        out[at] = cx + (vx + vz * shearX + offsetX) * unit * s
        out[at + 1] = cy + (vy + vz * shearY + offsetY) * unit * s
    }

    /**
     * Works out every item's colour, width and depth. A cel-shaded facet takes its shadow or its
     * light tone by which way its true normal faces, with only a narrow blend between the two, so
     * the tones change in steps as the facet turns. Everything else gives off its own light and
     * only dims a little as it recedes.
     */
    private fun shade(bass: Float, lineUnit: Float) {
        val impact = fx.impact
        val bright = min1(0.86f + 0.14f * bass + 0.25f * impact)
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
                if (materials[m] == Emblem.CEL) {
                    val length = sqrt(nx * nx + ny * ny + nz * nz)
                    // Both faces are painted: whichever one is toward the viewer.
                    val flip = if (nz < 0f) -1f else 1f
                    val inverse = if (length > 1e-9f) flip / length else 0f
                    val facing = (nx * LX + ny * LY + nz * LZ) * inverse
                    val light = smooth((facing - CEL_SPLIT) / CEL_SOFT + 0.5f)
                    val dim = 0.82f + 0.18f * ((z + 1.2f) / 2.4f).coerceIn(0f, 1f)
                    r = (materials[m + 2] + (materials[m + 5] - materials[m + 2]) * light) * dim
                    g = (materials[m + 3] + (materials[m + 6] - materials[m + 3]) * light) * dim
                    b = (materials[m + 4] + (materials[m + 7] - materials[m + 4]) * light) * dim
                } else {
                    val level = bright * fog(z)
                    r = materials[m + 2] * level
                    g = materials[m + 3] * level
                    b = materials[m + 4] * level
                }
            } else {
                val head = idx[f]
                val tail = idx[f + n - 1]
                z = (wz[head] + wz[idx[f + n / 2]] + wz[tail]) / 3f
                width[i] = baseWidth[i] * lineUnit * (ws[head] + ws[tail]) * 0.5f * grow[i]
                val level = bright * fog(z)
                r = materials[m + 2] * level
                g = materials[m + 3] * level
                b = materials[m + 4] * level
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

    /** Hermite smoothstep of [v] clamped to 0..1: eases in and out. */
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

    private fun identity(out: FloatArray) {
        out[0] = 1f; out[1] = 0f; out[2] = 0f
        out[3] = 0f; out[4] = 1f; out[5] = 0f
        out[6] = 0f; out[7] = 0f; out[8] = 1f
    }

    private fun rotX(out: FloatArray, angle: Float) {
        val c = cos(angle)
        val s = sin(angle)
        out[0] = 1f; out[1] = 0f; out[2] = 0f
        out[3] = 0f; out[4] = c; out[5] = -s
        out[6] = 0f; out[7] = s; out[8] = c
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

        /** How long the Astrolabe takes to draw itself in and part its plates. */
        const val INTRO_A = 1.4f

        /** Turns a spring may run up before [rewindBy] takes them off. A whole number of turns, so nothing visibly moves. */
        const val REWIND = TWO_PI * 16f

        /**
         * Eclipse: longest the clockwork waits for a kick before it steps anyway; how far a kick
         * throws the star toward you; how hard a kick rolls it (radians a second); how far it
         * shakes it; the core's radius.
         */
        const val STEP_IDLE = 3.2f
        const val PUNCH = 0.45f
        const val ROLL_KICK = 0.9f
        const val SHAKE = 0.035f
        const val CORE_EDGE = 0.27f

        /** Eclipse slashes: how many, points along each edge, the arc each spans (radians), how long one lasts. */
        const val SLASHES = 2
        const val SLASH_POINTS = 14
        const val SLASH_SPAN = 1.5f
        const val SLASH_SECONDS = 0.45f

        /** Where cel shading turns from shadow to light (facing toward the light, -1..1), and how soft the turn is. */
        const val CEL_SPLIT = 0.55f
        const val CEL_SOFT = 0.08f

        /**
         * Sunwheel: rays, the star's arm length, how strong each afterimage is, how often a neon
         * outline buzzes, the shortest wait between flips and how long one takes.
         */
        const val RAYS = 12
        const val ARM = 0.56f
        val GHOST_FADE = floatArrayOf(0.30f, 0.17f, 0.08f)
        const val FLICKER = 0.003f
        const val FLICKER_SLOT = 0.07f
        const val FLIP_GAP = 4.5f
        const val FLIP_SECONDS = 0.8f

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

        // Key light from the upper left, in front. As in GyroModel; here it only decides which cel tone a facet takes.
        const val LX = -0.451f
        const val LY = -0.702f
        const val LZ = 0.551f
    }
}
