package com.zmusic.app.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.Reveal

/** Placeholder: playlists are the next phase. Labelled honestly instead of faking controls. */
@Composable
fun PlaylistsScreen() {
    Reveal(0) {
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
            Eyebrow("Coming next", color = MaterialTheme.colorScheme.primary)
            Text("Playlists", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Not built yet. Playlists, favorites and listening history are the next update.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
