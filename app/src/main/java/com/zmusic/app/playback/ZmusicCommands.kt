package com.zmusic.app.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand

/** Custom session commands shared by [PlaybackService] and [PlayerController]. */
object ZmusicCommands {
    const val TOGGLE_SHUFFLE = "com.zmusic.app.TOGGLE_SHUFFLE"
    const val CYCLE_REPEAT = "com.zmusic.app.CYCLE_REPEAT"

    /** Pause when the current song ends. Argument [KEY_ENABLED] (default true). */
    const val STOP_AFTER_CURRENT = "com.zmusic.app.STOP_AFTER_CURRENT"
    const val KEY_ENABLED = "enabled"

    val toggleShuffle = SessionCommand(TOGGLE_SHUFFLE, Bundle.EMPTY)
    val cycleRepeat = SessionCommand(CYCLE_REPEAT, Bundle.EMPTY)
    val stopAfterCurrent = SessionCommand(STOP_AFTER_CURRENT, Bundle.EMPTY)
}

/** Repeat cycles off, all, one, and back to off, from every place that offers the button. */
fun nextRepeatMode(mode: Int): Int = when (mode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}
