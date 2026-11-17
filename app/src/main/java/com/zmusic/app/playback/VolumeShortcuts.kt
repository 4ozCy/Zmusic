package com.zmusic.app.playback

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.zmusic.app.debug.BugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the volume shortcuts are on. Kept on disk; the playback service reads it. */
class VolumeShortcutStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(KEY, on).apply()
    }

    private companion object {
        const val PREFS = "zmusic_playback"
        const val KEY = "volume_shortcuts"
    }
}

/**
 * Quick presses of a volume button as transport controls, for headphones with nothing else to
 * press: up twice skips to the next song, down twice goes back, up three times pauses.
 *
 * Android does not give volume keys to an app in the background, and a Bluetooth headset's volume
 * buttons are not keys at all: the headset only tells the phone its new volume. So this watches
 * the media volume itself. Every step counts as one press, [VolumeTaps] turns a run of them into
 * a shortcut, and the volume is put back to where it was before the run, because those presses
 * were never meant to change it.
 *
 * It only acts while a song is playing over Bluetooth, so the speaker and wired headphones keep
 * plain volume buttons. It cannot tell the headset's buttons from the phone's own, and it cannot
 * see a press that changes nothing, such as volume up at full volume.
 *
 * Main thread only, like the player it drives.
 */
class VolumeShortcutWatcher(
    context: Context,
    private val player: Player,
    private val enabled: () -> Boolean,
) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val taps = VolumeTaps()
    private var registered = false

    // The last step seen, to drop the copies some phones send of each one.
    private var lastFrom = -1
    private var lastTo = -1
    private var lastAtMs = 0L

    // The step this class causes itself when it puts the volume back.
    private var restoringTo = -1
    private var restoreUntilMs = 0L

    private val finish = Runnable { finishRun() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_STREAM, AudioManager.STREAM_MUSIC) != AudioManager.STREAM_MUSIC) return
            val to = intent.getIntExtra(EXTRA_VALUE, -1).takeIf { it >= 0 } ?: audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val from = intent.getIntExtra(EXTRA_PREVIOUS, -1).takeIf { it >= 0 } ?: lastTo
            onStep(from, to, SystemClock.uptimeMillis())
        }
    }

    fun start() {
        if (registered) return
        // The same broadcast ExoPlayer listens to for its own device volume. It is not in the SDK,
        // so a phone that refuses it only loses the shortcuts.
        runCatching {
            ContextCompat.registerReceiver(appContext, receiver, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }.onFailure { BugLog.w("Playback", "Volume shortcuts are unavailable on this phone", it) }
    }

    fun stop() {
        handler.removeCallbacks(finish)
        taps.reset()
        if (!registered) return
        registered = false
        appContext.unregisterReceiver(receiver)
    }

    private fun onStep(from: Int, to: Int, atMs: Long) {
        val copy = from == lastFrom && to == lastTo && atMs - lastAtMs < COPY_MS
        lastFrom = from
        lastTo = to
        lastAtMs = atMs
        if (copy || from < 0 || from == to) return
        if (to == restoringTo && atMs < restoreUntilMs) {
            restoringTo = -1
            return
        }
        if (!armed()) {
            handler.removeCallbacks(finish)
            taps.reset()
            return
        }
        taps.onStep(from, to, atMs)
        handler.removeCallbacks(finish)
        handler.postDelayed(finish, taps.windowMs)
    }

    private fun finishRun() {
        val run = taps.finish() ?: return
        if (!armed()) return
        // Volume first, so a skipped-to song starts at the level the listener had chosen.
        restoreVolume(run.volumeBefore)
        when (run.shortcut) {
            VolumeShortcut.Next -> player.seekToNextMediaItem()
            VolumeShortcut.Previous -> player.seekToPrevious()
            VolumeShortcut.Pause -> player.pause()
        }
        BugLog.i("Playback", "Volume shortcut: ${run.shortcut.label}")
    }

    private fun restoreVolume(volume: Int) {
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == volume) return
        restoringTo = volume
        restoreUntilMs = SystemClock.uptimeMillis() + RESTORE_MS
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) }
            .onFailure { BugLog.w("Playback", "Could not put the volume back after a volume shortcut", it) }
    }

    /** Shortcuts on, a song actually sounding, and the sound going to a Bluetooth device. */
    private fun armed(): Boolean = enabled() && player.isPlaying && onBluetooth()

    private fun onBluetooth(): Boolean {
        val outputs = if (Build.VERSION.SDK_INT >= 33) {
            audio.getAudioDevicesForAttributes(MEDIA)
        } else {
            // Before Android 13 there is no asking where music goes. A connected Bluetooth
            // output is where it goes unless the listener sent it somewhere else by hand.
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).asList()
        }
        return outputs.any { it.type in BLUETOOTH_OUTPUTS }
    }

    private companion object {
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREVIOUS = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

        /** The same step reported again within this long is a copy, not a second press. */
        const val COPY_MS = 30L

        /** How long the step from putting the volume back may take to be reported. */
        const val RESTORE_MS = 500L

        val MEDIA: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        // Some of these are newer than the oldest Android the app runs on. They compile to plain
        // numbers, which an older phone simply never reports.
        @SuppressLint("InlinedApi")
        val BLUETOOTH_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}
