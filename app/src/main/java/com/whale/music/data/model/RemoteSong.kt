package com.whale.music.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 在线歌曲（发现页）— 酷我 / 网易的搜索、推荐、歌单歌曲
 * songId 形如 "kw_228908" 或 "wy_186016"，用作主键
 */
@Entity(tableName = "remote_songs")
data class RemoteSong(
    @PrimaryKey val songId: String,     // 平台_原始ID
    val source: String,                 // "kw" = 酷我, "wy" = 网易
    val rid: Long,                      // 平台原始歌曲 ID
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String,
    val duration: Long,                 // 毫秒
    val dateAdded: Long = System.currentTimeMillis()
)
