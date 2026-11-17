package com.zmusic.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Keyed by the MediaStore id, not the file path, so moves/renames don't create duplicates.
 * Tracks that disappear from MediaStore are flagged unavailable instead of deleted,
 * so future playlists/history can still reference them.
 */
@Entity(
    tableName = "tracks",
    indices = [Index("title"), Index("artist"), Index("album"), Index("dateAdded"), Index("available")],
)
data class TrackEntity(
    @PrimaryKey val mediaStoreId: Long,
    val uri: String,
    /**
     * What the app shows. Taken from [fileName] without its extension, so renaming the file
     * renames the song; the title tag is used only when there is no file name to go by.
     */
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val dateAdded: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    val relativePath: String?,
    val available: Boolean = true,
    /** The file's name on disk with its extension (MediaStore DISPLAY_NAME). Null on rows from before version 2. */
    val fileName: String? = null,
)
