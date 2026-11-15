package com.zmusic.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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

/**
 * UI-side handle to the playback service. All calls are queued until the
 * MediaController finishes connecting, so early taps are not lost.
 * Must be used from the main thread.
 */
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

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val c = controller ?: return
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) songStarts++
            sync(c, rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) failuresInARow = 0
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
        val queue = if (rebuildQueue) {
            List(c.mediaItemCount) { i ->
                val m = c.getMediaItemAt(i)
                QueueEntry(
                    index = i,
                    mediaId = m.mediaId,
                    title = m.mediaMetadata.title?.toString().orEmpty(),
                    artist = m.mediaMetadata.artist?.toString().orEmpty(),
                )
            }
        } else {
            _state.value.queue
        }
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
        c.addMediaItems(List(n) { tracks[(next + it) % tracks.size].toMediaItem() })
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

    fun cycleRepeat() = run {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private companion object {
        const val WINDOW_THRESHOLD = 400
        const val WINDOW_BEFORE = 100
        const val WINDOW_AFTER = 300
        const val FILL_BATCH = 250
        const val FILL_GAP_MS = 60L
        const val MAX_FAILURES_IN_A_ROW = 8
    }
}
