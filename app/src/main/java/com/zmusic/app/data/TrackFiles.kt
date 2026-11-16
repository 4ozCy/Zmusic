package com.zmusic.app.data

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/** Where a rename or delete stands after one attempt. */
sealed interface FileChange {
    /** Finished. [fileName] is the file's name afterwards, when it was renamed. */
    data class Done(val fileName: String? = null) : FileChange

    /**
     * Android wants the user to agree first. Launch [request]; if they agree, run the same
     * operation again with `approved = true`.
     */
    data class NeedsApproval(val request: IntentSender) : FileChange

    /** Android 9 and older: the app needs [permission] granted before it can change files. */
    data class NeedsPermission(val permission: String) : FileChange

    data class Failed(val reason: String) : FileChange
}

/**
 * Renames and deletes the audio files behind songs. Every Android version wants something
 * different:
 *
 * - **Android 11+**: the user approves each change in a system dialog
 *   (`MediaStore.createWriteRequest` / `createDeleteRequest`). For a delete, the system removes
 *   the file itself when they agree.
 * - **Android 10**: the change throws `RecoverableSecurityException`, whose dialog grants access,
 *   and the change is then tried again.
 * - **Android 8 and 9**: plain file access with `WRITE_EXTERNAL_STORAGE`, then MediaStore is
 *   brought up to date.
 *
 * Every function runs its I/O on [Dispatchers.IO].
 */
class TrackFiles(private val context: Context) {
    private val resolver get() = context.contentResolver

    suspend fun delete(track: TrackEntity, approved: Boolean): FileChange = withContext(Dispatchers.IO) {
        val uri = Uri.parse(track.uri)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext deleteLegacy(uri)
        // Android 11+ deletes the file itself once the user agrees, so there is usually nothing left to do.
        if (approved && !exists(uri)) return@withContext FileChange.Done()
        val refused = try {
            resolver.delete(uri, null, null)
            null
        } catch (e: SecurityException) {
            e
        }
        when {
            !exists(uri) -> FileChange.Done()
            approved -> FileChange.Failed(NOT_ALLOWED)
            // Refused by an exception or by deleting nothing: either way Android 11+ wants the user's yes.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                FileChange.NeedsApproval(MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender)
            refused != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> recoverable(refused)
            else -> FileChange.Failed("Android kept the file.")
        }
    }

    /** Renames the file to [input] (see [FileNames.rename]); the extension stays as it is. */
    suspend fun rename(track: TrackEntity, input: String, approved: Boolean): FileChange = withContext(Dispatchers.IO) {
        val uri = Uri.parse(track.uri)
        val current = track.fileName ?: displayName(uri) ?: return@withContext FileChange.Failed("Couldn't read the file's name.")
        FileNames.problem(input, current)?.let { return@withContext FileChange.Failed(it) }
        val target = FileNames.rename(input, current) ?: return@withContext FileChange.Failed("That name can't be used.")
        if (target == current) return@withContext FileChange.Done(current)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext renameLegacy(uri, target)
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, target) }
        val renamed = try {
            resolver.update(uri, values, null, null) > 0
        } catch (e: SecurityException) {
            return@withContext when {
                approved -> FileChange.Failed(NOT_ALLOWED)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> askToWrite(uri)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> recoverable(e)
                else -> FileChange.Failed(NOT_ALLOWED)
            }
        } catch (e: IllegalStateException) {
            // MediaProvider reports a failed move or an unusable name with these two.
            return@withContext FileChange.Failed(e.message ?: RENAME_FAILED)
        } catch (e: IllegalArgumentException) {
            return@withContext FileChange.Failed(e.message ?: RENAME_FAILED)
        }
        if (!renamed) {
            return@withContext if (!approved && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) askToWrite(uri) else FileChange.Failed(RENAME_FAILED)
        }
        // Picks up the new name, and a new title for songs whose title came from the name.
        dataPath(uri)?.let { rescan(it) }
        FileChange.Done(displayName(uri) ?: target)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun askToWrite(uri: Uri): FileChange =
        FileChange.NeedsApproval(MediaStore.createWriteRequest(resolver, listOf(uri)).intentSender)

    /** Android 10: the exception carries the dialog that grants access to this one file. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun recoverable(e: SecurityException): FileChange =
        if (e is RecoverableSecurityException) {
            FileChange.NeedsApproval(e.userAction.actionIntent.intentSender)
        } else {
            FileChange.Failed(NOT_ALLOWED)
        }

    private fun deleteLegacy(uri: Uri): FileChange {
        if (!canWriteLegacy()) return FileChange.NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val file = dataPath(uri)?.let(::File)
        if (file != null && file.exists() && !file.delete()) {
            return FileChange.Failed("Android wouldn't delete the file. It may be on an SD card.")
        }
        runCatching { resolver.delete(uri, null, null) }
        return FileChange.Done()
    }

    private suspend fun renameLegacy(uri: Uri, target: String): FileChange {
        if (!canWriteLegacy()) return FileChange.NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val from = dataPath(uri)?.let(::File) ?: return FileChange.Failed("Couldn't find the file.")
        val to = File(from.parentFile, target)
        // rename() would silently replace a file that already has the name.
        if (to.exists()) return FileChange.Failed("There is already a file called $target in that folder.")
        if (!from.renameTo(to)) return FileChange.Failed("Android wouldn't rename the file. It may be on an SD card.")
        val values = ContentValues().apply {
            @Suppress("DEPRECATION")
            put(MediaStore.MediaColumns.DATA, to.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, target)
        }
        runCatching { resolver.update(uri, values, null, null) }
        rescan(to.absolutePath)
        return FileChange.Done(target)
    }

    private fun canWriteLegacy(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun exists(uri: Uri): Boolean =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false

    private fun displayName(uri: Uri): String? = column(uri, MediaStore.MediaColumns.DISPLAY_NAME)

    /** The absolute path. Deprecated as a way to open files, still the way to ask for a rescan. */
    @Suppress("DEPRECATION")
    private fun dataPath(uri: Uri): String? = column(uri, MediaStore.MediaColumns.DATA)

    private fun column(uri: Uri, name: String): String? = runCatching {
        resolver.query(uri, arrayOf(name), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Asks MediaStore to read the file again and waits for it, up to a few seconds. */
    private suspend fun rescan(path: String) {
        withTimeoutOrNull(RESCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private companion object {
        const val RESCAN_TIMEOUT_MS = 5_000L
        const val NOT_ALLOWED = "Android didn't allow the change."
        const val RENAME_FAILED = "Android couldn't rename the file."
    }
}
