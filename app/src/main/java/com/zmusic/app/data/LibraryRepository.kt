package com.zmusic.app.data

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

    /** Throttled unless [force]. Serialized so overlapping calls can't run two scans. */
    suspend fun refresh(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastScanAt < STALE_AFTER_MS) return
        mutex.withLock {
            _scanning.value = true
            try {
                withContext(Dispatchers.IO) {
                    val found = scanner.scan()
                    dao.upsertAll(found)
                    val foundIds = found.mapTo(HashSet()) { it.mediaStoreId }
                    dao.allIds()
                        .filter { it !in foundIds }
                        .chunked(500)
                        .forEach { dao.markMissing(it) }
                }
                lastScanAt = System.currentTimeMillis()
                _scanError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _scanError.value = e.message ?: "Library scan failed"
            } finally {
                _scanning.value = false
            }
        }
    }

    private companion object {
        const val STALE_AFTER_MS = 30_000L
    }
}
