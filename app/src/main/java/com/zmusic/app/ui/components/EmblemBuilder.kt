package com.zmusic.app.ui.components

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A float list that grows while an emblem is laid out, then is frozen with [trim]. */
internal class Floats {
    private var a = FloatArray(256)
    var n = 0
        private set

    fun add(v: Float) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }

    operator fun get(i: Int): Float = a[i]

    fun trim(): FloatArray = a.copyOf(n)
}

/** The same for ints. */
internal class Ints {
    private var a = IntArray(256)
    var n = 0
        private set

    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }

    fun clear() {
        n = 0
    }

    fun trim(): IntArray = a.copyOf(n)
}

/** Names for the numbers [EmblemBuilder] and [EmblemModel] share. */
internal object Emblem {
    /** Kinds of item: a filled polygon, a stroked polyline, a round dot. */
    const val POLY = 0
    const val LINE = 1
    const val DOT = 2

    /** Kinds of material: one colour that gives off its own light, or three tones lit from the upper left. */
    const val FLAT = 0f
    const val LIT = 1f

    /** Floats per material (kind, alpha, then shadow, base and light as RGB, then how strong its sheen is), glow class, moving vertex and mote. */
    const val MATERIAL = 12
    const val GLOW = 5
    const val DYN = 8
    const val MOTE = 6

    /** Points round the shockwave ring. */
    const val SHOCK = 48

    // Layers. 0 is always the stage: it carries the shockwave and nothing turns it but the camera.
    const val E_STAR = 1
    const val E_HOOKS = 2
    const val E_CORE = 3
    const val E_SILVER = 4
    const val E_THORN = 5
    const val E_MOTES = 6

    const val S_COMPASS = 1
    const val S_OUTER = 2
    const val S_INNER = 3
    const val S_STAR = 4
    const val S_MOTES = 5

    const val A_MAIN = 1
    const val A_FIGURE = 2
    const val A_DIAL = 3
    const val A_DIALS = 3
    const val A_RING_A = 6
    const val A_RING_B = 7
    const val A_ARM_A = 8
    const val A_ARM_B = 9
    const val A_DUST = 10
    const val A_BURST = 11

    /** Where on the main disc's rim each dial rides, and how big it is. */
    val DIAL_ANGLE = floatArrayOf(-1.5708f, 2.6180f, 0.5236f)
    val DIAL_RADIUS = floatArrayOf(0.150f, 0.170f, 0.115f)

    /** A repeatable scatter value in 0 until 1 for [seed]. */
    fun scatter(seed: Int): Float {
        var x = seed * 747796405 + 1013904223
        x = ((x ushr ((x ushr 28) + 4)) xor x) * 277803737
        x = x xor (x ushr 22)
        return (x ushr 8) / 16777216f
    }
}

/**
 * Lays one emblem out, once. An emblem is a set of flat pieces: filled facets, stroked outlines
 * and dots. Every piece belongs to a layer, a rigid body that [EmblemModel] turns each frame, and
 * is described in that layer's own space: x right, y down, z out of the layer's face.
 *
 * Small sizes ([fine] false) leave out whatever would not read at 44dp: runes, thorns, gauge
 * ticks, sparks and the fine inner outlines.
 */
internal class EmblemBuilder(variant: GyroVariant, private val fine: Boolean) {
    val x = Floats()
    val y = Floats()
    val z = Floats()
    val layer = Ints()

    /** Per item: its kind, where its vertex list starts in [idx] and how long it is, material, glow class, depth bias and width. */
    val kind = Ints()
    val first = Ints()
    val size = Ints()
    val mat = Ints()
    val glow = Ints()
    val bias = Floats()
    val width = Floats()
    val idx = Ints()

    val materials = Floats()
    val glows = Floats()

    /** Vertices the spectrum moves: each slides from its base along a direction by its band's level. */
    val dynVertex = Ints()
    val dyn = Floats()

    /** Sparks: one vertex and one dot each, starting at [moteVertex] and [moteItem]. */
    val mote = Floats()
    var moteVertex = 0
    var moteItem = 0
    var motes = 0

    var shockItem = 0
    var shockVertex = 0
    var layers = 1

    // Where the next shapes go: which layer, turned how far about its centre, how far off its face.
    private var at = 0
    private var dirX = 1f
    private var dirY = 0f
    private var lift = 0f

