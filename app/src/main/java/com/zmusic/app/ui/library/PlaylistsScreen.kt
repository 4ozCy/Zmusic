package com.zmusic.app.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.components.Aurora
import com.zmusic.app.ui.components.Reveal
import com.zmusic.app.ui.theme.gradientTitleStyle

/** Placeholder: playlists are the next phase. Labelled honestly instead of faking controls. */
@Composable
fun PlaylistsScreen() {
    Box(Modifier.fillMaxSize()) {
        Aurora(Modifier.fillMaxWidth().height(300.dp))
        Reveal(0) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("Playlists", style = gradientTitleStyle(), modifier = Modifier.padding(top = 4.dp))
                Text(
                    "Not built yet. Playlists, favorites and history are coming in the next update.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
