package com.zmusic.app.playback

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live analysis published by [LevelAudioProcessor] on the audio thread and read by the UI.
 * Plain floats on purpose: no locks, no allocation. A torn read between two bands for one
 * frame is harmless for visuals.
 */
object AudioLevels {
    const val BANDS = 12

    /** 12 log-spaced bands, roughly 60 Hz to 10 kHz, each 0..1. */
    val bands = FloatArray(BANDS)

    /** Low-end energy (first four bands), 0..1. */
    @Volatile
    var bass = 0f

    fun publish(src: FloatArray) {
        System.arraycopy(src, 0, bands, 0, BANDS)
        bass = (src[0] + src[1] + src[2] + src[3]) / 4f
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
