package com.whale.music.data.sync

import com.whale.music.data.model.QsTrack
import com.whale.music.data.model.RemoteSong
import com.whale.music.data.remote.SongMatcher
import com.whale.music.ncm.NcmApi
import com.whale.music.ncm.NcmSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 App 里的「喜欢」写回网易云红心 —— 以前这里只读（点了心想变但改不动，还要用户去网易云操作）。
 *
 * 定位网易云歌曲 id 的三条路，按可靠性从高到低：
 * 1. 本来就是网易源的歌（`wyId` 已知，零歧义）；
 * 2. 已经红心的歌：从同步歌单那一行 `ext_ncm_<id>` 反解 id（取消红心用）；
 * 3. 本地 / 酷我 / lx / B 站的歌：按「歌名 + 歌手」在网易搜一遍，
 *    用 [SongMatcher] 的严格规则挑一条（歌手要对得上、时长差不能超 30 秒），
 *    挑不出来就当写不了 —— 宁可只收藏到本地，也不要喜欢错一首同名的歌。
 */
@Singleton
class NcmLikeWriter @Inject constructor(
    private val session: NcmSession,
    private val likedSync: NcmLikedSync,
    private val likedCatalog: LikedCatalog
) {

    /**
     * 切换一首歌的红心。
     *
     * @return true = 已红心，false = 已取消红心，**null = 网易云写不了**
     *         （未登录 / 搜不到对应歌曲 / 网络失败）—— 调用方收到 null 就退回本地收藏。
     */
    suspend fun toggle(
        title: String,
        artist: String,
        album: String,
        coverUrl: String,
        durationMs: Long,
        wyId: Long? = null
    ): Boolean? = withContext(Dispatchers.IO) {
        if (!session.hasCookies()) return@withContext null
        val currentlyLiked = likedCatalog.isLiked(title, artist)
        val id = wyId
            ?: likedIdOf(title, artist)
            ?: searchIdOf(title, artist, album, durationMs)
            ?: return@withContext null
        val target = !currentlyLiked
        val ok = likedSync.setLiked(id, target, title, artist, album, coverUrl, durationMs)
        if (ok) target else null
    }

    /** 已红心的歌：同步歌单里那一行的主键就是网易 id */
    private fun likedIdOf(title: String, artist: String): Long? {
        val want = SongKey.variants(title, artist)
        if (want.isEmpty()) return null
        return likedCatalog.rows.value.firstOrNull { row ->
            row.songKey.startsWith("ext_ncm_") &&
                SongKey.variants(row.title, row.artist).any { it in want }
        }?.songKey?.removePrefix("ext_ncm_")?.toLongOrNull()
    }

    private suspend fun searchIdOf(title: String, artist: String, album: String, durationMs: Long): Long? {
        if (title.isBlank()) return null
        val keyword = listOf(title, artist).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val hits = runCatching { NcmApi.search(session, keyword, limit = 10) }.getOrNull().orEmpty()
        if (hits.isEmpty()) return null
        val track = QsTrack(
            id = "like_search",
            title = title,
            artist = artist,
            album = album,
            cover = "",
            duration = durationMs
        )
        val candidates = hits.map { d ->
            RemoteSong(
                songId = "wy_${d.id}",
                source = "wy",
                rid = d.id,
                title = d.title,
                artist = d.artist,
                album = d.album,
                coverUrl = d.coverUrl.orEmpty(),
                duration = d.durationMs
            )
        }
        return SongMatcher.pickBest(candidates, track)?.first?.rid
    }
}
