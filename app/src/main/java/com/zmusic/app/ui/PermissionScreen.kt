package com.zmusic.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.components.AmbientAudioShaderBackground
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.GlassPill
import com.zmusic.app.ui.components.KineticGyroscope
import com.zmusic.app.ui.components.Reveal

@Composable
fun PermissionScreen(denied: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AmbientAudioShaderBackground(Modifier.fillMaxSize())
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // The idle gyroscope doubles as the brand mark; it is the same sculpture that comes alive on playback.
            Reveal(0) { KineticGyroscope(Modifier.padding(bottom = 28.dp).size(104.dp), halo = false, segments = 60) }
            Reveal(80) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                }
            }
            Reveal(160) {
                Column(
                    Modifier.padding(top = 28.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // The one solid control on this screen: it is the single thing to do.
                    GlassPill("Allow access to music", Icons.Rounded.LibraryMusic, prominent = true, onClick = onAllow)
                    if (denied) {
                        GlassPill("Open app settings", Icons.Rounded.Settings, onClick = onOpenSettings)
                    }
                }
            }
        }
    }
}
