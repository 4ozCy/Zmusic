package com.zmusic.app.playback.eq

import androidx.compose.runtime.Immutable
import kotlin.math.abs

/** Centres of the ten bands in Hz, an octave apart. */
val EQ_BAND_HZ: List<Float> = listOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

const val EQ_BAND_COUNT = 10
const val EQ_BAND_MIN_DB = -12f
const val EQ_BAND_MAX_DB = 12f
const val EQ_PREAMP_MIN_DB = -12f
const val EQ_PREAMP_MAX_DB = 6f
const val EQ_BASS_MAX_DB = 10f

/** Short label for band [index]: "31", "500", "1k", "16k". */
fun eqBandLabel(index: Int): String {
    val hz = EQ_BAND_HZ[index].toInt()
    return if (hz >= 1_000) "${hz / 1_000}k" else hz.toString()
}

/**
 * Everything the equalizer needs to know. Immutable, so the audio thread can read a snapshot
 * while the UI builds the next one.
 *
 * @param gainsDb one gain per band, [EQ_BAND_MIN_DB]..[EQ_BAND_MAX_DB]. The bands are corrected
 * for the way neighbours overlap, so the response at each band centre is this value.
 * @param preampDb gain applied after the bands, [EQ_PREAMP_MIN_DB]..[EQ_PREAMP_MAX_DB].
 * @param bassDb low shelf under 100 Hz, 0..[EQ_BASS_MAX_DB].
 */
@Immutable
data class EqSettings(
    val enabled: Boolean = false,
    val gainsDb: List<Float> = FLAT,
    val preampDb: Float = 0f,
    val bassDb: Float = 0f,
) {
    /** The preset whose curve these gains match, or null for a custom curve. */
    val preset: EqPreset?
        get() = EqPreset.entries.firstOrNull { p -> p.gainsDb.indices.all { abs(p.gainsDb[it] - gainsDb[it]) < 0.05f } }

    /** True when the settings leave the sound exactly as it was, so the audio can skip the work. */
    val isNeutral: Boolean
        get() = gainsDb.all { abs(it) < 0.05f } && abs(preampDb) < 0.05f && bassDb < 0.05f

    /** Same settings with every value pulled into range and exactly [EQ_BAND_COUNT] gains. */
    fun sanitized(): EqSettings = copy(
        gainsDb = List(EQ_BAND_COUNT) { i -> gainsDb.getOrNull(i).clean(EQ_BAND_MIN_DB, EQ_BAND_MAX_DB) },
        preampDb = preampDb.clean(EQ_PREAMP_MIN_DB, EQ_PREAMP_MAX_DB),
        bassDb = bassDb.clean(0f, EQ_BASS_MAX_DB),
    )

    /** One line for SharedPreferences: `enabled;g0,g1,...,g9;preamp;bass`. */
    fun encode(): String = buildString {
        append(if (enabled) '1' else '0').append(';')
        gainsDb.joinTo(this, ",")
        append(';').append(preampDb).append(';').append(bassDb)
    }

    companion object {
        val FLAT: List<Float> = List(EQ_BAND_COUNT) { 0f }

        /** Reads [encode]'s output. Anything missing or unreadable falls back to the defaults. */
        fun decode(text: String?): EqSettings {
            if (text.isNullOrBlank()) return EqSettings()
            val parts = text.split(';')
            val gains = parts.getOrNull(1)?.split(',')?.map { it.toFloatOrNull() ?: 0f } ?: FLAT
            return EqSettings(
                enabled = parts.getOrNull(0) == "1",
                gainsDb = gains,
                preampDb = parts.getOrNull(2)?.toFloatOrNull() ?: 0f,
                bassDb = parts.getOrNull(3)?.toFloatOrNull() ?: 0f,
            ).sanitized()
        }

        private fun Float?.clean(min: Float, max: Float): Float =
            if (this == null || isNaN()) 0f.coerceIn(min, max) else coerceIn(min, max)
    }
}

/**
 * Built-in curves. Boosts stay at +5.5 dB or under, so the soft clipper rarely has to work on a
 * loud master.
 */
enum class EqPreset(val label: String, val gainsDb: List<Float>) {
    Flat("Flat", EqSettings.FLAT),
    Bass("Bass", listOf(5.5f, 4.5f, 3.5f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
    Treble("Treble", listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 3f, 4f, 4.5f)),
    Vocal("Vocal", listOf(-2f, -2f, -1f, 1f, 3f, 3.5f, 3f, 1.5f, 0f, -1f)),
    Rock("Rock", listOf(4f, 3f, 1.5f, -0.5f, -1.5f, -0.5f, 1.5f, 3f, 3.5f, 4f)),
    Pop("Pop", listOf(-1f, 0f, 1.5f, 3f, 3.5f, 2.5f, 1f, 0f, -0.5f, -1f)),
    Electronic("Electronic", listOf(4.5f, 4f, 1.5f, 0f, -1.5f, 1.5f, 0.5f, 1.5f, 3.5f, 4.5f)),
    HipHop("Hip-hop", listOf(5f, 4f, 1.5f, 2.5f, -1f, -1f, 1f, -0.5f, 1.5f, 2.5f)),
    Jazz("Jazz", listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1.5f, 2.5f, 3f)),
    Classical("Classical", listOf(3f, 2.5f, 1.5f, 1f, 0f, 0f, 0f, 1.5f, 2.5f, 3f)),
    Acoustic("Acoustic", listOf(3f, 3f, 2f, 1f, 1.5f, 1f, 2f, 2.5f, 2f, 1.5f)),
}