    init {
        when (variant) {
            GyroVariant.Eclipse -> eclipse()
            GyroVariant.Sunwheel -> sunwheel()
            GyroVariant.Astrolabe -> astrolabe()
            GyroVariant.Gyroscope -> throw IllegalArgumentException("GyroModel draws the gyroscope")
        }
    }

    /**
     * A crimson compass star with a ridge down every blade, flames curling round a dark core
     * whose rim is a ring of ice shards, a broken silver ring carrying four faceted compass
     * points, and a hoop of thorns.
     */
    private fun eclipse() {
        layers = 7
        val blade = lit(0.36f, 0.01f, 0.06f, 0.90f, 0.06f, 0.15f, 1.00f, 0.30f, 0.30f, sheen = 0.35f)
        val neon = flat(1.00f, 0.13f, 0.22f)
        val silver = lit(0.34f, 0.36f, 0.50f, 0.80f, 0.83f, 0.93f, 1.00f, 1.00f, 1.00f)
        val white = flat(0.95f, 0.97f, 1.00f)
        val steel = lit(0.14f, 0.14f, 0.20f, 0.42f, 0.44f, 0.56f, 0.82f, 0.86f, 0.96f)
        val dark = flat(0.025f, 0.03f, 0.10f)
        val ice = flat(0.80f, 0.91f, 1.00f)
        val wire = flat(0.50f, 0.52f, 0.64f)
        val ember = flat(1.00f, 0.42f, 0.36f)
        val rim = flat(0.18f, 0.22f, 0.55f)
        val red = glowClass(1.00f, 0.10f, 0.20f, 9f, 0.24f)
        val pale = glowClass(0.62f, 0.76f, 1.00f, 8f, 0.20f)
        shock(flat(1.00f, 0.55f, 0.60f))

        for (k in 0 until 4) {
            val a = k * HALF_PI
            place(Emblem.E_STAR, a)
            kite(blade, 0.20f, 0.40f, 0.90f, 0.105f, 0.065f, red)
            if (fine) {
                // A diamond on the axis where the blade crosses the ring, then a needle beyond it.
                val d0 = v(0.86f, 0f)
                line(neon, 1.4f, d0, v(0.975f, -0.05f), v(1.10f, 0f), v(0.975f, 0.05f), d0, glow = red, bias = 0.01f)
                poly(blade, v(0.925f, 0f), v(0.975f, -0.022f), v(1.03f, 0f), v(0.975f, 0.022f))
                poly(neon, v(1.08f, -0.014f), v(1.36f, 0f), v(1.08f, 0.014f), glow = red)
            }
            // Two slivers on the diagonal, leaning apart so they catch the light in turn.
            place(Emblem.E_STAR, a + QUARTER_PI)
            poly(blade, v(0.30f, -0.062f), v(0.72f, -0.018f), v(0.30f, -0.012f, 0.03f))
            poly(blade, v(0.30f, 0.012f, 0.03f), v(0.72f, 0.018f), v(0.30f, 0.062f))
        }

        // Flames: two horns in each quadrant, sweeping from beside a blade toward the diagonal.
        val steps = 7
        for (k in 0 until 4) {
            place(Emblem.E_HOOKS, k * HALF_PI + QUARTER_PI)
            for (side in -1..1 step 2) {
                val horn = IntArray(steps * 2)
                for (j in 0 until steps) {
                    val s = j / (steps - 1f)
                    val angle = side * (0.70f - 0.62f * s)
                    val radius = 0.34f + 0.24f * s * s
                    val thick = 0.085f * (1f - s).pow(0.8f)
                    horn[j] = polar(radius + thick / 2f, angle)
                    horn[steps * 2 - 1 - j] = polar(radius - thick / 2f, angle)
                }
                poly(neon, *horn, glow = red)
            }
        }

        place(Emblem.E_CORE)
        poly(dark, *IntArray(40) { polar(0.265f, it * TWO_PI / 40) })
        circle(rim, 1.2f, 0.265f, 40, bias = 0.004f)
        val shards = if (fine) 26 else 12
        for (k in 0 until shards) {
            val a = (k + 0.5f * Emblem.scatter(k * 3 + 1)) * TWO_PI / shards
            val half = 0.045f + 0.045f * Emblem.scatter(k * 3 + 2)
            val length = 0.03f + 0.035f * Emblem.scatter(k * 3 + 3)
            val skew = (Emblem.scatter(k * 3 + 4) - 0.5f) * 0.06f
            place(Emblem.E_CORE, a, 0.012f)
            val outer = v(0.262f + length, 0.262f * sin(half * 0.55f + skew))
            val inner = v(0.262f + length * 0.8f, 0.262f * sin(-half * 0.6f + skew))
            poly(ice, polar(0.262f, -half), polar(0.262f, half), outer, inner, glow = pale)
            dynamic(outer, mirror(k, shards), 0.11f)
            dynamic(inner, mirror(k, shards), 0.09f)
        }

        for (k in 0 until 4) {
            val diagonal = k * HALF_PI + QUARTER_PI
            place(Emblem.E_SILVER)
            band(white, 0.972f, 1.03f, diagonal + 0.16f, diagonal + 0.60f, 8, pale)
            band(white, 0.972f, 1.03f, diagonal - 0.60f, diagonal - 0.16f, 8, pale)
            // A compass point: eight facets round a raised centre.
            place(Emblem.E_SILVER, diagonal)
            val centre = v(1.0f, 0f, 0.05f)
            val points = intArrayOf(
                v(1.25f, 0f), v(1.03f, 0.03f), v(1.0f, 0.125f), v(0.97f, 0.03f),
                v(0.78f, 0f), v(0.97f, -0.03f), v(1.0f, -0.125f), v(1.03f, -0.03f),
            )
            for (j in 0 until 8) poly(silver, centre, points[j], points[(j + 1) % 8], glow = pale)
            if (fine) poly(white, v(1.07f, 0f, 0.052f), v(1.0f, 0.032f, 0.052f), v(0.93f, 0f, 0.052f), v(1.0f, -0.032f, 0.052f), bias = 0.02f)
        }
        if (fine) {
            // The dotted circle outside the ring: each dot grows into a tick with its band.
            val ticks = 64
            for (k in 0 until ticks) {
                place(Emblem.E_SILVER, (k + 0.5f) * TWO_PI / ticks)
                val end = v(1.128f, 0f)
                line(wire, 1.3f, v(1.12f, 0f), end)
                dynamic(end, mirror(k, ticks), 0.15f)
            }
        }

        place(Emblem.E_THORN)
        circle(wire, 1.3f, 0.90f, if (fine) 72 else 36, chunks = if (fine) 12 else 6)
        if (fine) {
            circle(wire, 1.0f, 0.865f, 72, chunks = 12)
            val thorns = 24
            for (k in 0 until thorns) {
                place(Emblem.E_THORN, k * TWO_PI / thorns)
                val out = if (k % 2 == 0) 1f else -1f
                poly(steel, v(0.882f, -0.05f), v(0.882f, 0.05f), v(0.882f + 0.17f * out, 0.09f * out, 0.04f * out))
            }
        }
        if (fine) motes(Emblem.E_MOTES, ember, 18, 1.15f, 1.60f, PI.toFloat(), 1.0f, 2.2f)
    }

