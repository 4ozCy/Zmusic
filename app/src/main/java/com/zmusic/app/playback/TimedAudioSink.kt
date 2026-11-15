package com.zmusic.app.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * The player's audio sink with two taps for the visuals, both on the playback thread:
 *
 * - each buffer's presentation time goes to [levels] just before the sink (and so the processor)
 *   handles it, so every analysis can be stamped with the media time of its samples;
 * - after each buffer, the sink's playout position (what the speaker is playing, latency
 *   included) goes to [AudioLevels] as the clock the UI matches those stamps against.
 *
 * The renderer calls [handleBuffer] every few ms while playing, even when the sink is full, so
 * the clock stays fresh.
 */
@UnstableApi
class TimedAudioSink(sink: AudioSink, private val levels: LevelAudioProcessor) : ForwardingAudioSink(sink) {

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        levels.inputTimeUs = presentationTimeUs
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val position = super.getCurrentPositionUs(false)
        if (position != AudioSink.CURRENT_POSITION_NOT_SET) {
            AudioLevels.setClock(position, playbackParameters.speed, System.nanoTime())
        }
        return handled
    }

    override fun flush() {
        super.flush()
        AudioLevels.clear()
    }

    override fun reset() {
        super.reset()
        AudioLevels.clear()
    }
}
