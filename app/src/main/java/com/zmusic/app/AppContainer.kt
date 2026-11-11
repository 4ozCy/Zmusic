package com.zmusic.app

import android.content.Context
import androidx.room.Room
import com.zmusic.app.data.LibraryRepository
import com.zmusic.app.data.MediaStoreScanner
import com.zmusic.app.data.ZmusicDatabase
import com.zmusic.app.playback.PlayerController

/** Manual dependency container. Small enough that Hilt would add more than it saves right now. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database: ZmusicDatabase by lazy {
        Room.databaseBuilder(appContext, ZmusicDatabase::class.java, "zmusic.db").build()
    }

    val repository: LibraryRepository by lazy {
        LibraryRepository(database.trackDao(), MediaStoreScanner(appContext))
    }

    val player: PlayerController by lazy { PlayerController(appContext) }
}
