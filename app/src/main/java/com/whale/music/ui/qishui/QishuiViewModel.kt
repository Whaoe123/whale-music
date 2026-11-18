package com.whale.music.ui.qishui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whale.music.data.dao.RemoteSongDao
import com.whale.music.data.local.PlaybackMemory
import com.whale.music.data.model.QsPlaylist
import com.whale.music.data.model.QsTrack
import com.whale.music.data.model.RemoteSong
import com.whale.music.data.model.Song
import com.whale.music.data.remote.KwSource
import com.whale.music.data.remote.QishuiSource
import com.whale.music.data.remote.RemoteMusicController
import com.whale.music.data.sync.LikedCatalog
import com.whale.music.data.sync.SongKey
import com.whale.music.player.MusicPlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class QishuiUiState(
    val loading: Boolean = true,
    val playlists: List<QsPlaylist> = emptyList(),
    val selectedId: String? = null,
    val tracks: List<QsTrack> = emptyList(),
    val tracksLoading: Boolean = false,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val loggedIn: Boolean = false,
    val nickname: String = "",
    val autoplay: Boolean = true,
    val resolvingIndex: Int = -1,
    val showLogin: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    // ── 收藏（爱心）──
    /** 在线收藏的 songId 集合（kw_xxx） */
    val favorites: Set<String> = emptySet(),
    /** 网易云红心归一化 key（只读点亮） */
    val likedKeys: Set<String> = emptySet(),
    /** 汽水曲目 id → 酷我 songId（判断某行是否已收藏） */
    val kwMap: Map<String, String> = emptyMap(),
    // ── 搜索 ──
    val query: String = "",
    val searching: Boolean = false,
    /** null = 非搜索态；非空 = 显示搜索结果 */
    val searchResults: List<RemoteSong>? = null
) {
    val selected: QsPlaylist?
        get() = playlists.firstOrNull { it.id == selectedId } ?: playlists.firstOrNull()
}

