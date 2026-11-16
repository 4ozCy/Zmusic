package com.zmusic.app.ui.songs

import android.content.IntentSender
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zmusic.app.data.FileChange
import com.zmusic.app.data.LibraryRepository
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.debug.BugLog
import com.zmusic.app.playback.PlayerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the screen has to do for a rename or delete in flight. */
@Immutable
data class SongActionsUiState(
    /** A system dialog to show. Report it with [SongActionsViewModel.onApprovalLaunched], then its result. */
    val approval: IntentSender? = null,
    /** A runtime permission to ask for (Android 9 and older). */
    val permission: String? = null,
    /** A change is running; further renames and deletes wait. */
    val working: Boolean = false,
)

/**
 * Song actions from the options sheet: queueing, and renaming or deleting the file. A rename or
 * delete may need the user's approval in a system dialog, or a permission, before it can
 * happen; this keeps the change that is waiting and runs it again once the answer comes back.
 */
class SongActionsViewModel(
    private val repository: LibraryRepository,
    private val player: PlayerController,
) : ViewModel() {

    private sealed interface Pending {
        val track: TrackEntity

        data class Delete(override val track: TrackEntity) : Pending
        data class Rename(override val track: TrackEntity, val input: String) : Pending
    }

    private val _state = MutableStateFlow(SongActionsUiState())
    val state: StateFlow<SongActionsUiState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Short confirmations and failures to show the user. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private var pending: Pending? = null

    fun playNext(track: TrackEntity) {
        player.playNext(track)
        notice("“${track.title}” plays next")
    }

    fun addToQueue(track: TrackEntity) {
        player.addToQueue(track)
        notice("Added “${track.title}” to the queue")
    }

    fun delete(track: TrackEntity) = start(Pending.Delete(track))

    fun rename(track: TrackEntity, input: String) = start(Pending.Rename(track, input))

    /** The screen launched [SongActionsUiState.approval]; it must not launch it twice. */
    fun onApprovalLaunched() = _state.update { it.copy(approval = null) }

    fun onApprovalResult(approved: Boolean) {
        if (approved) {
            attempt(approved = true)
        } else {
            pending = null
            notice("Nothing was changed")
        }
    }

    /** The screen asked for [SongActionsUiState.permission]; it must not ask twice. */
    fun onPermissionRequested() = _state.update { it.copy(permission = null) }

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            attempt(approved = false)
        } else {
            pending = null
            notice("Zmusic needs storage access to change files")
        }
    }

    private fun start(next: Pending) {
        // A change still waiting for an answer is replaced: its dialog is no longer on screen.
        if (_state.value.working) return
        pending = next
        attempt(approved = false)
    }

    private fun attempt(approved: Boolean) {
        val p = pending ?: return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            val result = try {
                when (p) {
                    is Pending.Delete -> repository.delete(p.track, approved).also {
                        if (it is FileChange.Done) player.removeTrack(p.track.mediaStoreId)
                    }
                    is Pending.Rename -> {
                        val (change, fresh) = repository.rename(p.track, p.input, approved)
                        if (change is FileChange.Done && fresh != null) player.updateTrack(fresh)
                        change
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BugLog.e("Files", "Changing \"${p.track.title}\" failed", e)
                FileChange.Failed("Something went wrong. The Bugs tab has the details.")
            }
            when (result) {
                is FileChange.Done -> {
                    pending = null
                    notice(
                        when (p) {
                            is Pending.Delete -> "Deleted “${p.track.title}”"
                            is Pending.Rename -> "Renamed to “${result.fileName ?: p.input}”"
                        },
                    )
                }
                is FileChange.NeedsApproval -> _state.update { it.copy(approval = result.request) }
                is FileChange.NeedsPermission -> _state.update { it.copy(permission = result.permission) }
                is FileChange.Failed -> {
                    pending = null
                    notice(result.reason)
                }
            }
            _state.update { it.copy(working = false) }
        }
    }

    private fun notice(text: String) {
        _notices.tryEmit(text)
    }
}
