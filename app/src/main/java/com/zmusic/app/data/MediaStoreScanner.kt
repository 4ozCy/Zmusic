package com.zmusic.app.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

/** Reads audio from MediaStore. Only reads: renaming and deleting live in [TrackFiles]. */
class MediaStoreScanner(private val context: Context) {

    private val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    fun scan(): List<TrackEntity> = query(extraSelection = null, extraArgs = emptyArray())

    /** Reads one song again, for example after its file was renamed. Null if it is gone. */
    fun scanOne(mediaStoreId: Long): TrackEntity? =
        query("${MediaStore.Audio.Media._ID} = ?", arrayOf(mediaStoreId.toString())).firstOrNull()

    private fun query(extraSelection: String?, extraArgs: Array<String>): List<TrackEntity> {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Audio.Media.RELATIVE_PATH)
        }.toTypedArray()

        val base = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selection = if (extraSelection == null) base else "$base AND $extraSelection"
        val args = arrayOf(MIN_DURATION_MS.toString(), *extraArgs)
        val out = ArrayList<TrackEntity>()

        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                -1
            }

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val fileName = c.getString(nameCol)
                out += TrackEntity(
                    mediaStoreId = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    // The file's name is what the app shows, so a renamed file shows its new name.
                    title = FileNames.titleFrom(fileName, cleanOr(c.getString(titleCol), "Unknown title")),
                    artist = cleanOr(c.getString(artistCol), "Unknown artist"),
                    album = cleanOr(c.getString(albumCol), "Unknown album"),
                    albumId = c.getLong(albumIdCol),
                    durationMs = c.getLong(durCol),
                    dateAdded = c.getLong(addedCol),
                    sizeBytes = c.getLong(sizeCol),
                    mimeType = c.getString(mimeCol),
                    relativePath = if (pathCol >= 0) c.getString(pathCol) else null,
                    fileName = fileName,
                )
            }
        }
        return out
    }

    private fun cleanOr(value: String?, fallback: String): String {
        val v = value?.trim()
        return if (v.isNullOrEmpty() || v == "<unknown>") fallback else v
    }

    private companion object {
        const val MIN_DURATION_MS = 10_000L
    }
}
