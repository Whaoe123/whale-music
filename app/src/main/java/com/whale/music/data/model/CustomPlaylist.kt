package com.whale.music.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_playlists")
data class CustomPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverUrl: String = "",
    val kind: Int = KIND_MANUAL,
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KIND_MANUAL = 0
        const val KIND_IMPORTED = 1
        const val KIND_NCM_SYNC = 2
        const val NCM_SYNC_NAME = "我喜欢的音乐 · 网易云"
    }
}
