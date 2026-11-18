package com.whale.music.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.core.net.toUri
import com.whale.music.R
import com.whale.music.data.dao.SongDao
import com.whale.music.data.model.Song
import com.whale.music.util.CoverResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

internal const val MIN_SONG_DURATION_MS = 2 * 60 * 1_000L

internal fun isEligibleSongDuration(durationMs: Long): Boolean =
    durationMs > MIN_SONG_DURATION_MS

@Singleton
class MusicRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val songDao: SongDao
) {
    val allSongs: Flow<List<Song>> = songDao.getAllSongs()
    val favoriteSongs: Flow<List<Song>> = songDao.getFavoriteSongs()

    suspend fun toggleFavorite(songId: Long) = songDao.toggleFavorite(songId)

    suspend fun getSongById(id: Long): Song? = songDao.getSongById(id)

    /**
     * 删除本地歌曲：先删 MediaStore 条目（文件随之删除），再清数据库行和下载封面。
     * @return 错误信息；null = 成功
     */
    suspend fun deleteSong(song: Song): String? = withContext(Dispatchers.IO) {
        val uri = try {
            song.uri.toUri()
        } catch (e: Exception) {
            return@withContext "找不到这个文件"
        }
        try {
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted <= 0) return@withContext "系统没能删除这个文件"
            CoverResolver.removeDownloadCover(song.id)
            songDao.deleteById(song.id)
            null
        } catch (e: SecurityException) {
            // 非本应用写入的文件（例如用户自己拷贝进来的歌）在 Android 10+ 会被系统拦下
            "系统限制：只能删除本应用下载的歌曲"
        } catch (e: Exception) {
            "删除失败：${e.message ?: "未知错误"}"
        }
    }

    suspend fun getCount(): Int = songDao.getCount()

    fun getSongByIdFlow(id: Long): Flow<Song?> = songDao.getSongByIdFlow(id)

    /** 扫描设备本地音乐文件 */
    suspend fun scanLocalMusic(): Int = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATE_ADDED
        )

        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" AND ${MediaStore.Audio.Media.DURATION} > ?")
        }
        val selectionArgs = arrayOf(MIN_SONG_DURATION_MS.toString())
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )
                songs.add(
                    Song(
                        id = id,
                        title = cursor.getString(titleCol) ?: context.getString(R.string.unknown_title),
                        artist = cursor.getString(artistCol) ?: context.getString(R.string.unknown_artist),
                        album = cursor.getString(albumCol) ?: context.getString(R.string.unknown_album),
                        duration = cursor.getLong(durationCol),
                        albumId = cursor.getLong(albumIdCol),
                        uri = contentUri.toString(),
                        dateAdded = cursor.getLong(dateAddedCol)
                    )
                )
            }
        }

        // 再次校验扫描结果，避免个别系统的 MediaStore 忽略查询中的时长条件。
        val validSongs = songs.filter { isEligibleSongDuration(it.duration) }

        // 保留旧收藏状态
        val favoriteIds = songDao.getFavoriteIds().toSet()
        val songsWithFav = validSongs.map { song ->
            if (song.id in favoriteIds) song.copy(isFavorite = true) else song
        }

        songDao.replaceAll(songsWithFav)
        songsWithFav.size
    }
}
