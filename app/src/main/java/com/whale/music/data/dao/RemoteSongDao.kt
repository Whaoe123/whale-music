package com.whale.music.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.whale.music.data.model.RemoteSong
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteSongDao {

    @Query("SELECT * FROM remote_songs ORDER BY dateAdded DESC")
    fun getAll(): Flow<List<RemoteSong>>

    @Query("SELECT * FROM remote_songs")
    suspend fun getAllOnce(): List<RemoteSong>

    @Query("SELECT songId FROM remote_songs")
    fun getAllIds(): Flow<List<String>>

    @Query("SELECT * FROM remote_songs WHERE songId = :songId LIMIT 1")
    suspend fun getById(songId: String): RemoteSong?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: RemoteSong)

    @Query("DELETE FROM remote_songs WHERE songId = :songId")
    suspend fun delete(songId: String)

    @Query("SELECT COUNT(*) FROM remote_songs WHERE songId = :songId")
    suspend fun count(songId: String): Int
}
