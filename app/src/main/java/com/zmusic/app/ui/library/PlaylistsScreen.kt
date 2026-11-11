package com.zmusic.app.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Placeholder: playlists are the next phase. Labelled honestly instead of faking controls. */
@Composable
fun PlaylistsScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Playlists", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Not built yet. Playlists, favorites and history are planned for the next phase.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
