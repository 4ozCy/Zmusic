package com.zmusic.app.ui.songs

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.zmusic.app.data.FileNames
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne

/**
 * Renames the song's file. You edit the name without its extension, which stays as it is. The
 * whole name starts selected, so typing replaces it.
 */
@Composable
fun RenameDialog(track: TrackEntity, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val current = track.fileName ?: track.title
    val ext = FileNames.extension(current)
    var field by rememberSaveable(track.mediaStoreId, stateSaver = TextFieldValue.Saver) {
        val base = FileNames.baseName(current)
        mutableStateOf(TextFieldValue(base, selection = TextRange(0, base.length)))
    }
    val problem = FileNames.problem(field.text, current)
    val unchanged = FileNames.rename(field.text, current) == current
    val canRename = problem == null && !unchanged
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = Nocturne.Smoke,
        iconContentColor = Nocturne.Amber,
        titleContentColor = AppTheme.extended.text,
        textContentColor = AppTheme.extended.textSoft,
        icon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
        title = { Text("Rename file") },
        text = {
            Column {
                Text(
                    "This renames the file on your phone, and the song is called the new name here too.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    label = { Text("File name") },
                    suffix = if (ext.isEmpty()) null else { { Text(".$ext") } },
                    isError = problem != null && field.text.isNotBlank(),
                    supportingText = if (problem != null && field.text.isNotBlank()) { { Text(problem) } } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canRename) onRename(field.text) }),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(field.text) }, enabled = canRename, colors = accentButton()) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = quietButton()) { Text("Cancel") }
        },
    )
}

/** Asks before deleting. On Android 10 and later the system then asks once more, and does it. */
@Composable
fun DeleteDialog(track: TrackEntity, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val systemAsks = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = Nocturne.Smoke,
        iconContentColor = MaterialTheme.colorScheme.error,
        titleContentColor = AppTheme.extended.text,
        textContentColor = AppTheme.extended.textSoft,
        icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
        title = { Text("Delete this song?") },
        text = {
            Text(
                buildString {
                    append("“${track.fileName ?: track.title}”")
                    append(" will be deleted from your phone. You can't undo this.")
                    if (systemAsks) append(" Android asks once more before it deletes.")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = quietButton()) { Text("Cancel") }
        },
    )
}

@Composable
private fun accentButton() = ButtonDefaults.textButtonColors(
    contentColor = Nocturne.Amber,
    disabledContentColor = Color.White.copy(alpha = 0.32f),
)

@Composable
private fun quietButton() = ButtonDefaults.textButtonColors(contentColor = AppTheme.extended.textSoft)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AppTheme.extended.text,
    unfocusedTextColor = AppTheme.extended.text,
    cursorColor = Nocturne.Amber,
    focusedBorderColor = Nocturne.Amber,
    unfocusedBorderColor = Color.White.copy(alpha = 0.24f),
    focusedLabelColor = Nocturne.Amber,
    unfocusedLabelColor = AppTheme.extended.textDim,
    focusedSuffixColor = AppTheme.extended.textDim,
    unfocusedSuffixColor = AppTheme.extended.textDim,
    selectionColors = TextSelectionColors(handleColor = Nocturne.Amber, backgroundColor = Nocturne.Amber.copy(alpha = 0.32f)),
)
