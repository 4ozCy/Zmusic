package com.zmusic.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.zmusic.app.MainActivity
import com.zmusic.app.ZmusicApp
import com.zmusic.app.playback.eq.EqualizerAudioProcessor

/**
 * The single owner of the ExoPlayer. The UI only talks to it through a MediaController,
 * so navigating screens (or closing the Activity) never creates or destroys the player.
 *
 * The media notification and the lock screen get shuffle and repeat buttons next to the
 * transport controls. They are custom session commands, and their icons follow the player's
 * state whichever screen or controller changed it.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        val equalizerStore = (application as ZmusicApp).container.equalizer
        // Same renderers as default, plus the equalizer and a pass-through analyser feeding the
        // Now Playing visuals. The analyser comes second so the visuals follow what you hear.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val equalizer = EqualizerAudioProcessor { equalizerStore.settings.value }
                val levels = LevelAudioProcessor()
                val sink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(equalizer, levels))
                    .build()
                // Stamps the analysis with media time and publishes the playout clock, so the
                // visuals land on the beat you hear instead of the sink's buffer ahead of it.
                return TimedAudioSink(sink, levels)
            }
        }

        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer = player
        // After the last song the queue starts over from the first, unless the listener turns
        // repeat off.
        player.repeatMode = Player.REPEAT_MODE_ALL

        // The Visualizer in AudioFrequencyTracker attaches to this session.
        AudioSession.id.value = player.audioSessionId
        player.addListener(
            object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    AudioSession.id.value = audioSessionId
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refreshButtons()

                override fun onRepeatModeChanged(repeatMode: Int) = refreshButtons()

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    // "Stop after this song" is a one-off: once it has paused, songs run on again.
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                        player.pauseAtEndOfMediaItems = false
                    }
                }
            },
        )

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(Callback())
            .setCustomLayout(buttonsFor(player))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        exoPlayer = null
        AudioSession.id.value = 0
        super.onDestroy()
    }

    private fun refreshButtons() {
        val s = session ?: return
        s.setCustomLayout(buttonsFor(s.player))
    }

    /**
     * Shuffle and repeat for the notification and the lock screen. Media notifications are
     * drawn in one colour, so each state has its own icon: a filled badge means on.
     */
    private fun buttonsFor(player: Player): List<CommandButton> {
        val shuffle = player.shuffleModeEnabled
        val repeat = player.repeatMode
        return listOf(
            CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(if (shuffle) "Shuffle on" else "Shuffle off")
                .setSessionCommand(ZmusicCommands.toggleShuffle)
                .build(),
            CommandButton.Builder(
                when (repeat) {
                    Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                    Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                    else -> CommandButton.ICON_REPEAT_OFF
                },
            )
                .setDisplayName(
                    when (repeat) {
                        Player.REPEAT_MODE_ONE -> "Repeat one"
                        Player.REPEAT_MODE_ALL -> "Repeat all"
                        else -> "Repeat off"
                    },
                )
                .setSessionCommand(ZmusicCommands.cycleRepeat)
                .build(),
        )
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(ZmusicCommands.toggleShuffle)
                .add(ZmusicCommands.cycleRepeat)
                .add(ZmusicCommands.stopAfterCurrent)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(buttonsFor(session.player))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            when (customCommand.customAction) {
                ZmusicCommands.TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                ZmusicCommands.CYCLE_REPEAT -> player.repeatMode = nextRepeatMode(player.repeatMode)
                ZmusicCommands.STOP_AFTER_CURRENT ->
                    exoPlayer?.pauseAtEndOfMediaItems = args.getBoolean(ZmusicCommands.KEY_ENABLED, true)
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
