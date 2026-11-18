package com.whale.music.ui.playlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whale.music.R
import com.whale.music.data.dao.CustomPlaylistDao
import com.whale.music.data.dao.RemoteSongDao
import com.whale.music.data.dao.SongDao
import com.whale.music.data.model.CustomPlaylist
import com.whale.music.data.model.CustomPlaylistSong
import com.whale.music.data.model.QsTrack
import com.whale.music.data.model.RemoteSong
import com.whale.music.data.model.Song
import com.whale.music.data.remote.LinkImporter
import com.whale.music.data.remote.RemoteMusicController
import com.whale.music.data.sync.LikedCatalog
import com.whale.music.data.sync.NcmLikedSync
import com.whale.music.data.sync.SongKey
import com.whale.music.data.repository.MusicRepository
import com.whale.music.player.MusicPlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PlaylistUiState(
    val songs: List<Song> = emptyList(),
    val favoriteSongs: List<Song> = emptyList(),
    val remoteFavorites: List<RemoteSong> = emptyList(),
    val currentSong: Song? = null,
    val artwork: Any? = null,
    val isScanning: Boolean = false,
    val errorMessage: String? = null
)

private data class PlaylistOperationState(
    val isScanning: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: MusicRepository,
    private val songDao: SongDao,
    private val remoteSongDao: RemoteSongDao,
    private val playlistDao: CustomPlaylistDao,
    private val remoteController: RemoteMusicController,
    private val playerManager: MusicPlayerManager,
    private val ncmLikedSync: NcmLikedSync,
    private val likedCatalog: LikedCatalog
) : ViewModel() {

    private val operation = MutableStateFlow(PlaylistOperationState())
    private var initialScanAttempted = false

    /** 在线收藏流 */
    private val remoteFavorites = remoteSongDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val baseState = combine(
        repository.allSongs,
        repository.favoriteSongs,
        remoteFavorites,
        playerManager.currentSong,
        operation
    ) { songs, favoriteSongs, remoteFavorites, currentSong, operation ->
        PlaylistUiState(
            songs = songs,
            favoriteSongs = favoriteSongs,
            remoteFavorites = remoteFavorites,
            currentSong = currentSong,
            isScanning = operation.isScanning,
            errorMessage = operation.errorMessage
        )
    }

    val uiState: StateFlow<PlaylistUiState> = combine(baseState, playerManager.artwork) { state, art ->
        state.copy(artwork = art)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlaylistUiState()
    )

    fun ensureInitialScan(hasPermission: Boolean) {
        if (!hasPermission || initialScanAttempted) return
        initialScanAttempted = true
        // 每次进程首次进入歌单都与 MediaStore 同步，及时反映新增和已删除歌曲。
        viewModelScope.launch { scan() }
    }

    fun refresh(hasPermission: Boolean, onPermissionRequired: () -> Unit) {
        if (!hasPermission) {
            onPermissionRequired()
            return
        }
        viewModelScope.launch { scan() }
    }

    fun play(song: Song, songs: List<Song>) = playerManager.play(song, songs)

    fun toggleFavorite(songId: Long) {
        viewModelScope.launch { repository.toggleFavorite(songId) }
    }

    /** 删除本地歌曲（含文件）。正在播放的先切走再删，避免播放器报错 */
    fun deleteSong(song: Song, onResult: (String) -> Unit) {
        viewModelScope.launch {
            if (playerManager.currentSong.value?.id == song.id) {
                playerManager.next()
            }
            val err = repository.deleteSong(song)
            playerManager.removeFromQueue(song.id)
            if (err == null) {
                scan()
                onResult("已删除「${song.title}」")
            } else {
                onResult(err)
            }
        }
    }

    // ───────── 在线收藏（发现页收藏的歌） ─────────

    fun playRemote(song: RemoteSong, list: List<RemoteSong>) {
        viewModelScope.launch {
            val err = remoteController.play(song, list)
            if (err != null) {
                operation.value = PlaylistOperationState(errorMessage = err)
            }
        }
    }

    fun toggleRemoteFavorite(song: RemoteSong) {
        viewModelScope.launch {
            if (remoteSongDao.count(song.songId) > 0) remoteSongDao.delete(song.songId)
            else remoteSongDao.insert(song)
        }
    }

    fun downloadRemote(song: RemoteSong, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val err = remoteController.download(song)
            if (err == null) {
                // 下载完成立即重扫媒体库，让歌单页马上看到
                scan()
            }
            onResult(err)
        }
    }

    // ───────── 自建 / 导入歌单 ─────────

    /** 展示用的歌单列表：网易云同步歌单已合并进「我喜欢的音乐」，不再单列一张卡 */
    val customPlaylists: StateFlow<List<CustomPlaylist>> = playlistDao.playlists()
        .map { list -> list.filter { it.kind != CustomPlaylist.KIND_NCM_SYNC } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 「我喜欢的音乐」合并视图：三个数据源去重后统一展示，以网易云同步为主。
     * 顺序：网易云红心 → 在线收藏 → 本地红心；同名同歌手的重复项只留一条。
     */
    val favoriteRows: StateFlow<List<CustomPlaylistSong>> = combine(
        likedCatalog.rows,
        remoteFavorites,
        repository.favoriteSongs
    ) { ncm, remotes, locals ->
        val seen = HashSet<String>()
        val out = ArrayList<CustomPlaylistSong>()

        fun add(row: CustomPlaylistSong): Boolean {
            val variants = SongKey.variants(row.title, row.artist)
            if (variants.isEmpty() || variants.any { it in seen }) return false
            seen.addAll(variants)
            out += row.copy(position = out.size)
            return true
        }

        // 1. 网易云红心（只读真值源，优先）
        ncm.forEach { add(it) }
        // 2. 在线收藏（第二/三页点的爱心）
        remotes.forEach { r ->
            add(
                CustomPlaylistSong(
                    playlistId = 0L,
                    songKey = CustomPlaylistSong.remoteKey(r.songId),
                    title = r.title,
                    artist = r.artist,
                    album = r.album,
                    coverUrl = r.coverUrl,
                    duration = r.duration,
                    remoteSongId = r.songId,
                    remoteRid = r.rid
                )
            )
        }
        // 3. 本地红心
        locals.forEach { s ->
            add(
                CustomPlaylistSong(
                    playlistId = 0L,
                    songKey = CustomPlaylistSong.localKey(s.id),
                    title = s.title,
                    artist = s.artist,
                    album = s.album,
                    duration = s.duration,
                    localSongId = s.id
                )
            )
        }
        out
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 播放「我喜欢的音乐」里的第 index 首（整张作为队列） */
    fun playFavorites(index: Int) {
        playPlaylist(favoriteRows.value, index)
    }

    /** 从「我喜欢的音乐」里移除：按来源分别处理；网易云红心只读，不改动 */
    fun removeFavorite(row: CustomPlaylistSong) {
        viewModelScope.launch {
            when {
                row.songKey.startsWith("ext_ncm_") -> {
                    toast("这首来自网易云收藏，请到网易云里取消")
                }
                row.songKey.startsWith("remote_") -> {
                    remoteSongDao.delete(row.songKey.removePrefix("remote_"))
                    toast("已取消收藏")
                }
                row.songKey.startsWith("local_") -> {
                    val id = row.songKey.removePrefix("local_").toLongOrNull()
                    if (id != null) repository.toggleFavorite(id)
                    toast("已取消收藏")
                }
                else -> toast("这首无法移除")
            }
        }
    }

    private val _openedId = MutableStateFlow<Long?>(null)
    val openedId: StateFlow<Long?> = _openedId.asStateFlow()

    /** 当前打开歌单的曲目；关闭时为 null */
    val openedSongs: StateFlow<List<CustomPlaylistSong>?> = _openedId.flatMapLatest { id ->
        if (id == null) flowOf(null) else playlistDao.songs(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun consumeToast() {
        _toast.value = null
    }

    private fun toast(msg: String) {
        _toast.value = msg
    }

    fun openPlaylist(playlist: CustomPlaylist) {
        _openedId.value = playlist.id
    }

    fun openPlaylistById(id: Long) {
        _openedId.value = id
    }

    fun closePlaylist() {
        _openedId.value = null
    }

    /** 新建歌单；name 为空时给个默认名 */
    fun createPlaylist(name: String, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val clean = name.trim().ifBlank { "我的歌单" }
            val id = playlistDao.insertPlaylist(CustomPlaylist(name = clean))
            onCreated(id)
            toast("已新建「$clean」")
        }
    }

    fun renamePlaylist(playlist: CustomPlaylist, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            playlistDao.rename(playlist.id, clean)
            toast("已重命名为「$clean」")
        }
    }

    fun deletePlaylist(playlist: CustomPlaylist) {
        viewModelScope.launch {
            playlistDao.deletePlaylist(playlist.id)
            playlistDao.clearSongs(playlist.id)
            if (_openedId.value == playlist.id) _openedId.value = null
            toast("已删除「${playlist.name}」")
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, song: CustomPlaylistSong) {
        viewModelScope.launch {
            playlistDao.removeSong(playlistId, song.songKey)
            toast("已从歌单移除")
        }
    }

    // ── 往歌单里加歌 ──

    fun addLocalSong(playlistId: Long, song: Song) {
        viewModelScope.launch {
            val key = CustomPlaylistSong.localKey(song.id)
            addOrToast(
                playlistId,
                CustomPlaylistSong(
                    playlistId = playlistId,
                    songKey = key,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    coverUrl = "",
                    duration = song.duration,
                    localSongId = song.id
                )
            )
        }
    }

    fun addRemoteSong(playlistId: Long, song: RemoteSong) {
        viewModelScope.launch {
            addOrToast(
                playlistId,
                CustomPlaylistSong(
                    playlistId = playlistId,
                    songKey = CustomPlaylistSong.remoteKey(song.songId),
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    coverUrl = song.coverUrl,
                    duration = song.duration,
                    remoteSongId = song.songId,
                    remoteRid = song.rid
                )
            )
        }
    }

    /** 「我喜欢的」一键塞进歌单（在线收藏 + 本地收藏） */
    fun addAllFavorites(playlistId: Long) {
        viewModelScope.launch {
            val localFavs = songDao.getFavoriteIds()
            val remotes = remoteSongDao.getAllOnce()
            var added = 0
            localFavs.forEach { id ->
                songDao.getSongById(id)?.let { s ->
                    val r = playlistDao.insertSong(
                        CustomPlaylistSong(
                            playlistId = playlistId,
                            songKey = CustomPlaylistSong.localKey(s.id),
                            title = s.title,
                            artist = s.artist,
                            album = s.album,
                            duration = s.duration,
                            localSongId = s.id
                        )
                    )
                    if (r > 0) added++
                }
            }
            remotes.forEach { rs ->
                val r = playlistDao.insertSong(
                    CustomPlaylistSong(
                        playlistId = playlistId,
                        songKey = CustomPlaylistSong.remoteKey(rs.songId),
                        title = rs.title,
                        artist = rs.artist,
                        album = rs.album,
                        coverUrl = rs.coverUrl,
                        duration = rs.duration,
                        remoteSongId = rs.songId,
                        remoteRid = rs.rid
                    )
                )
                if (r > 0) added++
            }
            toast(if (added > 0) "已把 $added 首喜欢的歌放进来" else "喜欢的歌都已经在里面了")
        }
    }

    private suspend fun addOrToast(playlistId: Long, song: CustomPlaylistSong) {
        val pos = playlistDao.maxPosition(playlistId) ?: -1
        val rowId = playlistDao.insertSong(song.copy(position = pos + 1))
        if (rowId <= 0) {
            toast("这首歌已经在歌单里了")
        } else {
            playlistDao.playlist(playlistId)?.let { p ->
                if (p.coverUrl.isBlank() && song.coverUrl.isNotBlank()) {
                    playlistDao.setCover(playlistId, song.coverUrl)
                }
            }
            toast("已加入歌单")
        }
    }

    // ── 播放歌单 ──

    /**
     * 播放整个自定义歌单。
     *
     * 全本地 → 直接按本地文件播；含网络歌 → 走「匹配酷我出声」那条链路，
     * 这样导入的网易云 / QQ 歌单也能整张播下去（不再只播前几首）。
     */
    fun playPlaylist(songs: List<CustomPlaylistSong>, startIndex: Int) {
        if (songs.isEmpty()) return
        viewModelScope.launch {
            val hasLocal = songs.any { it.localSongId > 0 }
            val hasRemote = songs.any { it.localSongId <= 0 }
            if (hasLocal && !hasRemote) {
                val local = songs.mapNotNull { songDao.getSongById(it.localSongId) }
                if (local.isEmpty()) {
                    toast("这些歌已经不在本地了")
                    return@launch
                }
                val start = local.getOrNull(startIndex) ?: local.first()
                playerManager.play(start, local)
                return@launch
            }

            val tracks = songs.map { it.toQsTrack() }
            val err = remoteController.playQishui(tracks, startIndex, 0L)
            if (err != null) toast(err)
        }
    }

    fun playOne(song: CustomPlaylistSong, list: List<CustomPlaylistSong>) {
        playPlaylist(list, list.indexOf(song).coerceAtLeast(0))
    }

    // ───────── 歌单导入（其他 App 分享链接）─────────

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    /**
     * 粘贴其他 App 的歌单链接 → 全量解析 → 直接写进曲库（不再依赖播放器边解析边播）。
     * 支持网易云歌单/单曲、QQ 音乐歌单。
     */
    fun importFromLink(raw: String, onDone: (Long?) -> Unit = {}) {
        if (_importing.value) return
        if (raw.isBlank()) {
            _importMessage.value = "先把歌单链接粘进来"
            return
        }
        viewModelScope.launch {
            _importing.value = true
            try {
                val result = withContext(Dispatchers.IO) { LinkImporter.import(raw) }
                if (result.tracks.isEmpty()) {
                    _importMessage.value = "这个歌单里没解析到歌曲"
                    return@launch
                }
                // 同名导入单直接覆盖，避免反复粘贴堆出十几个同名歌单
                val existing = customPlaylists.value.firstOrNull {
                    it.kind == CustomPlaylist.KIND_IMPORTED && it.name == result.name
                }
                val pid = existing?.id ?: playlistDao.insertPlaylist(
                    CustomPlaylist(
                        name = result.name,
                        coverUrl = result.tracks.firstOrNull()?.cover.orEmpty(),
                        kind = CustomPlaylist.KIND_IMPORTED,
                        source = result.source
                    )
                )
                val rows = result.tracks.mapIndexed { index, t ->
                    CustomPlaylistSong(
                        playlistId = pid,
                        songKey = CustomPlaylistSong.extKey(result.source, t.id),
                        title = t.title,
                        artist = t.artist,
                        album = t.album,
                        coverUrl = t.cover,
                        duration = t.duration,
                        position = index
                    )
                }
                withContext(Dispatchers.IO) {
                    playlistDao.replaceSongs(pid, rows)
                    playlistDao.setCover(pid, result.tracks.firstOrNull()?.cover.orEmpty())
                }
                _importMessage.value = "已导入「${result.name}」${rows.size} 首到曲库"
                onDone(pid)
            } catch (e: Exception) {
                _importMessage.value = e.message ?: "导入失败，检查一下链接"
            } finally {
                _importing.value = false
            }
        }
    }

    fun consumeImportMessage() {
        _importMessage.value = null
    }

    /** 手动把网易云「我喜欢的音乐」全量同步进曲库 */
    fun syncNcmLiked() {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            try {
                val r = withContext(Dispatchers.IO) { ncmLikedSync.sync(force = true) }
                _importMessage.value = when {
                    r.ok && r.missing > 0 ->
                        "已同步「${CustomPlaylist.NCM_SYNC_NAME}」${r.count} 首到曲库（另有 ${r.missing} 首接口取不到详情，多为无版权或风控拦截）"
                    r.ok -> "已同步「${CustomPlaylist.NCM_SYNC_NAME}」${r.count} 首到曲库"
                    r.error == "not_logged_in" -> "先在 Whale-网易云 登录，才能同步收藏"
                    r.error == "empty" -> "网易云「我喜欢的音乐」是空的"
                    r.error == "profile_failed" -> "登录态已过期，请重新登录网易云"
                    else -> "同步失败，请检查网络后重试"
                }
            } catch (e: Exception) {
                _importMessage.value = e.message ?: "同步失败"
            } finally {
                _importing.value = false
            }
        }
    }

    private suspend fun scan() {
        if (operation.value.isScanning) return
        try {
            operation.value = PlaylistOperationState(isScanning = true)
            repository.scanLocalMusic()
            operation.value = PlaylistOperationState()
        } catch (error: Exception) {
            operation.value = PlaylistOperationState(
                errorMessage = error.message ?: context.getString(R.string.scan_failed)
            )
        }
    }
}

/** 歌单里的歌 → 汽水播放链路需要的 QsTrack 形态（id 用来做匹配缓存 key） */
private fun CustomPlaylistSong.toQsTrack(): QsTrack = QsTrack(
    id = songKey,
    title = title,
    artist = artist,
    album = album,
    cover = coverUrl,
    duration = duration,
    kwRid = remoteRid.takeIf { it > 0 } ?: -1L
)
