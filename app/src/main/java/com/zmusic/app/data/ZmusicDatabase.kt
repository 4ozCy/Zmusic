package com.zmusic.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

// Schema export is off for now; turn it on and add real Migrations when version 2 appears.
@Database(entities = [TrackEntity::class], version = 1, exportSchema = false)
abstract class ZmusicDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
}