    /**
     * Gold neon. A solid three-armed star spins at the centre of a double ring; three spear
     * heads and three pairs of leaves stand between them, drawn in outline over a wash of gold glass.
     */
    private fun sunwheel() {
        layers = 6
        val gold = flat(1.00f, 0.88f, 0.36f)
        val metal = lit(0.58f, 0.34f, 0.02f, 1.00f, 0.78f, 0.12f, 1.00f, 0.97f, 0.72f)
        val glass = lit(0.62f, 0.36f, 0.03f, 1.00f, 0.72f, 0.10f, 1.00f, 0.88f, 0.40f, alpha = 0.30f, sheen = 0f)
        val hot = flat(1.00f, 0.97f, 0.80f)
        val dim = flat(0.86f, 0.62f, 0.16f)
        val spark = flat(1.00f, 0.86f, 0.46f)
        val amber = glowClass(1.00f, 0.66f, 0.06f, 9f, 0.24f)
        shock(flat(1.00f, 0.85f, 0.45f))

        for (k in 0 until 3) {
            // A spear head: curved edges out to its shoulders, then in to the point.
            place(Emblem.S_COMPASS, k * THIRD)
            val top = v(0.92f, 0f, 0.08f)
            val outline = Ints()
            for (side in -1..1 step 2) {
                val s = side.toFloat()
                val shoulder = curve(0.50f, 0f, 0.74f, 0.045f * s, 0.90f, 0.17f * s, 5)
                val point = curve(0.90f, 0.17f * s, 1.10f, 0.05f * s, 1.56f, 0f, 6)
                poly(glass, *shoulder, top)
                poly(glass, *point, top)
                if (side < 0) {
                    for (i in shoulder) outline.add(i)
                    for (i in point) outline.add(i)
                } else {
                    for (i in point.reversedArray()) outline.add(i)
                    for (i in shoulder.reversedArray()) outline.add(i)
                }
            }
            line(gold, 2.0f, *outline.trim(), glow = amber)
            if (fine) {
                val d0 = v(0.76f, 0f, 0.03f)
                line(hot, 1.2f, d0, v(0.92f, -0.07f, 0.03f), v(1.16f, 0f, 0.03f), v(0.92f, 0.07f, 0.03f), d0, bias = 0.02f)
            }
            // A pair of leaves, one inside the rings and one outside.
            place(Emblem.S_COMPASS, k * THIRD + SIXTH)
            leaf(gold, glass, hot, 0.36f, 0.80f, 0.078f, amber)
            leaf(gold, glass, hot, 0.88f, 1.32f, 0.078f, amber)
        }

        // The double ring, with a gauge in the gap: the spectrum, as ticks hanging off the outer one.
        place(Emblem.S_OUTER)
        circle(gold, 2.2f, 1.0f, if (fine) 96 else 48, chunks = 12, glow = amber)
        circle(gold, 1.8f, 0.89f, if (fine) 96 else 48, chunks = 12, glow = amber)
        if (fine) {
            val ticks = 60
            for (k in 0 until ticks) {
                place(Emblem.S_OUTER, (k + 0.5f) * TWO_PI / ticks)
                val end = v(0.966f, 0f)
                line(dim, 1.3f, v(0.984f, 0f), end)
                dynamic(end, mirror(k, ticks), -0.055f)
            }
        }
        // A third, thinner ring inside them. This is the one that tumbles.
        place(Emblem.S_INNER)
        circle(gold, 1.6f, 0.78f, if (fine) 80 else 40, chunks = 10, glow = amber)

        for (k in 0 until 3) {
            // One arm of the star: two facets falling away from a raised centre.
            place(Emblem.S_STAR, k * THIRD + SIXTH)
            val centre = v(0f, 0f, 0.11f)
            for (side in -1..1 step 2) {
                poly(metal, *curve(0.56f, 0f, 0.20f, 0.045f * side, 0.06f, 0.104f * side, 6), centre, glow = amber)
            }
        }
        if (fine) motes(Emblem.S_MOTES, spark, 16, 1.10f, 1.60f, PI.toFloat(), 1.0f, 2.4f)
    }

