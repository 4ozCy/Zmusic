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

    @Query("SELECT mediaStoreId FROM tracks")
    suspend fun allIds(): List<Long>

    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET available = 0 WHERE mediaStoreId IN (:ids)")
    suspend fun markMissing(ids: List<Long>)
}
