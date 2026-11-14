package com.zmusic.app.playback

import android.media.audiofx.Visualizer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One place every live visual reads audio energy from.
 *
 * It publishes three smoothed, normalised bands ([bass], [mid], [treble], each `0f..1f`) plus the
 * twelve finer [bands] they are built from. Where the numbers come from is decided every frame:
 *
 * 1. **Visualizer** - `android.media.audiofx.Visualizer` attached to the player's own audio
 *    session. Needs the `RECORD_AUDIO` runtime permission. It taps the output mix, so it is in
 *    sync with what you hear and works for any sample format.
 * 2. **PCM tap** - [LevelAudioProcessor] measuring ExoPlayer's buffers. No permission, so this is
 *    what runs when the permission is denied or the Visualizer cannot be created.
 * 3. **Breathing** - a slow synthetic wave while nothing is playing, so the UI never freezes.
 *
 * Threading: [attach], [release] and [step] must all be called on the main thread. Visualizer
 * delivers its captures on the looper of the thread that created it, which is therefore also the
 * main thread, so nothing here needs a lock.
 *
 * Lifecycle: the owner must call [release] when the UI stops; a Visualizer holds a native audio
 * effect and leaks if it is only dropped. See `rememberAudioFrequencyTracker`.
 */
@Stable
class AudioFrequencyTracker {

    enum class Source { Visualizer, PcmTap, Breathing }

    private val _bass = MutableStateFlow(0f)
    private val _mid = MutableStateFlow(0f)
    private val _treble = MutableStateFlow(0f)

    /** 60 - 250 Hz. Kick and bass line. */
    val bass: StateFlow<Float> = _bass.asStateFlow()

    /** 400 Hz - 1.6 kHz. Vocals and body. */
    val mid: StateFlow<Float> = _mid.asStateFlow()

    /** 2.5 - 10 kHz. Hats, air, consonants. */
    val treble: StateFlow<Float> = _treble.asStateFlow()

    /** Twelve smoothed log-spaced bands, 0..1. Read in draw code; do not hold across frames. */
    val bands = FloatArray(BANDS)

    /**
     * Bumped once per display frame. Read it inside a draw or graphicsLayer lambda to redraw at
     * display rate without recomposing anything.
     */
    val frame = mutableLongStateOf(0L)

    /** Animation clock in seconds. Advances only while frames are being produced. */
    var seconds = 0f
        private set

    /** Which input fed the last frame. */
    var source = Source.Breathing
        private set

    private var visualizer: Visualizer? = null
    private var attachedSession = 0
    private val raw = FloatArray(BANDS)
    private var lastCaptureNanos = 0L
    private var lastFrameNanos = 0L

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

        override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            if (fft != null && fft.size >= 4 && samplingRate > 0) analyse(fft, samplingRate)
        }
    }

    /**
     * Attaches a Visualizer to [sessionId]. Returns false, and leaves the tracker on its
     * fallbacks, if the session is not valid yet, the permission is missing, or the device
     * refuses to create the effect. Safe to call repeatedly.
     */
    fun attach(sessionId: Int): Boolean {
        if (visualizer != null && attachedSession == sessionId) return true
        release()
        if (sessionId <= 0) return false
        var created: Visualizer? = null
        return try {
            val v = Visualizer(sessionId)
            created = v
            v.setEnabled(false)
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024))
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED)
            v.setDataCaptureListener(listener, Visualizer.getMaxCaptureRate(), false, true)
            v.setEnabled(true)
            visualizer = v
            attachedSession = sessionId
            true
        } catch (e: RuntimeException) {
            // No permission, effect not available on this device, or the session just went away.
            runCatching { created?.release() }
            false
        }
    }

    /** Stops capturing and frees the native effect. Idempotent. */
    fun release() {
        val v = visualizer ?: return
        visualizer = null
        attachedSession = 0
        runCatching { v.setEnabled(false) }
        runCatching { v.setDataCaptureListener(null, 0, false, false) }
        runCatching { v.release() }
    }

    /**
     * Visualizer FFT layout: byte 0 is the DC real part, byte 1 the Nyquist real part, then
     * (real, imaginary) pairs for bins 1..n/2-1. Each band takes the loudest bin in its range,
     * converted to dB and mapped onto 0..1. Allocates nothing.
     */
    private fun analyse(fft: ByteArray, samplingRateMilliHz: Int) {
        val n = fft.size
        val lastBin = n / 2 - 1
        val binHz = samplingRateMilliHz / 1000f / n
        for (b in 0 until BANDS) {
            val from = (BAND_EDGES[b] / binHz).toInt().coerceIn(1, lastBin)
            val to = (ceil(BAND_EDGES[b + 1] / binHz).toInt() - 1).coerceIn(from, lastBin)
            var peak = 0f
            for (k in from..to) {
                val m = hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
                if (m > peak) peak = m
            }
            // 8-bit capture: full scale is ~128, giving about 42 dB of usable range. The tilt
            // lifts the upper bands, which always carry less energy in music.
            val db = 20f * log10(peak / 128f + 1e-4f) + b * 1.5f
            raw[b] = ((db + 40f) / 36f).coerceIn(0f, 1f)
        }
        lastCaptureNanos = System.nanoTime()
    }

    /**
     * Advances the smoothing by one display frame. Attack is fast and release slower, so the
     * visuals catch transients without flickering, and the result is independent of refresh rate
     * (60, 90 and 120 Hz all decay over the same wall-clock time).
     */
    fun step(frameNanos: Long, playing: Boolean) {
        val dt = if (lastFrameNanos == 0L) 0.016f else ((frameNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNanos = frameNanos
        seconds += dt
        // Float seconds lose sub-frame precision after a few hours; one wrap an hour is invisible.
        if (seconds > 3600f) seconds -= 3600f

        val visualizerLive = visualizer != null && System.nanoTime() - lastCaptureNanos < STALE_NANOS
        source = when {
            !playing -> Source.Breathing
            visualizerLive -> Source.Visualizer
            else -> Source.PcmTap
        }

        val attack = 1f - exp(-dt * 34f)
        val release = 1f - exp(-dt * (if (playing) 8f else 3f))
        for (i in 0 until BANDS) {
            val breath = 0.13f + 0.09f * sin(seconds * 0.85f - i * 0.42f)
            val target = when (source) {
                Source.Visualizer -> max(raw[i], breath * 0.35f)
                // The floor keeps things moving when the tap is inactive (non 16-bit audio).
                Source.PcmTap -> max(AudioLevels.bands[i], breath * 0.35f)
                Source.Breathing -> breath
            }
            val current = bands[i]
            bands[i] = current + (target - current) * (if (target > current) attack else release)
        }

        // sqrt lifts quiet passages so the lights do not sit dark between hits.
        _bass.value = sqrt((bands[0] + bands[1] + bands[2] + bands[3]) / 4f)
        _mid.value = sqrt((bands[4] + bands[5] + bands[6] + bands[7]) / 4f)
        _treble.value = sqrt((bands[8] + bands[9] + bands[10] + bands[11]) / 4f)
        frame.longValue = frameNanos
    }

    companion object {
        const val BANDS = AudioLevels.BANDS

        /** If no capture arrived for this long, the Visualizer is treated as dead. */
        private const val STALE_NANOS = 500_000_000L

        /** Band boundaries in Hz: geometric midpoints of the same centres [LevelAudioProcessor] uses. */
        private val BAND_EDGES = floatArrayOf(
            45f, 77f, 126f, 200f, 316f, 502f, 794f, 1265f, 2000f, 3162f, 5020f, 7937f, 13000f,
        )
    }
}
