package com.zmusic.app.data

import com.zmusic.app.debug.BugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LibraryRepository(
    private val dao: TrackDao,
    private val scanner: MediaStoreScanner,
    private val files: TrackFiles,
) {
    private val mutex = Mutex()
    private var lastScanAt = 0L

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    fun observeAll(): Flow<List<TrackEntity>> = dao.observeAll()
    fun observeRecentlyAdded(limit: Int): Flow<List<TrackEntity>> = dao.observeRecentlyAdded(limit)
    fun search(query: String): Flow<List<TrackEntity>> = dao.search(query)

    suspend fun track(mediaStoreId: Long): TrackEntity? = dao.get(mediaStoreId)

    /**
     * Deletes the song's file. On [FileChange.Done] the song leaves the library at once instead of
     * waiting for the next scan. Other results say what Android needs first.
     */
    suspend fun delete(track: TrackEntity, approved: Boolean): FileChange {
        val result = files.delete(track, approved)
        when (result) {
            is FileChange.Done -> {
                dao.markMissing(listOf(track.mediaStoreId))
                BugLog.i("Library", "Deleted \"${track.title}\"")
            }
            is FileChange.Failed -> BugLog.w("Library", "Could not delete \"${track.title}\": ${result.reason}")
            else -> Unit
        }
        return result
    }

    /**
     * Renames the song's file to [input] (extension kept). On [FileChange.Done] the song is read
     * back from MediaStore, so a title that came from the old name changes with it. Returns the
     * fresh row alongside, or null if the song can no longer be read.
     */
    suspend fun rename(track: TrackEntity, input: String, approved: Boolean): Pair<FileChange, TrackEntity?> {
        val result = files.rename(track, input, approved)
        if (result is FileChange.Failed) BugLog.w("Library", "Could not rename \"${track.title}\": ${result.reason}")
        if (result !is FileChange.Done) return result to null
        val fresh = withContext(Dispatchers.IO) { scanner.scanOne(track.mediaStoreId) }
        if (fresh != null) dao.upsertAll(listOf(fresh))
        BugLog.i("Library", "Renamed \"${track.fileName}\" to \"${fresh?.fileName ?: result.fileName}\"")
        return result to fresh
    }

    /** Throttled unless [force]. Serialized so overlapping calls can't run two scans. */
    suspend fun refresh(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastScanAt < STALE_AFTER_MS) return
        mutex.withLock {
            _scanning.value = true
            try {
                withContext(Dispatchers.IO) {
                    val found = scanner.scan()
                    BugLog.i("Library", "Scan found ${found.size} audio files")
                    // Only what changed is written. Any write makes Room run every query that
                    // watches the table again and hand each screen a fresh copy of the whole
                    // library, so a scan that finds nothing new should write nothing at all.
                    val stored = dao.all().associateBy { it.mediaStoreId }
                    val changed = found.filter { stored[it.mediaStoreId] != it }
                    if (changed.isNotEmpty()) dao.upsertAll(changed)
                    val foundIds = found.mapTo(HashSet()) { it.mediaStoreId }
                    stored.values
                        .filter { it.available && it.mediaStoreId !in foundIds }
                        .map { it.mediaStoreId }
                        .chunked(500)
                        .forEach { dao.markMissing(it) }
                }
                lastScanAt = System.currentTimeMillis()
                _scanError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _scanError.value = e.message ?: "Library scan failed"
                BugLog.e("Library", "Scan failed: ${e.message ?: e.javaClass.simpleName}", e)
            } finally {
                _scanning.value = false
            }
        }
    }

    private companion object {
        const val STALE_AFTER_MS = 30_000L
    }
}
