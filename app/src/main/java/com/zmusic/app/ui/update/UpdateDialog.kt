package com.zmusic.app.ui.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zmusic.app.debug.BugLog
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne
import com.zmusic.app.update.AppRelease
import com.zmusic.app.update.UpdateState
import com.zmusic.app.update.Updater
import java.util.Locale

/**
 * Checks for a new build whenever the app comes to the front (at most once an hour) and, if
 * there is one, offers it. Tapping Update downloads it and hands it to Android, which asks once
 * more on its own screen. The first time, Android also needs "Install unknown apps" turned on
 * for Zmusic; this sends the user there and carries on by itself when they come back.
 */
@Composable
fun UpdateHost(updater: Updater) {
    val context = LocalContext.current
    val state by updater.state.collectAsStateWithLifecycle()
    val confirm by updater.confirm.collectAsStateWithLifecycle()
    // Set when Update sent the user to settings, so coming back with permission starts the download.
    var waitingForPermission by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (waitingForPermission && updater.canInstall()) {
            waitingForPermission = false
            updater.install()
        }
        updater.checkIfDue()
    }

    // Android's own "Update this app?" screen. Started from here, the visible activity, so
    // Android's background-start rules never swallow it.
    LaunchedEffect(confirm) {
        val intent = confirm ?: return@LaunchedEffect
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            BugLog.w("Update", "No installer screen to show", e)
        }
        updater.onConfirmShown()
    }

    val onUpdate: () -> Unit = {
        if (updater.canInstall()) {
            updater.install()
        } else {
            waitingForPermission = true
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                )
            } catch (e: ActivityNotFoundException) {
                waitingForPermission = false
                BugLog.w("Update", "Could not open the install permission screen", e)
                updater.install() // Android's install screen asks for the permission itself.
            }
        }
    }

    when (val s = state) {
        UpdateState.Idle -> Unit
        is UpdateState.Available -> OfferDialog(
            release = s.release,
            installed = updater.installedVersion,
            needsPermission = !updater.canInstall(),
            onUpdate = onUpdate,
            onLater = updater::later,
        )
        is UpdateState.Downloading -> ProgressDialog(
            title = "Downloading update",
            body = if (s.progress >= 0f) {
                "${(s.progress * 100).toInt()}%" + sizeSuffix(s.release)
            } else {
                "Downloading…"
            },
            progress = s.progress,
            onCancel = updater::cancelDownload,
        )
        is UpdateState.Installing -> ProgressDialog(
            title = "Installing",
            body = "Tap Update on Android's screen. Zmusic closes while it installs. Open it again after.",
            progress = -1f,
            onCancel = updater::later,
            cancelLabel = "Hide",
        )
        is UpdateState.Failed -> FailedDialog(message = s.message, onRetry = updater::install, onClose = updater::later)
    }
}

@Composable
private fun OfferDialog(
    release: AppRelease,
    installed: Long,
    needsPermission: Boolean,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        shape = RoundedCornerShape(28.dp),
        containerColor = Nocturne.Smoke,
        iconContentColor = Nocturne.Amber,
        titleContentColor = AppTheme.extended.text,
        textContentColor = AppTheme.extended.textSoft,
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
        title = { Text("Update available") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Zmusic ${release.versionName} is out. You have build $installed." + sizeSuffix(release, lead = " Size: "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (release.notes.isNotBlank()) {
                    Text(
                        "What's new",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppTheme.extended.text,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    Text(release.notes, style = MaterialTheme.typography.bodyMedium)
                }
                if (needsPermission) {
                    Text(
                        "First time only: Android will ask you to allow Zmusic to install apps. Turn it on, then come back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.extended.textDim,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate, colors = accentButton()) { Text("Update") }
        },
        dismissButton = {
            TextButton(onClick = onLater, colors = quietButton()) { Text("Later") }
        },
    )
}

@Composable
private fun ProgressDialog(
    title: String,
    body: String,
    progress: Float,
    onCancel: () -> Unit,
    cancelLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        shape = RoundedCornerShape(28.dp),
        containerColor = Nocturne.Smoke,
        iconContentColor = Nocturne.Amber,
        titleContentColor = AppTheme.extended.text,
        textContentColor = AppTheme.extended.textSoft,
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column {
                val track = Color.White.copy(alpha = 0.12f)
                if (progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = Nocturne.Amber,
                        trackColor = track,
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(
                        color = Nocturne.Amber,
                        trackColor = track,
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, colors = quietButton()) { Text(cancelLabel) }
        },
    )
}

@Composable
private fun FailedDialog(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        shape = RoundedCornerShape(28.dp),
        containerColor = Nocturne.Smoke,
        iconContentColor = MaterialTheme.colorScheme.error,
        titleContentColor = AppTheme.extended.text,
        textContentColor = AppTheme.extended.textSoft,
        icon = { Icon(Icons.Rounded.ErrorOutline, contentDescription = null) },
        title = { Text("Update failed") },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onRetry, colors = accentButton()) { Text("Try again") }
        },
        dismissButton = {
            TextButton(onClick = onClose, colors = quietButton()) { Text("Close") }
        },
    )
}

private fun sizeSuffix(release: AppRelease, lead: String = " of "): String {
    if (release.apkSize <= 0) return ""
    val mb = release.apkSize / (1024.0 * 1024.0)
    return lead + String.format(Locale.US, "%.1f MB", mb) + if (lead == " of ") "" else "."
}

@Composable
private fun accentButton() = ButtonDefaults.textButtonColors(contentColor = Nocturne.Amber)

@Composable
private fun quietButton() = ButtonDefaults.textButtonColors(contentColor = AppTheme.extended.textSoft)
