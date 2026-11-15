package com.zmusic.app.playback

import androidx.media3.common.Player

data class QueueEntry(
    val index: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
)

data class PlayerUiState(
    val hasMedia: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val durationMs: Long = 0L,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    /**
     * Goes up by one every time a song starts from the top: the next song, the same song again on
     * repeat, or a song picked from a list. Lets the UI tell a replay from the same song carrying on.
     */
    val songStarts: Int = 0,
)
