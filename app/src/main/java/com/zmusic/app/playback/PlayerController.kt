package com.zmusic.app.playback

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.zmusic.app.data.TrackEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

private const val KEY_ALBUM_ID = "zmusic.albumId"
private const val KEY_URI = "zmusic.uri"
private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

/** Album id and uri ride along in the metadata extras so the UI can load artwork for any queue item. */
fun TrackEntity.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaStoreId.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(ContentUris.withAppendedId(ALBUM_ART_BASE, albumId))
            .setExtras(
                Bundle().apply {
                    putLong(KEY_ALBUM_ID, albumId)
                    putString(KEY_URI, uri)
                },
            )
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
    private var controller: MediaController? = null
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

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val c = controller ?: return
            sync(c, rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED))
        }

        override fun onPlayerError(error: PlaybackException) {
            // Corrupt/unsupported file: skip to the next track instead of getting stuck.
            val c = controller ?: return
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
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
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
        val extras = md?.extras
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
            albumId = extras?.getLong(KEY_ALBUM_ID) ?: 0L,
            trackUri = extras?.getString(KEY_URI).orEmpty(),
            isPlaying = c.playWhenReady &&
                c.playbackState != Player.STATE_ENDED &&
                c.playbackState != Player.STATE_IDLE,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            durationMs = c.duration.coerceAtLeast(0L),
            queue = queue,
            currentIndex = c.currentMediaItemIndex,
        )
    }

    /**
     * Big libraries are not sent whole: building and shipping thousands of MediaItems on the main
     * thread made tapping a song feel laggy. Instead a window around the tapped song is queued
     * (100 before, 300 after), which is plenty for normal listening and starts instantly.
     */
    fun playQueue(tracks: List<TrackEntity>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val idx = startIndex.coerceIn(0, tracks.lastIndex)
        val windowed = tracks.size > WINDOW_THRESHOLD
        val from = if (windowed) (idx - WINDOW_BEFORE).coerceAtLeast(0) else 0
        val to = if (windowed) (idx + WINDOW_AFTER).coerceAtMost(tracks.size) else tracks.size
        val items = tracks.subList(from, to).map { it.toMediaItem() }
        run { c ->
            c.setMediaItems(items, idx - from, 0L)
            c.prepare()
            c.play()
        }
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
    }
}
