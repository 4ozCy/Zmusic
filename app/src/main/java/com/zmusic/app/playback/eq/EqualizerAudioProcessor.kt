package com.zmusic.app.playback.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The equalizer inside ExoPlayer's audio sink. It runs on the playback thread, ahead of
 * [com.zmusic.app.playback.LevelAudioProcessor], so the visuals react to the sound as it is heard.
 *
 * The EQ is software, not `android.media.audiofx.Equalizer`, so it has the same ten bands and the
 * same sound on every phone and never fights another app for control of a system effect.
 *
 * [settings] is read once per buffer; it must be cheap and thread safe (a `StateFlow.value`).
 * Switching the EQ on or off never reconfigures the sink: the processor is always active for
 * 16-bit and float PCM and copies the audio through untouched while bypassed.
 */
@UnstableApi
class EqualizerAudioProcessor(private val settings: () -> EqSettings) : BaseAudioProcessor() {

    private val core = EqualizerCore()
    private var applied: EqSettings? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if ((encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount < 1) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        val current = settings()
        if (current !== applied) {
            core.update(current)
            applied = current
        }

        val out = replaceOutputBuffer(size).order(ByteOrder.LITTLE_ENDIAN)
        if (core.isBypassed) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val format = inputAudioFormat
        val channels = format.channelCount
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val sampleBytes = if (isFloat) 4 else 2
        val frameBytes = channels * sampleBytes
        val view = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var pos = view.position()
        val end = view.limit()
        while (pos + frameBytes <= end) {
            for (c in 0 until channels) {
                if (isFloat) {
                    val y = core.process(c, view.getFloat(pos).toDouble())
                    out.putFloat(y.toFloat())
                    pos += 4
                } else {
                    val y = core.process(c, view.getShort(pos) / 32_768.0)
                    out.putShort((y * 32_768.0).roundToInt().coerceIn(-32_768, 32_767).toShort())
                    pos += 2
                }
            }
        }
        // A partial frame should never arrive, but if one does it is passed on as it came.
        while (pos < end) out.put(view.get(pos++))
        inputBuffer.position(inputBuffer.limit())
        out.flip()
        core.flushDenormals()
    }

    /** Called when a new format takes effect and on every seek: start the filters from silence. */
    override fun onFlush() {
        core.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        applied = null
    }

    override fun onReset() {
        core.configure(0, 0)
        applied = null
    }
}
