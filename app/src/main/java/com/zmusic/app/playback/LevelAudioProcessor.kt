package com.zmusic.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Passes audio through untouched while measuring a small spectrum from the real PCM samples
 * (Goertzel on a Hann-windowed 1024-sample block, 12 bands, a new block every 512 samples, so
 * about 86 analyses a second at 44.1 kHz). No RECORD_AUDIO permission needed because it reads
 * ExoPlayer's own output buffers, not the microphone.
 *
 * Every analysis is stamped with the media time of the samples it measured, worked out from the
 * presentation time [TimedAudioSink] hands over in [inputTimeUs] plus the frames counted since.
 * [AudioLevels] then holds it back until the speaker actually plays it.
 *
 * 16-bit and float PCM. Other formats leave the processor inactive. The hot loop allocates nothing.
 */
@UnstableApi
class LevelAudioProcessor : BaseAudioProcessor() {

    /**
     * Presentation time of the buffer the sink is handling now, set by [TimedAudioSink] on the
     * playback thread just before the buffer reaches this processor. [C.TIME_UNSET] if unknown.
     */
    var inputTimeUs = C.TIME_UNSET

    private val ring = FloatArray(WINDOW)
    private val window = FloatArray(WINDOW)
    private val hann = FloatArray(WINDOW) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (WINDOW - 1))).toFloat() }
    private val coeffs = FloatArray(AudioLevels.BANDS)
    private val result = FloatArray(AudioLevels.BANDS)
    private var ringAt = 0
    private var sinceHop = 0
    private var primed = 0
    private var channels = 2
    private var isFloat = false
    private var sampleRate = 44_100

    // Timing: the presentation time last seen, and the frame count at which it was seen.
    private var frames = 0L
    private var anchorUs = C.TIME_UNSET
    private var anchorFrame = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if ((encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount < 1) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount
        isFloat = encoding == C.ENCODING_PCM_FLOAT
        sampleRate = inputAudioFormat.sampleRate
        val fs = sampleRate.toFloat()
        for (i in 0 until AudioLevels.BANDS) {
            val f = min(BAND_HZ[i], fs * 0.45f)
            coeffs[i] = (2.0 * cos(2.0 * PI * f / fs)).toFloat()
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        // A new presentation time re-anchors the clock; a buffer handed over again keeps the old one.
        val pts = inputTimeUs
        if (pts != C.TIME_UNSET && pts != anchorUs) {
            anchorUs = pts
            anchorFrame = frames
        }

        // Read through a duplicate so the real buffer can be copied through unchanged afterwards.
        val view = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sampleBytes = if (isFloat) 4 else 2
        val frameBytes = channels * sampleBytes
        var pos = view.position()
        val end = view.limit()
        while (pos + frameBytes <= end) {
            var sum = 0f
            var c = 0
            while (c < channels) {
                sum += if (isFloat) view.getFloat(pos + c * 4) else view.getShort(pos + c * 2) / 32768f
                c++
            }
            ring[ringAt] = sum / channels
            ringAt = (ringAt + 1) % WINDOW
            pos += frameBytes
            frames++
            if (primed < WINDOW) primed++
            if (++sinceHop >= HOP && primed == WINDOW) {
                sinceHop = 0
                analyze()
            }
        }

        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }

    private fun analyze() {
        // Oldest sample first, windowed.
        for (i in 0 until WINDOW) window[i] = ring[(ringAt + i) % WINDOW] * hann[i]
        for (b in 0 until AudioLevels.BANDS) {
            val coeff = coeffs[b]
            var s1 = 0f
            var s2 = 0f
            for (i in 0 until WINDOW) {
                val s0 = window[i] + coeff * s1 - s2
                s2 = s1
                s1 = s0
            }
            val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
            // Hann window has a coherent gain of 0.5, so a sine of amplitude A reads A * N / 4.
            val mag = sqrt(max(power, 0f)) / (WINDOW * 0.25f)
            // Higher bands carry less energy in music; a gentle tilt keeps the display balanced.
            result[b] = 20f * log10(mag + 1e-6f) + b * 1.2f
        }
        if (anchorUs == C.TIME_UNSET) return
        // The window's centre is half a window behind the newest sample.
        val centreFrame = frames - WINDOW / 2
        val stampUs = anchorUs + (centreFrame - anchorFrame) * 1_000_000L / sampleRate
        AudioLevels.publish(stampUs, result)
    }

    override fun onFlush() {
        primed = 0
        sinceHop = 0
        ringAt = 0
        anchorUs = C.TIME_UNSET
        frames = 0L
        anchorFrame = 0L
    }

    override fun onReset() {
        onFlush()
        inputTimeUs = C.TIME_UNSET
    }

    private companion object {
        const val WINDOW = 1024
        const val HOP = 512
        val BAND_HZ = floatArrayOf(60f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f, 2500f, 4000f, 6300f, 10000f)
    }
}
