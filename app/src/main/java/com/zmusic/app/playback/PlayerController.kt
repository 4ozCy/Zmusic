package com.zmusic.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.debug.BugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** No artwork on purpose: the app is cover-free by design. */
fun TrackEntity.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaStoreId.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .build(),
    )
    .build()

/** When playback should stop on its own. */
sealed interface SleepTimer {
    data object Off : SleepTimer

    /** Pauses once [SystemClock.elapsedRealtime] reaches [endsAtElapsedMs]. */
    data class At(val endsAtElapsedMs: Long) : SleepTimer

    /** Pauses when the song playing now ends. */
    data object EndOfSong : SleepTimer
}

/**
 * UI-side handle to the playback service. All calls are queued until the
 * MediaController finishes connecting, so early taps are not lost.
 * Must be used from the main thread.
 *
 * It lives in the app container, not in a screen, so a sleep timer keeps running with the app
 * in the background.
 */
@OptIn(UnstableApi::class) // PlaybackService is marked unstable because it builds the player.
class PlayerController(context: Context) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null

    /** Bumped by every [playQueue]; a background fill that holds an older value stops. */
    private var queueToken = 0

    /** Songs that failed back to back. Reset as soon as one plays. */
    private var failuresInARow = 0
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Cold flow: only ticks while something (the visible UI) is collecting it. */
    val position: Flow<Long> = flow {
        while (true) {
            emit(controller?.currentPosition ?: 0L)
            delay(250)
        }
    }.distinctUntilChanged()

    /** Song starts seen so far: see [PlayerUiState.songStarts]. */
    private var songStarts = 0

    /** Media ids of deleted songs, so a queue still filling in the background skips them. */
    private val removedIds = HashSet<String>()

    private val _sleepTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleepTimer: StateFlow<SleepTimer> = _sleepTimer.asStateFlow()
    private val sleepCheck = Runnable { checkSleepTimer() }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val c = controller ?: return
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) songStarts++
            sync(c, rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            failuresInARow = 0
            // While paused the phone may sleep, and the posted check sleeps with it. Resuming puts
            // the timer back on real time. If its time passed with nothing playing, it has done its
            // job: let the music the user just started play.
            val timer = _sleepTimer.value as? SleepTimer.At ?: return
            handler.removeCallbacks(sleepCheck)
            val left = timer.endsAtElapsedMs - SystemClock.elapsedRealtime()
            if (left > 0) handler.postDelayed(sleepCheck, left) else _sleepTimer.value = SleepTimer.Off
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && _sleepTimer.value == SleepTimer.EndOfSong) {
                _sleepTimer.value = SleepTimer.Off
                BugLog.i("Playback", "Sleep timer stopped playback at the end of the song")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val title = controller?.currentMediaItem?.mediaMetadata?.title ?: "unknown track"
            BugLog.e("Playback", "${error.errorCodeName} while playing \"$title\": ${error.message}", error)
            // Corrupt/unsupported file: skip to the next track instead of getting stuck.
            val c = controller ?: return
            // The queue wraps round, so a run of unplayable files would otherwise skip forever.
            failuresInARow++
            if (failuresInARow >= minOf(c.mediaItemCount, MAX_FAILURES_IN_A_ROW)) {
                BugLog.w("Playback", "Stopped after $failuresInARow songs in a row failed to play")
                return
            }
            if (c.hasNextMediaItem()) {
                c.seekToNextMediaItem()
                c.prepare()
                c.play()
            }
        }
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }
                .onFailure { BugLog.e("Playback", "Could not connect to the playback service", it) }
                .getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            sync(c, rebuildQueue = true)
            pending.forEach { it(c) }
            pending.clear()
        }, mainExecutor)
    }

    private fun run(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending += block
    }

    private fun sync(c: MediaController, rebuildQueue: Boolean) {
        val item = c.currentMediaItem
        val md = item?.mediaMetadata
        val queue = if (rebuildQueue) rebuildQueue(c, _state.value.queue) else _state.value.queue
        _state.value = PlayerUiState(
            hasMedia = item != null,
            mediaId = item?.mediaId,
            title = md?.title?.toString().orEmpty(),
            artist = md?.artist?.toString().orEmpty(),
            album = md?.albumTitle?.toString().orEmpty(),
            isPlaying = c.playWhenReady &&
                c.playbackState != Player.STATE_ENDED &&
                c.playbackState != Player.STATE_IDLE,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            durationMs = c.duration.coerceAtLeast(0L),
            queue = queue,
            currentIndex = c.currentMediaItemIndex,
            songStarts = songStarts,
        )
    }

    /**
     * The controller's queue as [QueueEntry]s. A big library's queue fills in batches, and each
     * batch changes the timeline, so this runs dozens of times while the songs after the first
     * window arrive. Entries that are still right are reused instead of built again, and if
     * nothing changed at all, [old] itself comes back, so the state stays equal and the queue
     * on screen is left alone.
     */
    private fun rebuildQueue(c: MediaController, old: List<QueueEntry>): List<QueueEntry> {
        val n = c.mediaItemCount
        var same = old.size == n
        val out = ArrayList<QueueEntry>(n)
        for (i in 0 until n) {
            val m = c.getMediaItemAt(i)
            val title = m.mediaMetadata.title?.toString().orEmpty()
            val artist = m.mediaMetadata.artist?.toString().orEmpty()
            val prev = if (i < old.size) old[i] else null
            if (prev != null && prev.mediaId == m.mediaId && prev.title == title && prev.artist == artist) {
                out += prev
            } else {
                same = false
                out += QueueEntry(index = i, mediaId = m.mediaId, title = title, artist = artist)
            }
        }
        return if (same) old else out
    }

    /**
     * Big libraries are not sent in one go: building and shipping thousands of MediaItems on the
     * main thread made tapping a song feel laggy. A window around the tapped song (100 before,
     * 300 after) is queued first, so the song starts at once. The rest of the list then follows
     * in small batches: the songs after the window, then the ones before it, so the queue runs on
     * past the last song and round to the first.
     */
    fun playQueue(tracks: List<TrackEntity>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val idx = startIndex.coerceIn(0, tracks.lastIndex)
        val windowed = tracks.size > WINDOW_THRESHOLD
        val from = if (windowed) (idx - WINDOW_BEFORE).coerceAtLeast(0) else 0
        val to = if (windowed) (idx + WINDOW_AFTER).coerceAtMost(tracks.size) else tracks.size
        val items = tracks.subList(from, to).map { it.toMediaItem() }
        val token = ++queueToken
        run { c ->
            if (token != queueToken) return@run
            failuresInARow = 0
            c.setMediaItems(items, idx - from, 0L)
            c.prepare()
            c.play()
            val left = tracks.size - items.size
            if (left > 0) handler.postDelayed({ fillQueue(c, tracks, to, left, token) }, FILL_GAP_MS)
        }
    }

    /**
     * Appends [left] more songs to the queue, starting at [next] and wrapping past the end of
     * [tracks], one batch per call. Stops if another [playQueue] has replaced the queue.
     */
    private fun fillQueue(c: MediaController, tracks: List<TrackEntity>, next: Int, left: Int, token: Int) {
        if (token != queueToken || left <= 0) return
        val n = minOf(FILL_BATCH, left)
        val batch = List(n) { tracks[(next + it) % tracks.size] }
            .filter { removedIds.isEmpty() || it.mediaStoreId.toString() !in removedIds }
        if (batch.isNotEmpty()) c.addMediaItems(batch.map { it.toMediaItem() })
        handler.postDelayed({ fillQueue(c, tracks, next + n, left - n, token) }, FILL_GAP_MS)
    }

    fun togglePlayPause() = run { c ->
        if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
        }
    }

    fun next() = run { it.seekToNextMediaItem() }
    fun previous() = run { it.seekToPrevious() }
    fun seekTo(ms: Long) = run { it.seekTo(ms) }
    fun skipTo(index: Int) = run { it.seekTo(index, 0L) }
    fun removeAt(index: Int) = run { it.removeMediaItem(index) }
    fun setShuffle(enabled: Boolean) = run { it.shuffleModeEnabled = enabled }
    fun toggleShuffle() = run { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = run { it.repeatMode = nextRepeatMode(it.repeatMode) }

    /**
     * Puts [track] straight after the current song, or plays it now if nothing is queued.
     * With shuffle on, the player slots it into the shuffle order wherever it likes.
     */
    fun playNext(track: TrackEntity) = run { c ->
        if (c.mediaItemCount == 0) playQueue(listOf(track), 0) else c.addMediaItem(c.currentMediaItemIndex + 1, track.toMediaItem())
    }

    /** Adds [track] to the end of the queue, or plays it now if nothing is queued. */
    fun addToQueue(track: TrackEntity) = run { c ->
        if (c.mediaItemCount == 0) playQueue(listOf(track), 0) else c.addMediaItem(track.toMediaItem())
    }

    /** Takes every copy of a deleted song out of the queue. If it was playing, the next one starts. */
    fun removeTrack(mediaStoreId: Long) {
        val id = mediaStoreId.toString()
        removedIds += id
        run { c ->
            for (i in c.mediaItemCount - 1 downTo 0) {
                if (c.getMediaItemAt(i).mediaId == id) c.removeMediaItem(i)
            }
        }
    }

    /**
     * Refreshes the title, artist and album of a song already in the queue (after a rename).
     * The file and its URI are unchanged, so the song keeps playing without a gap.
     */
    fun updateTrack(track: TrackEntity) = run { c ->
        val id = track.mediaStoreId.toString()
        for (i in 0 until c.mediaItemCount) {
            val item = c.getMediaItemAt(i)
            if (item.mediaId != id) continue
            val md = item.mediaMetadata
            val stale = md.title?.toString() != track.title ||
                md.artist?.toString() != track.artist ||
                md.albumTitle?.toString() != track.album
            if (stale) c.replaceMediaItem(i, track.toMediaItem())
        }
    }

    /** Pauses playback [minutes] from now. Replaces any timer already set. */
    fun sleepIn(minutes: Int) {
        cancelSleepTimer()
        val delayMs = minutes * 60_000L
        _sleepTimer.value = SleepTimer.At(SystemClock.elapsedRealtime() + delayMs)
        handler.postDelayed(sleepCheck, delayMs)
        BugLog.i("Playback", "Sleep timer set for $minutes min")
    }

    /** Pauses when the current song ends. The service does it on the player itself, so no next song leaks in. */
    fun sleepAtEndOfSong() {
        cancelSleepTimer()
        _sleepTimer.value = SleepTimer.EndOfSong
        run { it.sendCustomCommand(ZmusicCommands.stopAfterCurrent, stopAfterArgs(true)) }
        BugLog.i("Playback", "Sleep timer set for the end of the song")
    }

    fun cancelSleepTimer() {
        handler.removeCallbacks(sleepCheck)
        if (_sleepTimer.value == SleepTimer.EndOfSong) {
            run { it.sendCustomCommand(ZmusicCommands.stopAfterCurrent, stopAfterArgs(false)) }
        }
        _sleepTimer.value = SleepTimer.Off
    }

    private fun checkSleepTimer() {
        val timer = _sleepTimer.value as? SleepTimer.At ?: return
        val left = timer.endsAtElapsedMs - SystemClock.elapsedRealtime()
        if (left > 0) {
            handler.postDelayed(sleepCheck, left)
            return
        }
        _sleepTimer.value = SleepTimer.Off
        run { it.pause() }
        BugLog.i("Playback", "Sleep timer paused playback")
    }

    private fun stopAfterArgs(enabled: Boolean) = Bundle().apply { putBoolean(ZmusicCommands.KEY_ENABLED, enabled) }

    private companion object {
        const val WINDOW_THRESHOLD = 400
        const val WINDOW_BEFORE = 100
        const val WINDOW_AFTER = 300
        const val FILL_BATCH = 250
        const val FILL_GAP_MS = 60L
        const val MAX_FAILURES_IN_A_ROW = 8
    }
}
