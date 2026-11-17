package com.zmusic.app.ui.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.ui.components.HairlineDivider
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne
import com.zmusic.app.ui.theme.TimeStyle
import com.zmusic.app.ui.theme.ZmusicTheme
import kotlinx.coroutines.launch

/** Under the shortcuts row while they are on: the three of them, so nobody has to remember. */
const val VOLUME_SHORTCUTS_ON = "On. Volume up twice: next song. Down twice: back. Up three times: pause."

/** What the options sheet can do. Null entries are left out of the list. */
data class SongOptionActions(
    val onPlayNext: (() -> Unit)? = null,
    val onAddToQueue: (() -> Unit)? = null,
    val onSleepTimer: (() -> Unit)? = null,
    val onVolumeShortcuts: (() -> Unit)? = null,
    val onShare: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/**
 * Everything you can do with one song, opened by a long press on a row or tile, or by the
 * options button in Now Playing. The sheet slides away before the action runs, so a dialog
 * that follows does not appear on top of it.
 *
 * @param sleepDetail state of the sleep timer, shown under its row ("Off", "Stops in 12:03").
 * @param shortcutsDetail state of the Bluetooth volume shortcuts, shown under their row.
 */
@Composable
fun SongOptionsSheet(
    track: TrackEntity,
    actions: SongOptionActions,
    onDismiss: () -> Unit,
    sleepDetail: String? = null,
    shortcutsDetail: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun choose(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Nocturne.Smoke,
        contentColor = AppTheme.extended.text,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = { SheetGrabber() },
    ) {
        SongOptionsContent(track, actions, sleepDetail, shortcutsDetail, onChoose = ::choose)
    }
}

/** The sheet's body: who the song is, then one row per action. [onChoose] runs a row's action. */
@Composable
fun SongOptionsContent(
    track: TrackEntity,
    actions: SongOptionActions,
    sleepDetail: String?,
    shortcutsDetail: String?,
    onChoose: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleLarge,
                color = AppTheme.extended.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${track.artist} · ${track.album}",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.extended.textSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            track.fileName?.let { name ->
                Text(
                    text = name,
                    style = TimeStyle,
                    color = AppTheme.extended.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        HairlineDivider(Modifier.padding(top = 16.dp, bottom = 6.dp))
        actions.onPlayNext?.let { OptionRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") { onChoose(it) } }
        actions.onAddToQueue?.let { OptionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to queue") { onChoose(it) } }
        actions.onSleepTimer?.let { OptionRow(Icons.Rounded.Bedtime, "Sleep timer", detail = sleepDetail) { onChoose(it) } }
        actions.onVolumeShortcuts?.let {
            OptionRow(Icons.Rounded.Bluetooth, "Bluetooth volume shortcuts", detail = shortcutsDetail) { onChoose(it) }
        }
        OptionRow(Icons.Rounded.Share, "Share") { onChoose(actions.onShare) }
        OptionRow(Icons.Rounded.DriveFileRenameOutline, "Rename file") { onChoose(actions.onRename) }
        OptionRow(Icons.Rounded.DeleteOutline, "Delete file", tint = MaterialTheme.colorScheme.error) { onChoose(actions.onDelete) }
    }
}

@Preview(name = "Song options", widthDp = 360, showBackground = true, backgroundColor = 0xFF14151A)
@Composable
private fun SongOptionsPreview() {
    ZmusicTheme {
        SongOptionsContent(
            track = TrackEntity(
                mediaStoreId = 1, uri = "", title = "Midnight City", artist = "M83", album = "Hurry Up, We're Dreaming",
                albumId = 1, durationMs = 243_000, dateAdded = 0, sizeBytes = 0, mimeType = "audio/mpeg", relativePath = null,
                fileName = "01 Midnight City.mp3",
            ),
            actions = SongOptionActions(onPlayNext = {}, onAddToQueue = {}, onVolumeShortcuts = {}),
            sleepDetail = null,
            shortcutsDetail = VOLUME_SHORTCUTS_ON,
            onChoose = {},
        )
    }
}

/** The same grabber as the Now Playing sheet, so every sheet in the app reads as one family. */
@Composable
fun SheetGrabber() {
    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(36.dp).height(4.dp).background(Color.White.copy(alpha = 0.22f), CircleShape))
    }
}

@Composable
fun OptionRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = AppTheme.extended.text,
    detail: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 18.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSoft)
            }
        }
    }
}
