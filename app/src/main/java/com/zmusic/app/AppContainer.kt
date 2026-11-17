package com.zmusic.app

import android.content.Context
import androidx.room.Room
import com.zmusic.app.data.LibraryRepository
import com.zmusic.app.data.MediaStoreScanner
import com.zmusic.app.data.TrackFiles
import com.zmusic.app.data.ZmusicDatabase
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.VolumeShortcutStore
import com.zmusic.app.playback.eq.EqualizerStore

/** Manual dependency container. Small enough that Hilt would add more than it saves right now. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database: ZmusicDatabase by lazy {
        Room.databaseBuilder(appContext, ZmusicDatabase::class.java, "zmusic.db")
            .addMigrations(ZmusicDatabase.MIGRATION_1_2)
            .build()
    }

    val repository: LibraryRepository by lazy {
        LibraryRepository(database.trackDao(), MediaStoreScanner(appContext), TrackFiles(appContext))
    }

    val player: PlayerController by lazy { PlayerController(appContext) }

    /** Read by the playback service on the audio thread, written by the Sound panel. */
    val equalizer: EqualizerStore by lazy { EqualizerStore(appContext) }

    /** Written by the options sheet in Now Playing, read by the playback service. */
    val volumeShortcuts: VolumeShortcutStore by lazy { VolumeShortcutStore(appContext) }
}