    /**
     * Pale line work. A ruled disc with a band of runes and three dials riding its rim, two
     * smaller discs floating off-centre in front of and behind it, a triangle and three long
     * chords, two armillary rings tumbling round a starburst, and a slanted ring of gold dust.
     */
    private fun astrolabe() {
        layers = 12
        val pale = flat(0.88f, 0.93f, 1.00f)
        val dim = flat(0.52f, 0.62f, 0.88f)
        val mint = flat(0.60f, 1.00f, 0.82f)
        val dust = flat(1.00f, 0.80f, 0.38f)
        val violet = flat(0.82f, 0.68f, 1.00f)
        val warm = flat(1.00f, 0.90f, 0.70f)
        val blue = glowClass(0.38f, 0.56f, 1.00f, 8f, 0.22f)
        val green = glowClass(0.25f, 1.00f, 0.66f, 7f, 0.18f)
        val purple = glowClass(0.64f, 0.40f, 1.00f, 8f, 0.22f)
        val orange = glowClass(1.00f, 0.56f, 0.16f, 7f, 0.24f)
        shock(flat(0.75f, 0.85f, 1.00f))

        place(Emblem.A_MAIN)
        circle(pale, 1.8f, 1.00f, if (fine) 96 else 48, chunks = 8, glow = blue)
        circle(pale, 1.2f, 0.90f, if (fine) 96 else 48, chunks = 8, glow = blue)
        if (fine) {
            circle(dim, 1.0f, 0.74f, 72, chunks = 6)
            val runes = 24
            for (k in 0 until runes) {
                place(Emblem.A_MAIN, (k + 0.5f) * TWO_PI / runes)
                glyph((k * 5 + 2) % GLYPHS.size, 0.95f, 0f, 0.030f, 0.036f, pale, 0.9f)
            }
            // The degree scale, standing on the inner circle: the spectrum.
            val ticks = 72
            for (k in 0 until ticks) {
                place(Emblem.A_MAIN, (k + 0.5f) * TWO_PI / ticks)
                val end = v(0.765f, 0f)
                line(dim, 1.2f, v(0.745f, 0f), end)
                dynamic(end, mirror(k, ticks), 0.115f)
            }
        }

        // The figure behind the disc: a triangle, and three chords that run on past the rim.
        place(Emblem.A_FIGURE)
        for (k in 0 until 3) {
            val from = -HALF_PI + k * THIRD
            line(mint, 1.2f, polar(0.74f, from), polar(0.74f, from + THIRD), glow = green)
        }
        if (fine) {
            for (k in 0 until 3) {
                place(Emblem.A_FIGURE, 0.31f + k * SIXTH)
                line(dim, 0.9f, v(-1.24f, 0f), v(1.24f, 0f))
                if (k == 0) {
                    line(dim, 0.9f, v(1.16f, -0.05f), v(1.24f, 0f), v(1.16f, 0.05f))
                } else {
                    for (j in 0 until 3) {
                        val a = j * SIXTH
                        line(dim, 0.9f, v(1.24f - 0.05f * cos(a), -0.05f * sin(a)), v(1.24f + 0.05f * cos(a), 0.05f * sin(a)))
                    }
                }
            }
        }

        for (k in 0 until Emblem.A_DIALS) {
            val l = Emblem.A_DIAL + k
            val r = Emblem.DIAL_RADIUS[k]
            place(l)
            circle(pale, 1.4f, r, 28, glow = blue)
            if (fine) {
                circle(dim, 1.0f, r * 0.66f, 20)
                glyph(k * 3 + 1, 0f, 0f, r * 0.36f, r * 0.36f, pale, 1.1f)
                for (j in 0 until 8) {
                    place(l, j * TWO_PI / 8)
                    line(dim, 1.0f, v(r * 0.76f, 0f), v(r * 0.92f, 0f))
                }
            }
        }

        place(Emblem.A_RING_A)
        circle(pale, 1.5f, 0.40f, 48, chunks = 4, glow = blue)
        if (fine) {
            circle(dim, 1.0f, 0.34f, 40)
            for (j in 0 until 12) {
                place(Emblem.A_RING_A, j * TWO_PI / 12)
                line(dim, 1.0f, v(0.34f, 0f), v(0.375f, 0f))
            }
            place(Emblem.A_RING_A, 0.9f)
            circle(pale, 1.1f, 0.07f, 16, cu = 0.40f)
            line(dim, 0.9f, v(-0.34f, 0f), v(0.34f, 0f))
            line(dim, 0.9f, v(0f, -0.34f), v(0f, 0.34f))
        }

        place(Emblem.A_RING_B)
        circle(pale, 1.5f, 0.36f, 48, chunks = 4, glow = blue)
        if (fine) {
            circle(dim, 1.0f, 0.30f, 40)
            circle(pale, 1.1f, 0.15f, 24)
            for (j in 0 until 6) {
                place(Emblem.A_RING_B, j * SIXTH)
                line(dim, 1.0f, v(0.15f, 0f), v(0.30f, 0f))
            }
            place(Emblem.A_RING_B)
            glyph(5, 0f, 0f, 0.07f, 0.07f, pale, 1.0f)
        }

        place(Emblem.A_ARM_A)
        circle(violet, 1.6f, 0.52f, if (fine) 64 else 32, chunks = 8, glow = purple)
        place(Emblem.A_ARM_B)
        circle(violet, 1.3f, 0.42f, if (fine) 56 else 28, chunks = 7, glow = purple)

        // The starburst: rays spread evenly over a sphere, each one flickering with its own band.
        val rays = if (fine) 22 else 10
        for (k in 0 until rays) {
            val dz = 1f - 2f * (k + 0.5f) / rays
            val across = sqrt(1f - dz * dz)
            val a = k * 2.39996f
            val dx = across * cos(a)
            val dy = across * sin(a)
            val length = 0.14f + 0.20f * Emblem.scatter(k * 5 + 1)
            val end = vertex(Emblem.A_BURST, dx * length, dy * length, dz * length)
            line(warm, 1.1f, vertex(Emblem.A_BURST, dx * 0.04f, dy * 0.04f, dz * 0.04f), end, glow = orange)
            dynamic(end, Emblem.scatter(k * 5 + 2), 0.42f, dx, dy, dz)
        }
        if (fine) motes(Emblem.A_DUST, dust, 56, 0.70f, 1.48f, 0.20f, 0.9f, 2.6f)
    }

