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
    val albumId: Long = 0L,
    val trackUri: String = "",
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val durationMs: Long = 0L,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
)
