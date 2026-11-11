package com.zmusic.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.components.TrackRow
import com.zmusic.app.ui.theme.gradientTitleStyle

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
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Library", style = gradientTitleStyle())
                if (tracks != null) {
                    Text(
                        "${tracks.size} songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (scanning) {
                CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
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

            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(tracks, key = { _, t -> t.mediaStoreId }) { index, track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.mediaStoreId.toString() == playerState.mediaId,
                        isPlaying = playerState.isPlaying,
                    ) {
                        player.setShuffle(false)
                        player.playQueue(tracks, index)
                    }
                }
            }
        }
    }
}
