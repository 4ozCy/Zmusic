package com.zmusic.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.GhostButton
import com.zmusic.app.ui.components.PrimaryButton
import com.zmusic.app.ui.components.Reveal

@Composable
fun PermissionScreen(denied: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    Reveal(0) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Eyebrow("Zmusic", color = MaterialTheme.colorScheme.primary)
            Text("Your music,\nright where it lives.", style = MaterialTheme.typography.displayMedium)
            Text(
                if (denied) {
                    "Without access to audio files Zmusic can't find your music. You can allow it again, or turn it on in system settings."
                } else {
                    "Zmusic needs permission to read the audio files on this device so it can build your library. Your music never leaves your phone."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton("Allow access to music", Icons.Rounded.LibraryMusic, onClick = onAllow)
            if (denied) {
                GhostButton("Open app settings", Icons.Rounded.Settings, onClick = onOpenSettings)
            }
        }
    }
}
