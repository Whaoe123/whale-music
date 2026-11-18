package com.whale.music.data.sync

import com.whale.music.data.dao.CustomPlaylistDao
import com.whale.music.data.model.CustomPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「已喜欢」全局真值源（只读）。
 *
 * 网易云「我喜欢的音乐」同步歌单由 NcmLikedSync 全量覆盖写入曲库，
 * 这里实时把它映射成归一化 key 集合与歌手集合，供各页面爱心判定、
 * 第三页推荐加权使用。不写回网易云，也不往本地收藏表里塞记录。
 */
@Singleton
class LikedCatalog @Inject constructor(
    private val dao: CustomPlaylistDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 同步歌单曲目（实时流） */
    val rows: StateFlow<List<com.whale.music.data.model.CustomPlaylistSong>> = dao.playlists()
        .map { list -> list.firstOrNull { it.kind == CustomPlaylist.KIND_NCM_SYNC }?.id }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else dao.songs(id)
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 归一化 key 集合（含歌手拆分变体） */
    val keys: StateFlow<Set<String>> = rows.map { list ->
        val out = HashSet<String>()
        list.forEach { out.addAll(SongKey.variants(it.title, it.artist)) }
        out
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** 红心歌手里出现过的歌手（归一化），用于推荐加权 */
    val artists: StateFlow<Set<String>> = rows.map { list ->
        val out = HashSet<String>()
        list.forEach { out.addAll(SongKey.artistParts(it.artist)) }
        out
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun isLiked(title: String, artist: String): Boolean {
        val k = keys.value
        if (k.isEmpty()) return false
        return SongKey.variants(title, artist).any { it in k }
    }

    fun isArtistLiked(artist: String): Boolean {
        val a = artists.value
        if (a.isEmpty()) return false
        return SongKey.artistParts(artist).any { it in a }
    }
}
