package com.whale.music.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.whale.music.data.dao.CustomPlaylistDao
import com.whale.music.data.dao.RemoteSongDao
import com.whale.music.data.dao.SongDao
import com.whale.music.data.model.CustomPlaylist
import com.whale.music.data.model.CustomPlaylistSong
import com.whale.music.data.model.RemoteSong
import com.whale.music.data.model.Song

@Database(
    entities = [
        Song::class,
        RemoteSong::class,
        CustomPlaylist::class,
        CustomPlaylistSong::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun remoteSongDao(): RemoteSongDao
    abstract fun customPlaylistDao(): CustomPlaylistDao

    companion object {
        /** 版本 1 → 2：添加 isFavorite 字段 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 版本 2 → 3：添加在线歌曲收藏表 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS remote_songs (" +
                        "songId TEXT NOT NULL PRIMARY KEY, " +
                        "source TEXT NOT NULL, " +
                        "rid INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "artist TEXT NOT NULL, " +
                        "album TEXT NOT NULL, " +
                        "coverUrl TEXT NOT NULL, " +
                        "duration INTEGER NOT NULL, " +
                        "dateAdded INTEGER NOT NULL)"
                )
            }
        }

        /** 版本 3 → 4：自建 / 导入歌单 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS custom_playlists (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "coverUrl TEXT NOT NULL, " +
                        "kind INTEGER NOT NULL, " +
                        "source TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS custom_playlist_songs (" +
                        "playlistId INTEGER NOT NULL, " +
                        "songKey TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "artist TEXT NOT NULL, " +
                        "album TEXT NOT NULL, " +
                        "coverUrl TEXT NOT NULL, " +
                        "duration INTEGER NOT NULL, " +
                        "localSongId INTEGER NOT NULL, " +
                        "remoteSongId TEXT NOT NULL, " +
                        "remoteRid INTEGER NOT NULL, " +
                        "position INTEGER NOT NULL, " +
                        "addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(playlistId, songKey))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_custom_playlist_songs_playlistId " +
                        "ON custom_playlist_songs (playlistId)"
                )
            }
        }
    }
}
