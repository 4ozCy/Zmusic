package com.zmusic.app.playback

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live analysis from [LevelAudioProcessor], kept on a timeline so the UI can show what is coming
 * out of the speaker right now rather than what the decoder just produced.
 *
 * The processor sits before the AudioTrack, so it sees each sample a few hundred ms (the sink's
 * buffer) before it is heard. Every analysis is therefore stamped with the media time of the
 * samples it measured, and [TimedAudioSink] publishes the sink's playout clock. [heardNow] looks
 * up the analysis whose stamp matches the clock.
 *
 * Writers are on the playback thread, the reader on the main thread. Every access is a few dozen
 * floats under one monitor, a few hundred times a second, so a lock costs nothing that matters.
 */
object AudioLevels {
    const val BANDS = 12

    /** History slots. At 512-sample hops this is about 1.5 s at 44.1 kHz, more than any sink buffers. */
    private const val SLOTS = 128

    /** An analysis further than this from the clock does not count as "now" (a seek, a gap). */
    private const val MATCH_US = 120_000L

    /** The clock is extrapolated forward between updates, but never further than this. */
    private const val EXTRAPOLATE_MAX_NANOS = 300_000_000L

    /** A clock older than this means playback stopped feeding the sink. */
    private const val CLOCK_STALE_NANOS = 1_000_000_000L

    private val stamps = LongArray(SLOTS)
    private val history = FloatArray(SLOTS * BANDS)
    private var head = 0 // next slot to write
    private var count = 0

    private var clockUs = Long.MIN_VALUE
    private var clockNanos = 0L
    private var clockSpeed = 1f

    /** Stores one analysis: [db] per band (dBFS plus a tilt), measured on samples centred on [mediaUs]. */
    @Synchronized
    fun publish(mediaUs: Long, db: FloatArray) {
        // A stamp going backwards means a seek or a new stream: what is stored no longer applies.
        if (count > 0 && mediaUs < stamps[(head - 1 + SLOTS) % SLOTS]) count = 0
        stamps[head] = mediaUs
        System.arraycopy(db, 0, history, head * BANDS, BANDS)
        head = (head + 1) % SLOTS
        if (count < SLOTS) count++
    }

    /** The sink's playout position, in the same timebase as [publish]'s stamps. */
    @Synchronized
    fun setClock(positionUs: Long, speed: Float, nanos: Long) {
        clockUs = positionUs
        clockSpeed = speed
        clockNanos = nanos
    }

    /** Forgets the history and the clock (seek, flush, reset). */
    @Synchronized
    fun clear() {
        count = 0
        clockUs = Long.MIN_VALUE
    }

    /**
     * Copies into [out] the bands of the audio being heard at [nowNanos] (`System.nanoTime`).
     * Returns false, leaving [out] alone, when there is no clock or nothing analysed matches it.
     */
    @Synchronized
    fun heardNow(nowNanos: Long, out: FloatArray): Boolean {
        if (clockUs == Long.MIN_VALUE || count == 0) return false
        val age = nowNanos - clockNanos
        if (age < 0 || age > CLOCK_STALE_NANOS) return false
        val nowUs = clockUs + (minOf(age, EXTRAPOLATE_MAX_NANOS) / 1000L * clockSpeed).toLong()
        // Newest first: the latest analysis that has already started playing.
        for (k in 1..count) {
            val slot = (head - k + SLOTS) % SLOTS
            val stamp = stamps[slot]
            if (stamp <= nowUs) {
                if (nowUs - stamp > MATCH_US) return false
                System.arraycopy(history, slot * BANDS, out, 0, BANDS)
                return true
            }
        }
        return false
    }
}

/**
 * The ExoPlayer audio session id, published by [PlaybackService] (same process as the UI) so
 * [AudioFrequencyTracker] can attach a Visualizer to this app's own output instead of the
 * deprecated global mix. 0 means "no session yet".
 */
object AudioSession {
    val id = MutableStateFlow(0)
}