    // ---- shapes ----

    /** Points the next shapes along [angle] in [layer], [z] off its face. */
    private fun place(layer: Int, angle: Float = 0f, z: Float = 0f) {
        at = layer
        dirX = cos(angle)
        dirY = sin(angle)
        lift = z
    }

    /** A vertex [u] out along the current direction and [w] across it. */
    private fun v(u: Float, w: Float, dz: Float = 0f): Int = vertex(at, u * dirX - w * dirY, u * dirY + w * dirX, lift + dz)

    private fun polar(radius: Float, angle: Float): Int = v(radius * cos(angle), radius * sin(angle))

    private fun vertex(l: Int, px: Float, py: Float, pz: Float): Int {
        x.add(px)
        y.add(py)
        z.add(pz)
        layer.add(l)
        return x.n - 1
    }

    private fun item(k: Int, m: Int, w: Float, g: Int, b: Float, vertices: IntArray): Int {
        kind.add(k)
        first.add(idx.n)
        size.add(vertices.size)
        mat.add(m)
        glow.add(g)
        bias.add(b)
        width.add(w)
        for (i in vertices) idx.add(i)
        return kind.n - 1
    }

    private fun poly(m: Int, vararg vertices: Int, glow: Int = 0, bias: Float = 0f) = item(Emblem.POLY, m, 0f, glow, bias, vertices)

