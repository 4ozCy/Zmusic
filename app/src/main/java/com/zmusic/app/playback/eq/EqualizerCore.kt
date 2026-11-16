package com.zmusic.app.playback.eq

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Filter design shared by the audio path and the curve on screen, so the curve you see is the one
 * you hear. Plain Kotlin with no Android or Media3 types, so it runs in JVM unit tests.
 *
 * Biquads follow the RBJ Audio EQ Cookbook. Each one is five doubles, `b0, b1, b2, a1, a2`,
 * already divided by `a0`. Slots 0..9 are the band bells, slot 10 is the bass shelf.
 */
object EqDesign {
    const val STAGES = EQ_BAND_COUNT + 1
    private const val BASS_STAGE = EQ_BAND_COUNT

    /** About one octave wide, so neighbouring bands meet without gaps. */
    private const val Q = 1.41
    private const val BASS_HZ = 100.0
    private const val BASS_SLOPE = 0.9
    private const val CORRECTION_PASSES = 6
    private const val INTERNAL_LIMIT_DB = 18.0

    /** Smaller gains than this are treated as zero and the stage is skipped. */
    private const val IDENTITY_DB = 0.01

    /** Where the soft clipper starts to bend: -1 dBFS. */
    const val KNEE = 0.891

    /**
     * Fills [coeffs] (`STAGES * 5`) and [active] (`STAGES`) for [settings] at [sampleRate] Hz.
     * A stage left inactive is an exact pass-through. Returns the preamp as a linear gain.
     *
     * Neighbouring bells overlap, so ten sliders at +6 dB would add up to about +8 dB. A few
     * correction passes nudge each band until the response at every band centre equals its slider.
     */
    fun design(settings: EqSettings, sampleRate: Double, coeffs: DoubleArray, active: BooleanArray): Double {
        val targets = settings.gainsDb
        // Bands too close to Nyquist (16 kHz at a 22 kHz rate) are left out instead of misbehaving.
        val usable = BooleanArray(EQ_BAND_COUNT) { EQ_BAND_HZ[it] < sampleRate * 0.45 }
        val gains = DoubleArray(EQ_BAND_COUNT) { targets[it].toDouble() }
        if (targets.any { abs(it) >= IDENTITY_DB }) {
            repeat(CORRECTION_PASSES) {
                for (i in 0 until EQ_BAND_COUNT) {
                    if (usable[i]) peaking(EQ_BAND_HZ[i].toDouble(), gains[i], sampleRate, coeffs, i * 5)
                }
                val next = DoubleArray(EQ_BAND_COUNT)
                for (i in 0 until EQ_BAND_COUNT) {
                    if (!usable[i]) continue
                    val w = 2.0 * PI * EQ_BAND_HZ[i] / sampleRate
                    var sum = 0.0
                    for (k in 0 until EQ_BAND_COUNT) if (usable[k]) sum += magnitudeDb(coeffs, k * 5, w)
                    next[i] = (gains[i] + targets[i] - sum).coerceIn(-INTERNAL_LIMIT_DB, INTERNAL_LIMIT_DB)
                }
                next.copyInto(gains)
            }
        }
        for (i in 0 until EQ_BAND_COUNT) {
            active[i] = usable[i] && abs(gains[i]) >= IDENTITY_DB
            if (active[i]) peaking(EQ_BAND_HZ[i].toDouble(), gains[i], sampleRate, coeffs, i * 5)
        }
        active[BASS_STAGE] = settings.bassDb >= IDENTITY_DB
        if (active[BASS_STAGE]) lowShelf(BASS_HZ, settings.bassDb.toDouble(), sampleRate, coeffs, BASS_STAGE * 5)
        return 10.0.pow(settings.preampDb / 20.0)
    }

    /** The curve in dB at each of [freqsHz]: bands and bass shelf together, preamp left out. */
    fun responseDb(settings: EqSettings, freqsHz: FloatArray, sampleRate: Double = 48_000.0): FloatArray {
        val coeffs = DoubleArray(STAGES * 5)
        val active = BooleanArray(STAGES)
        design(settings, sampleRate, coeffs, active)
        return FloatArray(freqsHz.size) { j ->
            val w = 2.0 * PI * freqsHz[j] / sampleRate
            var sum = 0.0
            for (k in 0 until STAGES) if (active[k]) sum += magnitudeDb(coeffs, k * 5, w)
            sum.toFloat()
        }
    }

