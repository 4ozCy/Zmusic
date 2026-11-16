package com.zmusic.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Schema export is off for now; turn it on before the schema grows further.
@Database(entities = [TrackEntity::class], version = 2, exportSchema = false)
abstract class ZmusicDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        /** Version 2 stores each file's name, for renaming. The next scan fills it in. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN fileName TEXT")
            }
        }
    }
}