    private fun line(m: Int, w: Float, vararg vertices: Int, glow: Int = 0, bias: Float = 0f) = item(Emblem.LINE, m, w, glow, bias, vertices)

    /**
     * A circle of [n] sides round the point ([cu], [cw]), cut into [chunks] strokes that share
     * their end points, so each one can be sorted by depth on its own.
     */
    private fun circle(m: Int, w: Float, r: Float, n: Int, chunks: Int = 1, glow: Int = 0, bias: Float = 0f, cu: Float = 0f, cw: Float = 0f) {
        val ring = IntArray(n + 1)
        for (k in 0 until n) {
            val a = k * TWO_PI / n
            ring[k] = v(cu + r * cos(a), cw + r * sin(a))
        }
        ring[n] = ring[0]
        val per = n / chunks
        for (c in 0 until chunks) {
            val to = if (c == chunks - 1) n else (c + 1) * per
            line(m, w, *ring.copyOfRange(c * per, to + 1), glow = glow, bias = bias)
        }
    }

    /** Part of a flat ring, between two radii and two angles, as one polygon. */
    private fun band(m: Int, inner: Float, outer: Float, from: Float, to: Float, n: Int, glow: Int) {
        val edge = IntArray(2 * (n + 1))
        for (k in 0..n) {
            val a = from + (to - from) * k / n
            edge[k] = polar(outer, a)
            edge[2 * n + 1 - k] = polar(inner, a)
        }
        poly(m, *edge, glow = glow)
    }

    /**
     * A blade along the current direction. It starts at [r0], widens to [half] either side at
     * [rw] and runs to a point at [r1]. A ridge [ridge] high down the middle splits it into four
     * flat facets, which is what makes it catch the light as it turns.
     */
    private fun kite(m: Int, r0: Float, rw: Float, r1: Float, half: Float, ridge: Float, glow: Int) {
        val root = v(r0, 0f)
        val tip = v(r1, 0f)
        val left = v(rw, -half)
        val right = v(rw, half)
        val top = v(rw, 0f, ridge)
        poly(m, root, left, top, glow = glow)
        poly(m, left, tip, top, glow = glow)
        poly(m, root, top, right, glow = glow)
        poly(m, top, tip, right, glow = glow)
    }

