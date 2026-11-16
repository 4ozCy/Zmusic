package com.zmusic.app.ui.welcome

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The welcome screen's timeline as plain functions of the time since it appeared, in ms.
 * No Compose here, so the choreography can be unit tested and every frame is just arithmetic.
 *
 * Beats:
 * 1. The logo sits exactly where the system splash drew it, so the hand-off is invisible.
 * 2. The bars dip like a key being pressed, from the middle outwards, and spring back up.
 *    As the middle bar lands, two amber rings ripple out from it.
 * 3. The logo lifts and shrinks, the bars keep moving like a level meter, and the words rise in.
 * 4. Everything fades and grows slightly while the app shows through.
 */
internal object WelcomeMotion {
    /** The logo holds still this long so it reads as the splash icon before it moves. */
    const val HOLD = 140L

    /** When the screen starts to leave by itself. A tap brings this forward. */
    const val EXIT_AT = 2700L

    /** How long leaving takes. */
    const val EXIT = 420L

    /** Bar centres and half heights in the 108-unit launcher icon viewport (ic_launcher_foreground). */
    val BAR_X = floatArrayOf(30f, 42f, 54f, 66f, 78f)
    val BAR_HALF = floatArrayOf(7f, 15f, 26f, 16f, 7f)
    const val BAR_STROKE = 7f
    const val CENTER_BAR = 2
    const val VIEWPORT = 108f

    private const val DIP_STAGGER = 55L
    private const val DIP_DOWN = 150L
    private const val DIP_DEPTH = 0.32f
    private const val LIFT_AT = 560L
    private const val CAPTION_AT = 860L
    private const val LETTER_AT = 980L
    private const val LETTER_STAGGER = 55L
    private const val RULE_AT = 1400L
    private const val RIPPLE = 950L
    private const val RIPPLE_GAP = 170L

    // Each bar keeps moving at its own rate afterwards, so the meter never looks like a loop.
    private val WAVE_HZ = floatArrayOf(1.7f, 2.3f, 1.3f, 2.1f, 1.9f)
    private val WAVE_PHASE = floatArrayOf(0.4f, 2.1f, 0f, 3.7f, 1.2f)
    private const val WAVE_AMP = 0.17f

    fun progress(t: Long, start: Long, duration: Long): Float =
        ((t - start).toFloat() / duration).coerceIn(0f, 1f)

    /**
     * Step response of a damped spring, 0 at [ms] = 0 heading to 1. [damping] under 1 overshoots.
     * Written out instead of using Compose springs so every value comes from one clock.
     */
    fun spring(ms: Long, damping: Float, omega: Float): Float {
        if (ms <= 0) return 0f
        val t = ms / 1000f
        if (damping >= 1f) {
            // Critically damped: no overshoot.
            return 1f - exp(-omega * t) * (1f + omega * t)
        }
        val wd = omega * sqrt(1f - damping * damping)
        val decay = exp(-damping * omega * t)
        return 1f - decay * (cos(wd * t) + damping * omega / wd * sin(wd * t))
    }

    /** When bar [i] starts its dip: the middle first, then outwards. */
    fun dipStart(i: Int): Long = HOLD + abs(i - CENTER_BAR) * DIP_STAGGER

    /** When the middle bar bottoms out and springs back up. The rings start here. */
    val landAt: Long get() = dipStart(CENTER_BAR) + DIP_DOWN

    /** Height of bar [i] as a multiple of its height in the icon. Exactly 1 until the hold ends. */
    fun barScale(i: Int, t: Long, still: Boolean = false): Float {
        if (still) return 1f
        val local = t - dipStart(i)
        val dip = when {
            local <= 0 -> 1f
            local < DIP_DOWN -> {
                val p = local.toFloat() / DIP_DOWN
                1f - (1f - DIP_DEPTH) * p * p
            }
            else -> DIP_DEPTH + (1f - DIP_DEPTH) * spring(local - DIP_DOWN, damping = 0.42f, omega = 19f)
        }
        val amp = WAVE_AMP * progress(t, 1000L, 600L)
        val wave = sin(t / 1000f * 2f * PI.toFloat() * WAVE_HZ[i] + WAVE_PHASE[i])
        return dip * (1f + amp * wave)
    }

    /** 0 while the logo is where the splash left it, 1 once it has moved up and shrunk. */
    fun lift(t: Long, still: Boolean = false): Float =
        if (still) 1f else spring(t - LIFT_AT, damping = 0.86f, omega = 11f)

    /** Ripple [n] (0 or 1): 0..1 as it grows, or a negative number before it has started. */
    fun ripple(n: Int, t: Long): Float {
        val start = landAt + n * RIPPLE_GAP
        if (t < start) return -1f
        return progress(t, start, RIPPLE)
    }

    /** How strongly the amber light behind the middle bar shows, 0..1. It breathes once lit. */
    fun glow(t: Long, still: Boolean = false): Float {
        if (still) return 0.85f
        val on = progress(t, HOLD, 600L)
        val breath = 0.85f + 0.15f * sin(t / 1000f * 2f * PI.toFloat() * 0.6f)
        return on * breath
    }

    /** "WELCOME TO": 0 hidden, 1 in place. Type is well damped: it never wobbles. */
    fun caption(t: Long, still: Boolean = false): Float =
        if (still) 1f else spring(t - CAPTION_AT, damping = 1f, omega = 14f)

    /** Letter [i] of "Zmusic", rising in one after another. */
    fun letter(i: Int, t: Long, still: Boolean = false): Float =
        if (still) 1f else spring(t - (LETTER_AT + i * LETTER_STAGGER), damping = 1f, omega = 16f)

    /** The amber rule under the name, growing out from its middle. */
    fun rule(t: Long, still: Boolean = false): Float =
        if (still) 1f else spring(t - RULE_AT, damping = 1f, omega = 12f)

    /** 0 while showing, 1 once gone. Eased in, so it starts gently and then clears quickly. */
    fun exit(t: Long, exitAt: Long): Float {
        val p = progress(t, exitAt, EXIT)
        return p * p
    }

    fun finished(t: Long, exitAt: Long): Boolean = t >= exitAt + EXIT
}
