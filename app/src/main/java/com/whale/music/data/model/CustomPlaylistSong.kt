package com.whale.music.data.model

import androidx.room.Entity
import androidx.room.Index

/**
 * 歌单里的一首歌 —— 存完整快照，不依赖歌曲表是否还在。
 *
 * songKey 唯一：本地歌 "local_<songId>"，在线歌 "remote_<platform>_<rid>"，
 * 导入歌 "ext_<平台>_<原始id>"（这类没有 rid，播放时要现搜酷我）。
 */
@Entity(
    tableName = "custom_playlist_songs",
    primaryKeys = ["playlistId", "songKey"],
    indices = [Index("playlistId")]
)
data class CustomPlaylistSong(
    val playlistId: Long,
    val songKey: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String = "",
    val duration: Long = 0L,
    /** >0 表示本地 MediaStore 歌曲，可直接播放 */
    val localSongId: Long = 0L,
    /** 非空表示网络歌，格式 "kw_<rid>"；播放走在线链路 */
    val remoteSongId: String = "",
    /** 酷我 rid，0 = 还没匹配过 */
    val remoteRid: Long = 0L,
    val position: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun localKey(songId: Long) = "local_$songId"
        fun remoteKey(songId: String) = "remote_$songId"
        fun extKey(platform: String, id: String) = "ext_${platform}_$id"
    }
}
