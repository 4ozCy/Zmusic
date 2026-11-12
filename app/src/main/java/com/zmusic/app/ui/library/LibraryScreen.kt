package com.zmusic.app.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.TrackRow
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.NumberStyle

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
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Eyebrow(if (tracks != null) "${tracks.size} songs" else "Loading", color = MaterialTheme.colorScheme.primary)
                Text("Library", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (scanning) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Rescan library") }
            }
        }

        if (scanError != null) {
            Text(
                text = "Scan problem: $scanError",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        when {
            tracks == null -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }

            tracks.isEmpty() -> EmptyLibrary(scanning, onRefresh)

            else -> {
                // Tracks arrive sorted by title, so letter groups are contiguous. Keep each song's
                // index in the full list so tapping plays from the right place.
                val groups = remember(tracks) {
                    tracks.withIndex().groupBy { iv ->
                        iv.value.title.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "#"
                    }
                }
                val hairline = AppTheme.extended.hairline
                LazyColumn(Modifier.fillMaxSize()) {
                    groups.forEach { (letter, rows) ->
                        stickyHeader(key = "h-$letter") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Text(letter, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    text = "  ${rows.size}",
                                    style = NumberStyle.copy(fontSize = 12.sp, letterSpacing = 0.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                        items(rows, key = { it.value.mediaStoreId }) { iv ->
                            TrackRow(
                                track = iv.value,
                                isCurrent = iv.value.mediaStoreId.toString() == playerState.mediaId,
                                isPlaying = playerState.isPlaying,
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
