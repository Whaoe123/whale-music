package com.whale.music.ui.ncm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whale.music.data.model.Song
import com.whale.music.data.sync.NcmLikedSync
import com.whale.music.ncm.NcmApi
import com.whale.music.ncm.NcmLoginState
import com.whale.music.ncm.NcmSession
import com.whale.music.ncm.NcmSongDto
import com.whale.music.ncm.QrLoginState
import com.whale.music.ncm.QrStatus
import com.whale.music.player.MusicPlayerManager
import com.whale.music.util.CoverResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 网易云登录 / 红心列表 / 搜索 / 红心回写 / 原生播放。
 */
@HiltViewModel
class NcmViewModel @Inject constructor(
    private val session: NcmSession,
    private val playerManager: MusicPlayerManager,
    private val likedSync: NcmLikedSync
) : ViewModel() {

    // ───────── 登录 ─────────
    private val _loginState = MutableStateFlow(
        if (session.hasCookies()) NcmLoginState.LOGGED_IN else NcmLoginState.LOGGED_OUT
    )
    val loginState: StateFlow<NcmLoginState> = _loginState.asStateFlow()

    /** 账号资料（昵称/头像），登录后由 session 提供 */
    val profile: StateFlow<com.whale.music.ncm.NcmProfile?> = session.profile

    private val _qrState = MutableStateFlow<QrLoginState?>(null)
    val qrState: StateFlow<QrLoginState?> = _qrState.asStateFlow()

    // ───────── 推荐列表 ─────────
    private val _recommended = MutableStateFlow<List<Song>>(emptyList())
    val recommended: StateFlow<List<Song>> = _recommended.asStateFlow()

    private val _recommendedLoading = MutableStateFlow(false)
    val recommendedLoading: StateFlow<Boolean> = _recommendedLoading.asStateFlow()

    // ───────── 红心列表 ─────────
    private val _liked = MutableStateFlow<List<Song>>(emptyList())
    val liked: StateFlow<List<Song>> = _liked.asStateFlow()

    private val _likedLoading = MutableStateFlow(false)
    val likedLoading: StateFlow<Boolean> = _likedLoading.asStateFlow()

    // ───────── 搜索 ─────────
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Song>?>(null)
    val searchResults: StateFlow<List<Song>?> = _searchResults.asStateFlow()

    // ───────── 播放链接解析中 ─────────
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    /** 错误提示（UI 消费后置空） */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 收藏同步结果提示（UI 消费后置空） */



    /** 轮询诊断行（显示在弹窗底部，排障用） */
    private val _pollInfo = MutableStateFlow("轮询启动中…")
    val pollInfo: StateFlow<String> = _pollInfo.asStateFlow()

    /** 歌曲 id → 封面地址（供封面注册与 MediaItem artwork） */
    private val coverMap = mutableMapOf<Long, String>()

    private var searchJob: Job? = null
    private var pollJob: Job? = null
    /** 登录会话代际：弹窗关闭即 +1，防止取码协程在弹窗关闭后仍重启轮询 */
    @Volatile
    private var loginGen = 0

    init {
        // 推荐音乐不登录也能看能听，App 一启动就加载
        loadRecommended()
        if (session.hasCookies()) {
            viewModelScope.launch {
                session.refreshProfile()
                if (session.profile.value != null) {
                    _loginState.value = NcmLoginState.LOGGED_IN
                    loadLiked()
                } else {
                    // Cookie 过期
                    session.clear()
                    _loginState.value = NcmLoginState.LOGGED_OUT
                }
            }
        }
    }

    // ───────── 扫码登录 ─────────

    fun startLogin(force: Boolean = false) {
        val existing = _qrState.value
        // 幂等：已有一张有效码在轮询时不重复取码（防止切换/重组时偷偷换 key）
        if (!force && existing != null &&
            (existing.status == QrStatus.WAITING || existing.status == QrStatus.SCANNED)
        ) {
            return
        }
        val gen = ++loginGen
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { NcmApi.warmup(session) } // 先拿完整 Cookie 组，降低风控
                val key = withContext(Dispatchers.IO) { NcmApi.unikey(session) }
                if (key.isEmpty()) {
                    _error.value = "获取二维码失败，请重试"
                    return@launch
                }
                if (gen != loginGen) return@launch // 弹窗已关闭，丢弃结果，不再后台轮询
                _qrState.value = QrLoginState(
                    key = key,
                    qrUrl = "https://music.163.com/login?codekey=$key",
                    status = QrStatus.WAITING
                )
                startPolling(key)
            } catch (e: Exception) {
                _error.value = "获取二维码失败：${e.message}"
            }
        }
    }

    private fun startPolling(key: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var round = 0
            var riskHits = 0
            while (true) {
                delay(3000) // 降频：1.5s → 3s，过密请求会触发风控
                val current = _qrState.value ?: break
                if (current.key != key) break
                try {
                    // 扫码状态机
                    val status = withContext(Dispatchers.IO) { NcmApi.qrCheck(session, key) }
                    val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date())
                    _pollInfo.value = "${status?.name ?: "未知"} · ${NcmApi.lastCheckInfo} · " +
                        "ck=[${session.cookieNames()}] · $now"
                    android.util.Log.d("NcmPoll", _pollInfo.value)
                    when (status) {
                        QrStatus.SUCCESS -> {
                            _qrState.value = current.copy(status = QrStatus.SUCCESS)
                            onLoginSuccess()
                            break
                        }
                        QrStatus.EXPIRED -> {
                            _qrState.value = current.copy(status = QrStatus.EXPIRED)
                            break
                        }
                        QrStatus.BLOCKED -> {
                            // 8821：账号级风控拒绝签发 MUSIC_U，扫码这条路走不通了
                            _error.value = "网易云风控拒绝扫码登录（8821），请改用「网页登录」或「粘贴 Cookie」"
                            _qrState.value = current.copy(status = QrStatus.BLOCKED)
                            break
                        }
                        QrStatus.RISK_CONTROLLED -> {
                            // -462 风控：继续轮询无意义，明确告知用户换网络
                            if (++riskHits >= 3) {
                                _error.value = "网易云风控拦截了登录请求，请关闭代理/VPN 或切换网络后重试"
                                _qrState.value = current.copy(status = QrStatus.EXPIRED)
                                break
                            }
                        }
                        QrStatus.WAITING, QrStatus.SCANNED -> {
                            riskHits = 0
                            _qrState.value = current.copy(status = status)
                        }
                        null -> Unit // 未知响应：保持原状态继续轮询，绝不降级
                    }
                    // 地面真值：每 4 轮（约 12s）或状态异常时查一次账号接口兜底，
                    // 不再每轮都打（该接口敏感，频繁调用会加剧风控）
                    if (++round % 4 == 0 || status == null || status == QrStatus.RISK_CONTROLLED) {
                        val prof = withContext(Dispatchers.IO) { session.refreshProfile() }
                        if (prof != null) {
                            _pollInfo.value = "账号已登录 ✓"
                            _qrState.value = (_qrState.value ?: current).copy(status = QrStatus.SUCCESS)
                            onLoginSuccess()
                            break
                        }
                    }
                } catch (e: Exception) {
                    _pollInfo.value = "轮询异常: ${e.javaClass.simpleName} ${e.message?.take(40)}"
                    android.util.Log.w("NcmPoll", "poll error", e)
                }
            }
        }
    }

    fun stopLogin() {
        loginGen++
        pollJob?.cancel()
        _qrState.value = null
    }

    /**
     * Cookie 登录（网页登录 / 手动粘贴）——扫码被 8821 风控后的可靠通道。
     * 校验方式与扫码一致：能拿到 profile 才算登录成功。
     */
    fun loginWithCookie(raw: String) {
        if (raw.isBlank() || !raw.contains("MUSIC_U=")) {
            _error.value = "Cookie 里没有 MUSIC_U，请复制完整 Cookie"
            return
        }
        viewModelScope.launch {
            session.setCookies(raw)
            val prof = withContext(Dispatchers.IO) { session.refreshProfile() }
            if (prof != null) {
                _loginState.value = NcmLoginState.LOGGED_IN
                loadLiked()
            } else {
                session.clear()
                _error.value = "Cookie 无效或已过期，请在浏览器重新登录后复制"
            }
        }
    }

    private suspend fun onLoginSuccess() {
        if (session.profile.value == null) session.refreshProfile()
        _loginState.value = NcmLoginState.LOGGED_IN
        // 保留 SUCCESS 状态给弹窗展示「登录成功」，由弹窗延时自动关闭
        loadLiked()
    }

    fun logout() {
        pollJob?.cancel()
        session.clear()
        _loginState.value = NcmLoginState.LOGGED_OUT
        _qrState.value = null
        _liked.value = emptyList()
        _searchResults.value = null
        _searchQuery.value = ""
        loadRecommended() // 退出后刷新为通用推荐
    }

    // ───────── 推荐列表 ─────────

    fun loadRecommended() {
        viewModelScope.launch {
            _recommendedLoading.value = true
            try {
                val list = withContext(Dispatchers.IO) { NcmApi.recommendSongs(session) }
                registerCovers(list)
                _recommended.value = list.map { it.toSong(favorite = false) }
            } catch (e: Exception) {
                _error.value = "加载推荐音乐失败：${e.message}"
            } finally {
                _recommendedLoading.value = false
            }
        }
    }

    // ───────── 红心列表 ─────────

    fun loadLiked() {
        viewModelScope.launch {
            _likedLoading.value = true
            try {
                val uid = session.profile.value?.userId
                    ?: session.refreshProfile()?.userId
                if (uid == null) {
                    // 不因一次资料拉取失败把人踢回未登录；提示重试
                    _error.value = "登录态读取失败，请下拉刷新或重新扫码"
                    return@launch
                }
                // 一次拿全量红心歌单（trackIds 全量 + 分批补详情），不再被 tracks 截断
                val dtos = withContext(Dispatchers.IO) { NcmApi.likedSongs(session, uid) }
                if (dtos.isEmpty()) {
                    _error.value = "未找到「我喜欢的音乐」歌单"
                } else {
                    registerCovers(dtos)
                    _liked.value = dtos.map { it.toSong(favorite = true) }
                    // 同时把整张歌单写进曲库（用现成列表，不再重复请求）
                    withContext(Dispatchers.IO) { likedSync.save(dtos) }
                }
            } catch (e: Exception) {
                _error.value = "加载红心列表失败：${e.message}"
            } finally {
                _likedLoading.value = false
            }
        }
    }


    // ───────── 搜索 ─────────

    fun onQueryChange(q: String) {
        _searchQuery.value = q
        searchJob?.cancel()
        if (q.isBlank()) {
            _searchResults.value = null
            return
        }
        searchJob = viewModelScope.launch {
            delay(400) // 防抖
            try {
                val results = withContext(Dispatchers.IO) {
                    NcmApi.search(session, q.trim())
                }
                registerCovers(results)
                _searchResults.value = results.map { it.toSong(favorite = true) }
            } catch (e: Exception) {
                _error.value = "搜索失败：${e.message}"
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchQuery.value = ""
        _searchResults.value = null
    }

    // ───────── 红心回写 ─────────

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            val target = !song.isFavorite
            val ok = try {
                withContext(Dispatchers.IO) { NcmApi.like(session, song.id, target) }
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                _error.value = "红心操作失败，请重试"
                return@launch
            }
            _liked.value = _liked.value.map {
                if (it.id == song.id) it.copy(isFavorite = target) else it
            }
            _searchResults.value = _searchResults.value?.map {
                if (it.id == song.id) it.copy(isFavorite = target) else it
            }
        }
    }

    // ───────── 播放 ─────────

    /**
     * 原生播放：批量解析播放链接 → Media3 播放。
     * 没链接的歌以占位（空 uri）进队列，数量/顺序与歌单一致，播到时自动跳过；
     * 单独点它时仍给出明确提示。
     */
    fun play(song: Song, list: List<Song>) {
        if (_resolving.value) return
        viewModelScope.launch {
            _resolving.value = true
            try {
                val urls = withContext(Dispatchers.IO) {
                    // 带上时长：EAPI 用它识别「没标 freeTrialInfo 却只有 30 秒」的截断试听流
                    NcmApi.playUrls(session, list.map { it.id }, list.associate { it.id to it.duration })
                }
                if (urls[song.id] == null) {
                    _error.value = "这首歌接口没有发链接，跳过播放"
                    return@launch
                }
                // 整单入队：有链接的带 uri，没链接的占位（空 uri），队列与歌单完全一致
                val withUrls = list.map { s ->
                    urls[s.id]?.let { s.copy(uri = it) } ?: s.copy(uri = "")
                }
                val covers = list.filter { it.id in urls.keys && coverMap.containsKey(it.id) }
                    .associate { it.id.toString() to (coverMap[it.id] ?: "") }
                    .filterValues { it.isNotEmpty() }
                val index = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                withContext(Dispatchers.Main) {
                    playerManager.setPlaylist(withUrls, index, covers)
                }
            } catch (e: Exception) {
                _error.value = "获取播放链接失败：${e.message}"
            } finally {
                _resolving.value = false
            }
        }
    }

    // ───────── 工具 ─────────

    private fun registerCovers(list: List<NcmSongDto>) {
        list.forEach { dto ->
            val cover = dto.coverUrl
            if (cover != null && dto.albumId > 0) {
                coverMap[dto.id] = cover
                CoverResolver.registerNcmCover(dto.albumId, cover)
            }
        }
    }

    fun consumeError() {
        _error.value = null
    }

    private fun NcmSongDto.toSong(favorite: Boolean) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        duration = durationMs,
        albumId = albumId,
        uri = "",
        isFavorite = favorite
    )
}
