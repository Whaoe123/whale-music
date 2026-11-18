package com.whale.music.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.whale.music.data.model.CustomPlaylist
import com.whale.music.data.model.CustomPlaylistSong
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomPlaylistDao {

    // ───────── 歌单 ─────────

    @Query("SELECT * FROM custom_playlists ORDER BY kind ASC, updatedAt DESC")
    fun playlists(): Flow<List<CustomPlaylist>>

    @Query("SELECT * FROM custom_playlists WHERE kind = :kind LIMIT 1")
    suspend fun playlistByKind(kind: Int): CustomPlaylist?

    @Query("SELECT * FROM custom_playlists WHERE id = :id")
    suspend fun playlist(id: Long): CustomPlaylist?

    @Query("SELECT COUNT(*) FROM custom_playlist_songs WHERE playlistId = :id")
    suspend fun songCount(id: Long): Int

    @Insert
    suspend fun insertPlaylist(playlist: CustomPlaylist): Long

    @Query("UPDATE custom_playlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE custom_playlists SET coverUrl = :cover, updatedAt = :now WHERE id = :id")
    suspend fun setCover(id: Long, cover: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM custom_playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM custom_playlist_songs WHERE playlistId = :id")
    suspend fun clearSongs(id: Long)

    // ───────── 歌单里的歌 ─────────

    @Query("SELECT * FROM custom_playlist_songs WHERE playlistId = :id ORDER BY position ASC, addedAt ASC")
    fun songs(id: Long): Flow<List<CustomPlaylistSong>>

    @Query("SELECT * FROM custom_playlist_songs WHERE playlistId = :id ORDER BY position ASC, addedAt ASC")
    suspend fun songsOnce(id: Long): List<CustomPlaylistSong>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSong(song: CustomPlaylistSong): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<CustomPlaylistSong>): List<Long>

    @Query("DELETE FROM custom_playlist_songs WHERE playlistId = :id AND songKey = :key")
    suspend fun removeSong(id: Long, key: String)

    @Query("SELECT COUNT(*) FROM custom_playlist_songs WHERE songKey = :key")
    suspend fun keyCount(key: String): Int

    /** 歌单里有几个歌单包含这首歌 —— 用于「已加入」提示 */
    @Query("SELECT playlistId FROM custom_playlist_songs WHERE songKey = :key")
    suspend fun playlistsContaining(key: String): List<Long>

    @Query("SELECT MAX(position) FROM custom_playlist_songs WHERE playlistId = :id")
    suspend fun maxPosition(id: Long): Int?

    /** 整个歌单换血（导入时用）：先清空再批量写，避免残留旧数据 */
    @Transaction
    suspend fun replaceSongs(id: Long, songs: List<CustomPlaylistSong>) {
        clearSongs(id)
        insertSongs(songs)
    }
}
