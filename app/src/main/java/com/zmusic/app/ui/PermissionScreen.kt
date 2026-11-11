package com.zmusic.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PermissionScreen(denied: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Zmusic", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (denied) {
                "Without access to audio files Zmusic can't find your music. You can allow it again, or turn it on in system settings."
            } else {
                "Zmusic needs permission to read the audio files on this device so it can build your library. Your music never leaves your phone."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAllow) { Text("Allow access to music") }
        if (denied) {
            OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
        }
    }
}
