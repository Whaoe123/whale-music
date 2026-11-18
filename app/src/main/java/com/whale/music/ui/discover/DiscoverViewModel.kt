package com.whale.music.ui.discover

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whale.music.data.dao.RemoteSongDao
import com.whale.music.data.model.RemoteSong
import com.whale.music.data.remote.KwSource
import com.whale.music.data.remote.RemoteMusicController
import com.whale.music.data.sync.LikedCatalog
import com.whale.music.data.sync.SongKey
import com.whale.music.ncm.NcmApi
import com.whale.music.ncm.NcmHttp
import com.whale.music.ncm.NcmSession
import com.whale.music.ncm.NcmSongDto
import com.whale.music.player.MusicPlayerManager
import com.whale.music.util.CoverUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import javax.inject.Inject

/** 网易歌单卡片 */
data class WyPlaylist(
    val id: Long,
    val name: String,
    val picUrl: String,
    val trackCount: Int
)

data class DiscoverUiState(
    val loading: Boolean = true,
    val recommend: List<RemoteSong> = emptyList(),
    val personalized: Boolean = false,
    val playlists: List<WyPlaylist> = emptyList(),
    val query: String = "",
    val searching: Boolean = false,
    val searchResults: List<RemoteSong>? = null,   // null = 非搜索态
    val openedPlaylist: WyPlaylist? = null,
    val playlistSongs: List<RemoteSong> = emptyList(),
    val playlistLoading: Boolean = false,
    val resolving: Boolean = false,
    val downloadingId: String? = null,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val controller: RemoteMusicController,
    private val remoteSongDao: RemoteSongDao,
    private val session: NcmSession,
    private val playerManager: MusicPlayerManager,
    private val likedCatalog: LikedCatalog
) : ViewModel() {

    /** 当前播放封面 — 发现页背景与其他页面保持一致 */
    val artwork: StateFlow<Any?> = playerManager.artwork

    /** 网易云「我喜欢的音乐」归一化 key —— 只读点亮，不写入本地收藏表 */
    val likedKeys: StateFlow<Set<String>> = likedCatalog.keys

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    /** 在线收藏（Room 实时流） */
    val remoteFavorites: StateFlow<List<RemoteSong>> = remoteSongDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var searchJob: Job? = null
    private var searchSeq = 0

    init {
        loadHome()
    }

    // ───────── 首页数据 ─────────

    /**
     * 首页推荐：
     * - A：已登录网易云 → 拉「每日推荐」（按账号口味，不再是通用榜单）；
     *      未登录 → 酷我通用推荐兜底。
     * - B：用网易云红心歌单里的歌手做加权混排，让口味相近的歌靠前，
     *      但不全堆同一种，交错穿插保留新鲜感。
     */
    fun loadHome(force: Boolean = false) {
        if (!force && _uiState.value.recommend.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val recommend = withContext(Dispatchers.IO) {
                val base = if (session.hasCookies()) {
                    val daily = runCatching { NcmApi.recommendSongs(session, 50) }
                        .getOrDefault(emptyList())
                        .map { it.toRemoteSong() }
                    // 日推被风控 / 空结果时兜底，保证打开就有歌
                    daily.ifEmpty {
                        runCatching { KwSource.recommend() }.getOrDefault(emptyList())
                    }
                } else {
                    runCatching { KwSource.recommend() }.getOrDefault(emptyList())
                }
                boostByTaste(base)
            }
            val playlists = withContext(Dispatchers.IO) {
                try { wyPersonalizedPlaylists() } catch (e: Exception) { emptyList() }
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    recommend = recommend,
                    playlists = playlists,
                    personalized = session.hasCookies() && recommend.isNotEmpty(),
                    error = if (recommend.isEmpty() && playlists.isEmpty()) "内容加载失败，下拉重试" else null
                )
            }
        }
    }

    /**
     * B 档：把「歌手出现在红心歌单里」的歌标成口味命中，
     * 命中组与其余组交错穿插（2:1），命中的优先靠前但不淹没整张列表。
     */
    private fun boostByTaste(list: List<RemoteSong>): List<RemoteSong> {
        val likedArtists = likedCatalog.artists.value
        if (list.isEmpty() || likedArtists.isEmpty()) return list
        val hit = ArrayList<RemoteSong>()
        val rest = ArrayList<RemoteSong>()
        list.forEach { s ->
            val parts = SongKey.artistParts(s.artist)
            if (parts.any { it in likedArtists }) hit += s else rest += s
        }
        if (hit.isEmpty()) return list
        val out = ArrayList<RemoteSong>(list.size)
        var i = 0
        var j = 0
        while (i < hit.size || j < rest.size) {
            if (i < hit.size) out += hit[i++]
            if (i < hit.size) out += hit[i++]
            if (j < rest.size) out += rest[j++]
        }
        return out
    }

    private fun NcmSongDto.toRemoteSong(): RemoteSong = RemoteSong(
        songId = "wy_$id",
        source = "wy",
        rid = id,
        title = title,
        artist = artist,
        album = album,
        coverUrl = CoverUrl.hd(coverUrl.orEmpty()),
        duration = durationMs
    )

    /** 只读点亮：网易云已红心的歌不重复点喜欢，也不反向回写 */
    fun isLikedByNcm(song: RemoteSong): Boolean =
        likedCatalog.isLiked(song.title, song.artist)

    fun toggleFavorite(song: RemoteSong) {
        // 红心歌单是只读真值源：这类歌爱心恒亮，点击不改动任何数据
        if (isLikedByNcm(song)) {
            _uiState.update { it.copy(message = "这首已在网易云收藏里，去网易云取消即可") }
            return
        }
        viewModelScope.launch {
            val exists = remoteSongDao.count(song.songId) > 0
            if (exists) {
                remoteSongDao.delete(song.songId)
                _uiState.update { it.copy(message = "已取消收藏「${song.title}」") }
            } else {
                remoteSongDao.insert(song)
                _uiState.update { it.copy(message = "已收藏「${song.title}」，可在收藏页查看") }
            }
        }
    }

    /** 网易匿名推荐歌单 */
    private suspend fun wyPersonalizedPlaylists(): List<WyPlaylist> = withContext(Dispatchers.IO) {
        val json = NcmHttp.get(
            "https://music.163.com/api/personalized/playlist?limit=30",
            session.cookieHeader()
        )
        val arr = json.optJSONArray("result") ?: return@withContext emptyList()
        val out = mutableListOf<WyPlaylist>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val id = p.optLong("id")
            if (id == 0L) continue
            out += WyPlaylist(
                id = id,
                name = p.optString("name"),
                picUrl = p.optString("picUrl"),
                trackCount = p.optInt("playCount", 0)
            )
        }
        out
    }

    // ───────── 搜索 ─────────

    fun onQueryChange(q: String) {
        _uiState.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.isBlank()) {
            _uiState.update { it.copy(searchResults = null, searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(400) // 防抖
            val seq = ++searchSeq
            _uiState.update { it.copy(searching = true) }
            val results = withContext(Dispatchers.IO) {
                try { KwSource.search(q) } catch (e: Exception) { emptyList<RemoteSong>() }
            }
            if (seq != searchSeq) return@launch // 已被更新请求取代
            _uiState.update { it.copy(searching = false, searchResults = results) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchSeq++
        _uiState.update { it.copy(query = "", searchResults = null, searching = false) }
    }

    // ───────── 歌单详情 ─────────

    fun openPlaylist(playlist: WyPlaylist) {
        viewModelScope.launch {
            _uiState.update { it.copy(openedPlaylist = playlist, playlistLoading = true, playlistSongs = emptyList()) }
            val songs = withContext(Dispatchers.IO) {
                try { wyPlaylistSongs(playlist.id) } catch (e: Exception) { emptyList<RemoteSong>() }
            }
            _uiState.update { it.copy(playlistLoading = false, playlistSongs = songs) }
        }
    }

    fun closePlaylist() {
        _uiState.update { it.copy(openedPlaylist = null, playlistSongs = emptyList()) }
    }

    /** 网易歌单详情（匿名），转成可播歌曲列表 */
    private suspend fun wyPlaylistSongs(pid: Long): List<RemoteSong> = withContext(Dispatchers.IO) {
        val json = NcmHttp.get(
            "https://music.163.com/api/v6/playlist/detail?id=$pid&n=200&s=8",
            session.cookieHeader()
        )
        val pl = json.optJSONObject("playlist") ?: return@withContext emptyList()
        val tracks = pl.optJSONArray("tracks") ?: return@withContext emptyList()
        val out = mutableListOf<RemoteSong>()
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val rid = t.optLong("id")
            if (rid == 0L) continue
            val artists = t.optJSONArray("artists") ?: t.optJSONArray("ar")
            val artistNames = StringBuilder()
            if (artists != null) {
                for (j in 0 until artists.length()) {
                    if (j > 0) artistNames.append('/')
                    artistNames.append(artists.optJSONObject(j)?.optString("name").orEmpty())
                }
            }
            val album = t.optJSONObject("album") ?: t.optJSONObject("al")
            out += RemoteSong(
                songId = "wy_$rid",
                source = "wy",
                rid = rid,
                title = t.optString("name"),
                artist = artistNames.toString(),
                album = album?.optString("name").orEmpty(),
                coverUrl = com.whale.music.util.CoverUrl.hd(album?.optString("picUrl") ?: ""),
                duration = t.optLong("duration", 0L)
            )
        }
        out
    }

    // ───────── 播放 / 收藏 / 下载 ─────────

    fun play(song: RemoteSong, list: List<RemoteSong>) {
        if (_uiState.value.resolving) return
        viewModelScope.launch {
            _uiState.update { it.copy(resolving = true) }
            val err = controller.play(song, list)
            _uiState.update {
                it.copy(resolving = false, message = err ?: "开始播放「${song.title}」")
            }
        }
    }

    fun download(song: RemoteSong) {
        if (_uiState.value.downloadingId != null) {
            _uiState.update { it.copy(message = "已有任务在下载，请稍候") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingId = song.songId) }
            val err = controller.download(song)
            _uiState.update {
                it.copy(
                    downloadingId = null,
                    message = err ?: "已下载「${song.title}」，刷新音乐库可见"
                )
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