    /** A leaf from [r0] to [r1], pointed at both ends: an outline over two facets of glass, with a smaller leaf inside. */
    private fun leaf(outline: Int, fill: Int, inner: Int, r0: Float, r1: Float, half: Float, glow: Int) {
        val n = 8
        val left = IntArray(n + 1)
        val right = IntArray(n + 1)
        for (k in 0..n) {
            val s = k / n.toFloat()
            val bulge = half * max(0f, sin(PI.toFloat() * s)).pow(0.85f)
            left[k] = v(r0 + (r1 - r0) * s, -bulge)
            right[k] = v(r0 + (r1 - r0) * s, bulge)
        }
        val top = v((r0 + r1) / 2f, 0f, 0.05f)
        poly(fill, *left, top)
        poly(fill, *right, top)
        line(outline, 1.8f, *left, *right.reversedArray(), glow = glow)
        if (!fine) return
        val small = IntArray(2 * n + 1)
        val mid = (r0 + r1) / 2f
        for (k in 0..2 * n) {
            val s = (if (k <= n) k else 2 * n - k) / n.toFloat()
            val bulge = half * 0.45f * max(0f, sin(PI.toFloat() * s)).pow(0.85f)
            small[k] = v(mid + (r1 - r0) * 0.5f * (s - 0.5f), if (k <= n) -bulge else bulge, 0.03f)
        }
        line(inner, 1.0f, *small, bias = 0.02f)
    }

    /** Vertices along a curve from ([u0], [w0]) to ([u1], [w1]) that bends toward ([cu], [cw]). Both ends are included. */
    private fun curve(u0: Float, w0: Float, cu: Float, cw: Float, u1: Float, w1: Float, n: Int): IntArray = IntArray(n + 1) { k ->
        val s = k / n.toFloat()
        val q = 1f - s
        v(q * q * u0 + 2f * q * s * cu + s * s * u1, q * q * w0 + 2f * q * s * cw + s * s * w1)
    }

    /**
     * One of the made-up signs, centred on ([cu], [cw]): [su] tall along the current direction
     * (its top points outward) and [sw] wide across it.
     */
    private fun glyph(index: Int, cu: Float, cw: Float, su: Float, sw: Float, m: Int, w: Float) {
        val strokes = GLYPHS[index % GLYPHS.size]
        val pen = Ints()
        var k = 0
        while (k <= strokes.size) {
            if (k == strokes.size || strokes[k] == UP) {
                if (pen.n > 1) line(m, w, *pen.trim())
                pen.clear()
                k++
            } else {
                pen.add(v(cu - strokes[k + 1] * su, cw + strokes[k] * sw))
                k += 2
            }
        }
    }

    /** The shockwave: a ring on the stage that the model resizes and fades each frame. */
    private fun shock(m: Int) {
        place(0)
        shockVertex = x.n
        val ring = IntArray(Emblem.SHOCK + 1)
        for (k in 0 until Emblem.SHOCK) ring[k] = polar(1f, k * TWO_PI / Emblem.SHOCK)
        ring[Emblem.SHOCK] = ring[0]
        shockItem = line(m, 1.6f, *ring)
    }

    /**
     * [n] sparks in [layer], each on its own circular orbit: a radius between [near] and [far],
     * tipped up to [spread] radians out of the layer's face.
     */
    private fun motes(layer: Int, m: Int, n: Int, near: Float, far: Float, spread: Float, small: Float, big: Float) {
        moteVertex = x.n
        moteItem = kind.n
        motes = n
        for (i in 0 until n) {
            item(Emblem.DOT, m, small + (big - small) * Emblem.scatter(i * 9 + 1).pow(2f), 0, 0f, intArrayOf(vertex(layer, 0f, 0f, 0f)))
            mote.add(near + (far - near) * Emblem.scatter(i * 9 + 2))
            mote.add(spread * (Emblem.scatter(i * 9 + 3) - 0.5f) * 2f)
            mote.add(TWO_PI * Emblem.scatter(i * 9 + 4))
            mote.add(TWO_PI * Emblem.scatter(i * 9 + 5))
            mote.add(0.6f + 0.8f * Emblem.scatter(i * 9 + 6))
            mote.add(2f + 5f * Emblem.scatter(i * 9 + 7))
        }
    }

