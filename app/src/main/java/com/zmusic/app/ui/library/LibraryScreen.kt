package com.zmusic.app.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.components.RescanButton
import com.zmusic.app.ui.components.ScreenHeader
import com.zmusic.app.ui.components.TrackRow
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.TimeStyle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    tracks: List<TrackEntity>?,
    playerState: PlayerUiState,
    player: PlayerController,
    scanning: Boolean,
    scanError: String?,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            eyebrow = when {
                tracks == null -> "Loading"
                tracks.size == 1 -> "1 song"
                else -> "${tracks.size} songs"
            },
            title = "Library",
        ) { RescanButton(scanning, onRefresh) }

        if (scanError != null) {
            Text(
                text = "Scan problem: $scanError",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }

        when {
            tracks == null -> LoadingIndicator(Modifier.fillMaxSize())

            tracks.isEmpty() -> EmptyLibrary(scanning, onRefresh)

            else -> {
                // Tracks arrive sorted by title, so letter groups are contiguous. Each song keeps its
                // index in the full list so tapping plays from the right place.
                val groups = remember(tracks) {
                    tracks.withIndex().groupBy { iv ->
                        iv.value.title.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "#"
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    groups.forEach { (letter, rows) ->
                        stickyHeader(key = "h-$letter") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(start = 24.dp, top = 14.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    letter,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = rows.size.toString(),
                                    style = TimeStyle,
                                    color = AppTheme.extended.faint,
                                    modifier = Modifier.padding(bottom = 5.dp),
                                )
                            }
                        }
                        items(rows, key = { it.value.mediaStoreId }) { iv ->
                            TrackRow(
                                track = iv.value,
                                isCurrent = iv.value.mediaStoreId.toString() == playerState.mediaId,
                            ) {
                                player.setShuffle(false)
                                player.playQueue(tracks, iv.index)
                            }
                        }
                    }
                }
            }
        }
    }
}
