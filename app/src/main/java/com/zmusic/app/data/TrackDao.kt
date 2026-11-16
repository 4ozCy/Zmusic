package com.zmusic.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE available = 1 ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE available = 1 ORDER BY dateAdded DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<TrackEntity>>

    @Query(
        "SELECT * FROM tracks WHERE available = 1 AND (title LIKE '%' || :q || '%' " +
            "OR artist LIKE '%' || :q || '%' OR album LIKE '%' || :q || '%') " +
            "ORDER BY title COLLATE NOCASE ASC LIMIT 200",
    )
    fun search(q: String): Flow<List<TrackEntity>>

    /** Every row, missing ones included, for a scan to compare against. */
    @Query("SELECT * FROM tracks")
    suspend fun all(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE mediaStoreId = :id")
    suspend fun get(id: Long): TrackEntity?

    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    /** Rows already marked missing are left alone, so marking them again writes nothing. */
    @Query("UPDATE tracks SET available = 0 WHERE available = 1 AND mediaStoreId IN (:ids)")
    suspend fun markMissing(ids: List<Long>)
}
