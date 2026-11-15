package com.zmusic.app.ui.components

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Listens to the bass for kicks and keeps what a kick sets off, for whichever sculpture is on
 * stage: the impact, the age of the shockwave, and the focus lines round the edge of the frame.
 * Plain Kotlin, no Compose or Android types.
 *
 * @param focusLines how many focus lines to lay out; 0 for a sculpture that shows none.
 */
internal class ImpactFx(val focusLines: Int) {
    /**
     * The skill going off. A kick sets it between 0.45 and 1 by how hard the kick lands, and it
     * falls back to 0 in about half a second. Kicks closer together than [GAP] leave it alone,
     * which keeps the flashing under three a second.
     */
    var impact = 0f
        private set

    /** Seconds since the last impact went off; stops counting at 10. */
    var sinceImpact = 10f
        private set

    /** How many impacts have gone off. It changes on the frame one does. */
    var strike = 0
        private set

    /** Seconds since a kick last threw a shockwave out, or negative when none is travelling. */
    var waveAge = -1f
        private set

    /** Thin wedges pointing in from the edge of the frame, one triangle (6 floats) each. [focusAlpha] zero means "none showing". */
    val focusXY = FloatArray(focusLines * 6)
    var focusAlpha = 0f
        private set

    // Bass against its own slow average.
    private var bassSlow = 0f
    private var primed = false
    private var armed = true

    /**
     * Steps the detector by [dt] seconds. Every kick restarts the shockwave. One that arrives on
     * a rising edge, at least [GAP] after the last, also sets off [impact]: that frame returns true.
     */
    fun listen(bass: Float, dt: Float): Boolean {
        // Start the average at the level it finds, so whatever is already playing is not a kick.
        if (!primed) {
            primed = true
            bassSlow = bass
        }
        bassSlow += (bass - bassSlow) * min(1f, dt * 2.5f)
        val jump = bass - bassSlow
        impact -= impact * min(1f, dt * 6f)
        if (impact < 0.004f) impact = 0f
        if (sinceImpact < 10f) sinceImpact += dt
        if (waveAge >= 0f) {
            waveAge += dt
            if (waveAge > WAVE_SECONDS) waveAge = -1f
        }
        if (jump < 0.08f) armed = true
        if (jump <= 0.13f) return false
        if (waveAge < 0f || waveAge > 0.28f) waveAge = 0f
        val strength = 0.45f + 0.55f * ((jump - 0.13f) / 0.20f).coerceIn(0f, 1f)
        if (armed && sinceImpact > GAP) {
            armed = false
            sinceImpact = 0f
            strike++
            impact = strength
            return true
        }
        // The kick is caught as it crosses the threshold, before it peaks: let it keep climbing.
        if (sinceImpact < RISE && strength > impact) impact = strength
        return false
    }

    /**
     * Lays the focus lines out round a frame centred on ([fx], [fy]) while [impact] is high. They
     * are redrawn in new places every 60 ms, the way hand-drawn ones boil from frame to frame.
     */
    fun focus(fx: Float, fy: Float, halfWidth: Float, halfHeight: Float) {
        val strength = impact * impact
        if (focusLines == 0 || strength < 0.02f) {
            focusAlpha = 0f
            return
        }
        focusAlpha = strength * 0.55f
        val boil = (sinceImpact / 0.06f).toInt()
        for (i in 0 until focusLines) {
            val seed = strike * 977 + boil * 131 + i * 13
            val a = (i + Emblem.scatter(seed)) * TWO_PI / focusLines
            val c = cos(a)
            val s = sin(a)
            // Distance from the frame's centre to its edge in this direction.
            val edge = min(halfWidth / max(abs(c), 1e-3f), halfHeight / max(abs(s), 1e-3f))
            val outer = edge * 1.02f
            val inner = edge * (0.80f + 0.17f * Emblem.scatter(seed + 1))
            val w = edge * (0.004f + 0.012f * Emblem.scatter(seed + 2))
            val o = i * 6
            focusXY[o] = fx + c * outer - s * w; focusXY[o + 1] = fy + s * outer + c * w
            focusXY[o + 2] = fx + c * outer + s * w; focusXY[o + 3] = fy + s * outer - c * w
            focusXY[o + 4] = fx + c * inner; focusXY[o + 5] = fy + s * inner
        }
    }

    companion object {
        /** How long a shockwave travels, in seconds. */
        const val WAVE_SECONDS = 0.75f

        /** Shortest time between two impacts, in seconds. */
        const val GAP = 0.36f

        /** How long after it starts an impact may still grow, while the kick is still rising. */
        const val RISE = 0.10f

        /** How many focus lines a hero-size sculpture shows. */
        const val FOCUS = 44

        private const val TWO_PI = 6.2831855f
    }
}