@HiltViewModel
class QishuiViewModel @Inject constructor(
    private val controller: RemoteMusicController,
    private val session: QishuiSession,
    private val playerManager: MusicPlayerManager,
    private val memory: PlaybackMemory,
    private val remoteSongDao: RemoteSongDao,
    private val likedCatalog: LikedCatalog
) : ViewModel() {

    private val _uiState = MutableStateFlow(QishuiUiState())
    val uiState: StateFlow<QishuiUiState> = _uiState.asStateFlow()

    // 播放器状态（玻璃迷你条用），拆成独立流避免进度刷新带动整页重组
    val artwork: StateFlow<Any?> = playerManager.artwork
    val currentSong: StateFlow<Song?> = playerManager.currentSong
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val progress: StateFlow<Float> = playerManager.progress

    fun togglePlay() = playerManager.togglePlay()
    fun next() = playerManager.next()

    private var loadedOnce = false

    private var searchJob: Job? = null
    private var searchSeq = 0

    init {
        // 登录态仅在匿名请求失败时作为降级使用（见 QishuiSource）
        QishuiSource.cookieProvider = { session.cookieHeader() }
        _uiState.update {
            it.copy(
                loggedIn = session.isLoggedIn(),
                nickname = session.nickname,
                autoplay = memory.autoplayEnabled
            )
        }
        // 在线收藏变化实时反映到爱心
        viewModelScope.launch {
            remoteSongDao.getAll().collect { list ->
                _uiState.update { it.copy(favorites = list.map { r -> r.songId }.toSet()) }
            }
        }
        // 网易云红心（只读真值源）实时反映到爱心
        viewModelScope.launch {
            likedCatalog.keys.collect { keys ->
                _uiState.update { it.copy(likedKeys = keys) }
            }
        }
        loadHome()
    }

    // ───────── 列表 ─────────

    fun loadHome() {
        if (loadedOnce && _uiState.value.playlists.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val list = withContext(Dispatchers.IO) {
                runCatching { QishuiSource.recommend(12) }.getOrDefault(emptyList())
            }
            if (list.isEmpty()) {
                _uiState.update {
                    it.copy(loading = false, error = "推荐加载失败，检查网络后下拉重试")
                }
                return@launch
            }
            loadedOnce = true
            _uiState.update {
                it.copy(loading = false, playlists = list, selectedId = list.first().id)
            }
            loadTracks(list.first(), append = false)
        }
    }

    fun refresh() = refreshAnonymous()

    fun refreshAnonymous() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val list = withContext(Dispatchers.IO) {
                runCatching { QishuiSource.recommend(12) }.getOrDefault(emptyList())
            }
            if (list.isEmpty()) {
                _uiState.update { it.copy(loading = false, error = "刷新失败，稍后再试") }
                return@launch
            }
            val keep = _uiState.value.selectedId
            val target = keep?.let { id -> list.firstOrNull { it.id == id } } ?: list.first()
            _uiState.update {
                it.copy(
                    loading = false,
                    playlists = list,
                    selectedId = target.id,
                    tracks = emptyList()
                )
            }
            loadTracks(target, append = false)
        }
    }

    fun selectPlaylist(playlist: QsPlaylist) {
        if (_uiState.value.selectedId == playlist.id && _uiState.value.tracks.isNotEmpty()) return
        _uiState.update {
            it.copy(selectedId = playlist.id, tracks = emptyList(), nextCursor = null, hasMore = false)
        }
        loadTracks(playlist, append = false)
    }

    private fun loadTracks(playlist: QsPlaylist, append: Boolean) {
        if (_uiState.value.tracksLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(tracksLoading = true) }
            val cursor = if (append) _uiState.value.nextCursor else null
            val page = withContext(Dispatchers.IO) {
                runCatching { QishuiSource.playlistTracks(playlist.id, cursor) }.getOrNull()
            }
            _uiState.update { st ->
                if (page == null) {
                    st.copy(tracksLoading = false, error = if (st.tracks.isEmpty()) "歌单加载失败" else null)
                } else {
                    // 歌单翻页时可能重复下发同一首，去重防止 LazyColumn 重复 key 崩溃
                    val merged = if (append) st.tracks + page.tracks else page.tracks
                    st.copy(
                        tracksLoading = false,
                        tracks = merged.distinctBy { it.id },
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore
                    )
                }
            }
            // 后台先把前几首跟酷我对上，点歌时只剩取直链一步
            if (page != null && page.tracks.isNotEmpty()) {
                launch(Dispatchers.IO) { controller.prefetchQishui(page.tracks) }
                // 预匹配完成后回填「曲目→酷我 songId」映射，爱心就能点亮
                launch {
                    delay(2600)
                    val map = page.tracks.take(12).mapNotNull { t ->
                        controller.kwSongIdOf(t.id)?.let { t.id to it }
                    }.toMap()
                    if (map.isNotEmpty()) {
                        _uiState.update { it.copy(kwMap = it.kwMap + map) }
                    }
                }
            }
        }
    }

    fun loadMore() {
        val st = _uiState.value
        if (!st.hasMore || st.tracksLoading) return
        val pl = st.selected ?: return
        loadTracks(pl, append = true)
    }

    // ───────── 播放 ─────────

    fun play(index: Int) {
        val st = _uiState.value
        if (st.resolvingIndex >= 0 || st.tracks.isEmpty()) return
        val track = st.tracks.getOrNull(index) ?: return
        val playlistId = st.selected?.id.orEmpty()
        viewModelScope.launch {
            _uiState.update { it.copy(resolvingIndex = index) }
            val err = controller.playQishui(st.tracks, index, 0L)
            if (err == null) {
                // 记住现场，下次冷启动从这里接着放
                memory.rememberQishui(playlistId, index)
            }
            _uiState.update {
                it.copy(resolvingIndex = -1, message = err ?: "正在播放「${track.title}」")
            }
        }
    }

    fun playAll() {
        if (_uiState.value.tracks.isEmpty()) return
        play(0)
    }

    // ───────── 自动播放开关 ─────────

    fun setAutoplay(enabled: Boolean) {
        memory.autoplayEnabled = enabled
        _uiState.update {
            it.copy(
                autoplay = enabled,
                message = if (enabled) "下次打开 App 会自动接着播" else "已关闭启动自动播放"
            )
        }
    }

    // ───────── 登录 ─────────

    fun openLogin() = _uiState.update { it.copy(showLogin = true) }

    fun closeLogin() {
        _uiState.update { it.copy(showLogin = false) }
        syncLoginState()
    }

    fun onLoggedIn(cookie: String, nickname: String) {
        if (cookie.isNotBlank()) session.cookie = cookie
        session.wasLoggedIn = true
        if (nickname.isNotBlank()) session.nickname = nickname
        loadedOnce = false
        _uiState.update {
            it.copy(
                showLogin = false,
                loggedIn = true,
                nickname = nickname.ifBlank { it.nickname },
                message = "已登录汽水音乐"
            )
        }
        // 带着 Cookie 重拉一次，推荐会变成个性化
        refreshAnonymous()
    }

    fun syncLoginState() {
        val logged = session.isLoggedIn()
        _uiState.update { it.copy(loggedIn = logged, nickname = session.nickname) }
    }

    fun logout() {
        session.clear()
        _uiState.update { it.copy(loggedIn = false, nickname = "", message = "已退出汽水账号") }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null, error = null) }

    // ───────── 爱心收藏 ─────────

    /** 网易云红心里已有的歌（只读点亮，按歌名+歌手归一化匹配） */
    private fun isLikedByNcm(title: String, artist: String): Boolean =
        SongKey.variants(title, artist).any { it in _uiState.value.likedKeys }

    /** 某行曲目是否已收藏（曲匹配结果回填后即可点亮） */
    fun isTrackFavorite(track: QsTrack): Boolean {
        if (isLikedByNcm(track.title, track.artist)) return true
        val st = _uiState.value
        val kwId = st.kwMap[track.id] ?: controller.kwSongIdOf(track.id) ?: return false
        return kwId in st.favorites
    }

    /** 搜索结果某首歌是否已点亮（在线收藏 or 网易云红心） */
    fun isRemoteFavorite(song: RemoteSong): Boolean =
        song.songId in _uiState.value.favorites || isLikedByNcm(song.title, song.artist)

    /** 点爱心：把汽水曲目对到酷我曲库后写入在线收藏（音乐库列表可见） */
    fun toggleTrackFavorite(track: QsTrack) {
        // 网易云红心只读，恒亮不改动
        if (isLikedByNcm(track.title, track.artist)) {
            _uiState.update { it.copy(message = "这首已在网易云收藏里，去网易云取消即可") }
            return
        }
        viewModelScope.launch {
            val kw = controller.resolveKw(track)
            if (kw == null) {
                _uiState.update {
                    it.copy(message = "「${track.title}」没匹配到曲库，暂不能收藏")
                }
                return@launch
            }
            _uiState.update { it.copy(kwMap = it.kwMap + (track.id to kw.songId)) }
            if (remoteSongDao.count(kw.songId) > 0) {
                remoteSongDao.delete(kw.songId)
                _uiState.update { it.copy(message = "已从喜欢中移除") }
            } else {
                remoteSongDao.insert(kw)
                _uiState.update { it.copy(message = "已加入喜欢 · 在音乐库里能看到") }
            }
        }
    }

    /** 搜索结果 / 播放页同款的在线收藏切换 */
    fun toggleRemoteFavorite(song: RemoteSong) {
        if (isLikedByNcm(song.title, song.artist)) {
            _uiState.update { it.copy(message = "这首已在网易云收藏里，去网易云取消即可") }
            return
        }
        viewModelScope.launch {
            if (remoteSongDao.count(song.songId) > 0) {
                remoteSongDao.delete(song.songId)
                _uiState.update { it.copy(message = "已从喜欢中移除") }
            } else {
                remoteSongDao.insert(song)
                _uiState.update { it.copy(message = "已加入喜欢 · 在音乐库里能看到") }
            }
        }
    }

    // ───────── 搜索（酷我源，同发现页） ─────────

    fun onQueryChange(q: String) {
        _uiState.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.isBlank()) {
            _uiState.update { it.copy(searchResults = null, searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(400)
            val seq = ++searchSeq
            _uiState.update { it.copy(searching = true) }
            val results = withContext(Dispatchers.IO) {
                try { KwSource.search(q) } catch (e: Exception) { emptyList<RemoteSong>() }
            }
            if (seq != searchSeq) return@launch
            _uiState.update { it.copy(searching = false, searchResults = results) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchSeq++
        _uiState.update { it.copy(query = "", searchResults = null, searching = false) }
    }

    fun playSearchResult(index: Int) {
        val results = _uiState.value.searchResults ?: return
        val song = results.getOrNull(index) ?: return
        viewModelScope.launch {
            val err = controller.play(song, results)
            _uiState.update { it.copy(message = err ?: "正在播放「${song.title}」") }
        }
    }

    /** 搜索结果下载到本地（只有搜索行带这个按钮） */
    fun downloadSearchResult(song: RemoteSong) {
        viewModelScope.launch {
            _uiState.update { it.copy(message = "正在下载「${song.title}」") }
            val err = controller.download(song)
            _uiState.update { it.copy(message = err ?: "已下载「${song.title}」") }
        }
    }
}