    /**
     * Leaves samples under [KNEE] alone and bends louder ones smoothly toward full scale, so a
     * boost that would clip rounds off instead of cracking. Continuous in value and slope.
     */
    fun softClip(x: Double): Double {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1.0 - KNEE) * tanh((a - KNEE) / (1.0 - KNEE))
        return if (x < 0.0) -y else y
    }

    internal fun peaking(f0: Double, gainDb: Double, fs: Double, out: DoubleArray, at: Int) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val alpha = sin(w0) / (2.0 * Q)
        val cw = cos(w0)
        val a0 = 1.0 + alpha / a
        out[at] = (1.0 + alpha * a) / a0
        out[at + 1] = -2.0 * cw / a0
        out[at + 2] = (1.0 - alpha * a) / a0
        out[at + 3] = -2.0 * cw / a0
        out[at + 4] = (1.0 - alpha / a) / a0
    }

    internal fun lowShelf(f0: Double, gainDb: Double, fs: Double, out: DoubleArray, at: Int) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cw = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / BASS_SLOPE - 1.0) + 2.0)
        val sa = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1.0) + (a - 1.0) * cw + sa
        out[at] = a * ((a + 1.0) - (a - 1.0) * cw + sa) / a0
        out[at + 1] = 2.0 * a * ((a - 1.0) - (a + 1.0) * cw) / a0
        out[at + 2] = a * ((a + 1.0) - (a - 1.0) * cw - sa) / a0
        out[at + 3] = -2.0 * ((a - 1.0) + (a + 1.0) * cw) / a0
        out[at + 4] = ((a + 1.0) + (a - 1.0) * cw - sa) / a0
    }

    /** Magnitude in dB of the biquad at [at] for [w] radians per sample. */
    internal fun magnitudeDb(c: DoubleArray, at: Int, w: Double): Double {
        val b0 = c[at]
        val b1 = c[at + 1]
        val b2 = c[at + 2]
        val a1 = c[at + 3]
        val a2 = c[at + 4]
        val c1 = cos(w)
        val c2 = cos(2.0 * w)
        val num = b0 * b0 + b1 * b1 + b2 * b2 + 2.0 * (b0 * b1 + b1 * b2) * c1 + 2.0 * b0 * b2 * c2
        val den = 1.0 + a1 * a1 + a2 * a2 + 2.0 * (a1 + a1 * a2) * c1 + 2.0 * a2 * c2
        return 10.0 * log10(max(num, 1e-30) / max(den, 1e-30))
    }
}

/**
 * Runs [EqSettings] over interleaved samples: the bands and bass shelf as biquads in transposed
 * direct form II (double precision, which keeps the 31 Hz band quiet), then the preamp, then the
 * soft clipper.
 *
 * Not thread safe: call everything from the playback thread. Only [configure] allocates.
 */
class EqualizerCore {
    private val coeffs = DoubleArray(EqDesign.STAGES * 5)
    private val active = BooleanArray(EqDesign.STAGES)
    private val wasActive = BooleanArray(EqDesign.STAGES)
    private var state = DoubleArray(0)
    private var channels = 0
    private var sampleRate = 0.0
    private var preamp = 1.0
    private var settings: EqSettings? = null

    /** True when [process] returns its input unchanged, so callers can skip the work. */
    var isBypassed = true
        private set

    /** Sets the stream format. Clears the filters. */
    fun configure(sampleRate: Int, channels: Int) {
        if (sampleRate <= 0 || channels <= 0) {
            this.channels = 0
            isBypassed = true
            return
        }
        this.sampleRate = sampleRate.toDouble()
        this.channels = channels
        state = DoubleArray(channels * EqDesign.STAGES * 2)
        isBypassed = true
        wasActive.fill(false)
        settings?.let(::applySettings)
    }

    /** Takes new settings. Filters that stay on keep their state, so a slider drag does not click. */
    fun update(settings: EqSettings) {
        if (settings != this.settings) applySettings(settings)
    }

    private fun applySettings(s: EqSettings) {
        settings = s
        if (channels == 0 || !s.enabled || s.isNeutral) {
            isBypassed = true
            wasActive.fill(false)
            return
        }
        preamp = EqDesign.design(s, sampleRate, coeffs, active)
        // A stage switching on starts from silence rather than from whatever it held last time.
        val fresh = isBypassed
        for (k in 0 until EqDesign.STAGES) {
            if (active[k] && (fresh || !wasActive[k])) clearStage(k)
            wasActive[k] = active[k]
        }
        isBypassed = false
    }

    /** Filters one sample of [channel] (interleaved order) and returns it, soft clipped. */
    fun process(channel: Int, sample: Double): Double {
        if (isBypassed) return sample
        var x = sample
        val base = channel * EqDesign.STAGES * 2
        for (k in 0 until EqDesign.STAGES) {
            if (!active[k]) continue
            val c = k * 5
            val s = base + k * 2
            val y = coeffs[c] * x + state[s]
            state[s] = coeffs[c + 1] * x - coeffs[c + 3] * y + state[s + 1]
            state[s + 1] = coeffs[c + 2] * x - coeffs[c + 4] * y
            x = y
        }
        return EqDesign.softClip(x * preamp)
    }

    /** Forgets the audio the filters have seen (a seek or a new stream). */
    fun reset() = state.fill(0.0)

    /** Zeroes filter memory that has decayed far below hearing, so it never turns denormal. */
    fun flushDenormals() {
        for (i in state.indices) if (abs(state[i]) < 1e-25) state[i] = 0.0
    }

    private fun clearStage(k: Int) {
        for (ch in 0 until channels) {
            val s = ch * EqDesign.STAGES * 2 + k * 2
            state[s] = 0.0
            state[s + 1] = 0.0
        }
    }
}
