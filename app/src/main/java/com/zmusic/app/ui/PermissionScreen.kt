package com.zmusic.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.components.AmbientGlow
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.RadialSpectrum
import com.zmusic.app.ui.components.Reveal
import com.zmusic.app.ui.components.TextAction

@Composable
fun PermissionScreen(denied: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AmbientGlow(Modifier.fillMaxWidth().fillMaxHeight(0.6f))
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // The idle ring doubles as the brand mark; it is the same visual that comes alive on playback.
            Reveal(0) { RadialSpectrum(Modifier.padding(bottom = 32.dp).size(88.dp)) }
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
                Column(Modifier.padding(top = 28.dp), horizontalAlignment = Alignment.Start) {
                    // The one filled control on this screen: it is the single thing to do.
                    Button(
                        onClick = onAllow,
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        modifier = Modifier.heightIn(min = 52.dp),
                    ) { Text("Allow access to music") }
                    if (denied) {
                        TextAction("Open app settings", Icons.Rounded.Settings, Modifier.padding(top = 8.dp), onClick = onOpenSettings)
                    }
                }
            }
        }
    }
}
