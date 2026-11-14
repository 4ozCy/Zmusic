package com.zmusic.app.data

/** One album, derived from the track list. Track numbers aren't stored, so songs inside an album sort by title. */
data class AlbumSummary(
    val albumId: Long,
    val title: String,
    val artist: String,
    val trackCount: Int,
    val totalMs: Long,
)
