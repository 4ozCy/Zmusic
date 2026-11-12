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
 * (Goertzel on a Hann-windowed 1024-sample block, 12 bands). No RECORD_AUDIO permission needed
 * because it reads ExoPlayer's own output buffers, not the microphone.
 *
 * Notes:
 * - 16-bit PCM only. Other formats leave the processor inactive (levels stay at zero).
 * - It sits before the audio sink, so the visuals can lead the sound by the sink's buffer
 *   (a few tens of ms to a couple of hundred ms depending on device).
 * - The hot loop allocates nothing.
 */
@UnstableApi
class LevelAudioProcessor : BaseAudioProcessor() {

    private val window = FloatArray(WINDOW)
    private val hann = FloatArray(WINDOW) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (WINDOW - 1))).toFloat() }
    private val coeffs = FloatArray(AudioLevels.BANDS)
    private val result = FloatArray(AudioLevels.BANDS)
    private var filled = 0
    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount
        val fs = inputAudioFormat.sampleRate.toFloat()
        for (i in 0 until AudioLevels.BANDS) {
            val f = min(BAND_HZ[i], fs * 0.45f)
            coeffs[i] = (2.0 * cos(2.0 * PI * f / fs)).toFloat()
        }
        filled = 0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        // Read through a duplicate so the real buffer can be copied through unchanged afterwards.
        val view = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = channels * 2
        var pos = view.position()
        val end = view.limit()
        while (pos + frameBytes <= end) {
            var sum = 0
            var c = 0
            while (c < channels) {
                sum += view.getShort(pos + c * 2).toInt()
                c++
            }
            window[filled] = sum / (channels * 32768f) * hann[filled]
            filled++
            pos += frameBytes
            if (filled == WINDOW) {
                analyze()
                filled = 0
            }
        }

        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }

    private fun analyze() {
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
            val db = 20f * log10(mag + 1e-6f) + b * 1.2f
            result[b] = ((db + 62f) / 56f).coerceIn(0f, 1f)
        }
        AudioLevels.publish(result)
    }

    private companion object {
        const val WINDOW = 1024
        val BAND_HZ = floatArrayOf(60f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f, 2500f, 4000f, 6300f, 10000f)
    }
}