    /** Lets the spectrum slide [vertex] from where it is, [gain] times its band's level, along the current direction (or the one given). */
    private fun dynamic(vertex: Int, band: Float, gain: Float, dx: Float = dirX, dy: Float = dirY, dz: Float = 0f) {
        dynVertex.add(vertex)
        dyn.add(x[vertex])
        dyn.add(y[vertex])
        dyn.add(z[vertex])
        dyn.add(dx)
        dyn.add(dy)
        dyn.add(dz)
        dyn.add(band)
        dyn.add(gain)
    }

    /** Where mark [k] of [n] sits in the spectrum, 0..1: low bands at the start of the circle, mirrored on its second half. */
    private fun mirror(k: Int, n: Int): Float = (if (k < n / 2) k else n - k) / (n / 2f)

    private fun lit(
        sr: Float, sg: Float, sb: Float,
        br: Float, bg: Float, bb: Float,
        lr: Float, lg: Float, lb: Float,
        alpha: Float = 1f,
        sheen: Float = 1f,
    ): Int = material(Emblem.LIT, alpha, sr, sg, sb, br, bg, bb, lr, lg, lb, sheen)

    private fun flat(r: Float, g: Float, b: Float): Int = material(Emblem.FLAT, 1f, r, g, b, r, g, b, r, g, b, 0f)

    private fun material(vararg values: Float): Int {
        for (value in values) materials.add(value)
        return materials.n / Emblem.MATERIAL - 1
    }

    /** A halo colour for outlines. Returns its class, which is 1 or more: 0 means "no halo". */
    private fun glowClass(r: Float, g: Float, b: Float, width: Float, alpha: Float): Int {
        glows.add(r)
        glows.add(g)
        glows.add(b)
        glows.add(width)
        glows.add(alpha)
        return glows.n / Emblem.GLOW
    }

    private companion object {
        const val TWO_PI = 6.2831855f
        const val HALF_PI = 1.5707964f
        const val QUARTER_PI = 0.7853982f
        const val THIRD = 2.0943952f
        const val SIXTH = 1.0471976f

        /** Lifts the pen in a glyph. */
        const val UP = -9f

        /** Eight made-up signs. Each is a run of (across, down) points in -1..1, joined in order; [UP] starts a new stroke. */
        val GLYPHS = arrayOf(
            floatArrayOf(0f, -1f, 0.6f, -0.4f, 0f, 0.2f, -0.6f, -0.4f, 0f, -1f, UP, 0f, 0.2f, 0f, 1f, UP, -0.5f, 0.6f, 0.5f, 0.6f),
            floatArrayOf(0.5f, -1f, -0.4f, -0.5f, -0.4f, 0.5f, 0.5f, 1f, UP, 0.5f, -1f, 0.1f, 0f, 0.5f, 1f),
            floatArrayOf(-0.7f, -0.8f, -0.7f, 0f, 0f, 0.4f, 0.7f, 0f, 0.7f, -0.8f, UP, 0f, -1f, 0f, 1f),
            floatArrayOf(-0.7f, 0.7f, 0.7f, -0.7f, UP, 0f, -0.7f, 0.7f, -0.7f, 0.7f, 0f),
            floatArrayOf(-0.8f, -0.5f, -0.3f, 0.5f, 0.2f, -0.5f, 0.7f, 0.5f),
            floatArrayOf(-0.6f, -0.9f, 0.6f, -0.9f, -0.6f, 0.9f, 0.6f, 0.9f, -0.6f, -0.9f),
            floatArrayOf(-0.6f, -1f, -0.6f, 1f, UP, 0.6f, -1f, 0.6f, 1f, UP, -0.6f, 0f, 0.6f, 0f),
            floatArrayOf(0f, -0.9f, 0.8f, 0.7f, -0.8f, 0.7f, 0f, -0.9f, UP, 0f, -0.2f, 0f, 0.3f),
        )
    }
}
