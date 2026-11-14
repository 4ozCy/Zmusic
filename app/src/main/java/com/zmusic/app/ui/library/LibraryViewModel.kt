package com.zmusic.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zmusic.app.data.AlbumSummary
import com.zmusic.app.data.LibraryRepository
import com.zmusic.app.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val repo: LibraryRepository) : ViewModel() {

    /** null = still loading the first emission. */
    val tracks: StateFlow<List<TrackEntity>?> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recentlyAdded: StateFlow<List<TrackEntity>> = repo.observeRecentlyAdded(15)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<AlbumSummary>> = repo.observeAll()
        .map { list ->
            list.groupBy { it.albumId }
                .map { (id, ts) ->
                    val artists = ts.map { it.artist }.distinct()
                    AlbumSummary(
                        albumId = id,
                        title = ts.first().album,
                        artist = if (artists.size == 1) artists[0] else "Various artists",
                        trackCount = ts.size,
                        totalMs = ts.sumOf { it.durationMs },
                    )
                }
                .sortedBy { it.title.lowercase() }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Most common artists, shown as quick suggestions on the Search screen. */
    val topArtists: StateFlow<List<String>> = repo.observeAll()
        .map { list ->
            list.groupingBy { it.artist }.eachCount()
                .filterKeys { it != "Unknown artist" }
                .entries.sortedByDescending { it.value }
                .take(12)
                .map { it.key }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scanning: StateFlow<Boolean> = repo.scanning
    val scanError: StateFlow<String?> = repo.scanError

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val searchResults: StateFlow<List<TrackEntity>> = _query
        .debounce(250)
        .flatMapLatest { q ->
            val trimmed = q.trim()
            if (trimmed.isEmpty()) flowOf(emptyList()) else repo.search(trimmed)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) {
        _query.value = q
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch { repo.refresh(force) }
    }
}
