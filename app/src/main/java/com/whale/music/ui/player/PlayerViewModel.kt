package com.whale.music.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whale.music.data.dao.RemoteSongDao
import com.whale.music.data.model.RemoteSong
import com.whale.music.data.model.Song
import com.whale.music.data.remote.RemoteMusicController
import com.whale.music.data.repository.MusicRepository
import com.whale.music.data.sync.LikedCatalog
import com.whale.music.data.sync.SongKey
import com.whale.music.ncm.NcmLevel
import com.whale.music.ncm.NcmSession
import com.whale.music.player.MusicPlayerManager
import com.whale.music.player.PlayMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val playMode: PlayMode = PlayMode.SEQUENCE,
    val isFavorite: Boolean = false
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerManager: MusicPlayerManager,
    private val repository: MusicRepository,
    private val remoteSongDao: RemoteSongDao,
    private val likedCatalog: LikedCatalog,
    private val likedWriter: com.whale.music.data.sync.NcmLikeWriter,
    private val ncmSession: NcmSession,
    private val remoteController: RemoteMusicController
) : ViewModel() {

    // ───────── 音质选择（播放页「更多」→ 音质） ─────────

    /** 当前选择；null = 自动（会员无损 / 免费 320k） */
    fun qualityLevel(): NcmLevel? = ncmSession.qualityLevel()

    /** 播放队列快照 + 跳转（播放页「更多」页签） */
    fun queue(): List<com.whale.music.data.model.Song> = playerManager.queueSnapshot()
    fun playAt(index: Int) = playerManager.playAt(index)

    /**
     * 歌词兜底（歌词页在网易没词时回调）：酷我逐行 LRC → lx 脚本。
     *
     * 歌词页只认得「歌名 + 歌手」，这里把本地 Song 补成匹配用的 QsTrack
     * （id 只是匹配缓存 key，本地歌直接用媒体库 id 即可）。
     */
    suspend fun fallbackLyric(song: Song): String? = remoteController.fallbackLyric(
        com.whale.music.data.model.QsTrack(
            id = song.id.toString(),
            title = song.title,
            artist = song.artist,
            album = song.album,
            cover = "",
            duration = song.duration
        )
    )

    /** 按毫秒跳进度（歌词点句跳转） */
    fun seekToMs(ms: Long) = playerManager.seekToMs(ms)

    /**
     * 写入选择并**立刻清掉已解析地址与 EAPI 缓存** ——
     * 不清缓存就会继续播旧档位的地址，选项等于没选。
     */
    fun setQualityLevel(level: NcmLevel?) {
        ncmSession.setQualityLevel(level)
        remoteController.clearResolvedUrls()
    }

    private data class PlaybackState(
        val song: Song?,
        val playing: Boolean,
        val progress: Float,
        val position: Long,
        val duration: Long
    )

    private val playbackState = combine(
        playerManager.currentSong,
        playerManager.isPlaying,
        playerManager.progress,
        playerManager.currentPosition,
        playerManager.duration
    ) { song, playing, progress, position, duration ->
        PlaybackState(song, playing, progress, position, duration)
    }

    /**
     * 爱心状态：本地歌查本地库，网络歌（酷我/汽水/网易解析播放）查在线收藏表。
     * 网络歌播放时 Song.albumId 固定为 -1，以此区分。
     *
     * 三类真值源都先物化成 Set 再判断 —— 每次判断 O(1)，
     * 不再对全量歌曲/收藏列表做 firstOrNull / any 线性扫。
     */
    private val remoteFavoriteKeys: Flow<Set<String>> = remoteSongDao.getAll()
        .map { list -> list.mapTo(HashSet(list.size)) { it.songId } }

    private val favoriteState = combine(
        playerManager.currentSong,
        repository.favoriteIds,
        remoteFavoriteKeys,
        likedCatalog.keys
    ) { currentSong, favIds, remoteKeys, likedKeys ->
        when {
            currentSong == null -> false
            // 网易云红心只读点亮：按歌名+歌手归一化匹配，不用用户再点一次
            SongKey.variants(currentSong.title, currentSong.artist).any { it in likedKeys } -> true
            isRemoteSong(currentSong) -> remoteKeyId(currentSong) in remoteKeys
            else -> currentSong.id in favIds
        }
    }

    /** 网络歌（酷我 / 网易 / lx / B站）在本地 Song 里的 albumId 标记 */
    private fun isRemoteSong(song: Song): Boolean =
        song.albumId == REMOTE_ALBUM_ID || song.albumId == REMOTE_WY_ALBUM_ID ||
            song.albumId == REMOTE_LX_ALBUM_ID || song.albumId == REMOTE_BILI_ALBUM_ID

    /** 播放中的网络歌 → 在线收藏表主键（按来源建 kw_/wy_/lx_/bili_ 前缀） */
    private fun remoteKeyId(song: Song): String = when (song.albumId) {
        REMOTE_WY_ALBUM_ID -> "wy_${song.id}"
        REMOTE_LX_ALBUM_ID -> "lx_${song.id}"
        REMOTE_BILI_ALBUM_ID -> "bili_${song.id}"
        else -> "kw_${song.id}"
    }

    val uiState: StateFlow<PlayerUiState> = combine(
        playbackState,
        playerManager.playMode,
        favoriteState
    ) { playback, playMode, isFavorite ->
        PlayerUiState(
            currentSong = playback.song,
            isPlaying = playback.playing,
            progress = playback.progress,
            currentPosition = playback.position,
            duration = playback.duration,
            playMode = playMode,
            isFavorite = isFavorite
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerUiState()
    )

    /** 当前封面（本地/在线统一） */
    val artwork: StateFlow<Any?> = playerManager.artwork

    // 拆分为独立流：避免进度每 500ms 更新导致整页（含封面模糊背景）重组
    val currentSong: StateFlow<Song?> = playerManager.currentSong
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    /** 迷你播放条用；订阅方很少，单独暴露不会引起整页重组 */
    val progress: StateFlow<Float> = playerManager.progress
    val playMode: StateFlow<PlayMode> = playerManager.playMode
    val isFavorite: StateFlow<Boolean> = favoriteState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun connect() = playerManager.connect()
    fun togglePlay() = playerManager.togglePlay()
    fun next() = playerManager.next()
    fun previous() = playerManager.previous()
    fun seekTo(fraction: Float) = playerManager.seekTo(fraction)
    fun togglePlayMode() = playerManager.togglePlayMode()

    /**
     * 爱心：优先**写回网易云红心**（登录后点了就生效，不用再去网易云点）；
     * 网易云写不了（未登录 / 搜不到这首歌）才退回本地库 / 在线收藏表。
     */
    fun toggleFavorite() {
        val song = uiState.value.currentSong ?: return
        viewModelScope.launch {
            val wrote = likedWriter.toggle(
                title = song.title,
                artist = song.artist,
                album = song.album,
                coverUrl = playerManager.coverUrlFor(song.id.toString()).orEmpty(),
                durationMs = song.duration,
                wyId = if (song.albumId == REMOTE_WY_ALBUM_ID) song.id else null
            )
            if (wrote != null) return@launch
            if (isRemoteSong(song)) {
                val source = when (song.albumId) {
                    REMOTE_WY_ALBUM_ID -> "wy"
                    REMOTE_LX_ALBUM_ID -> "lx"
                    REMOTE_BILI_ALBUM_ID -> "bili"
                    else -> "kw"
                }
                val songId = "${source}_${song.id}"
                if (remoteSongDao.count(songId) > 0) {
                    remoteSongDao.delete(songId)
                } else {
                    remoteSongDao.insert(
                        RemoteSong(
                            songId = songId,
                            source = source,
                            rid = song.id,
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            coverUrl = playerManager.coverUrlFor(song.id.toString()).orEmpty(),
                            duration = song.duration
                        )
                    )
                }
            } else {
                repository.toggleFavorite(song.id)
            }
        }
    }

    companion object {
        /** RemoteMusicController.toLocalSong 给酷我网络歌的 albumId 标记 */
        const val REMOTE_ALBUM_ID = -1L

        /** RemoteMusicController.toLocalSong 给网易网络歌的 albumId 标记（rid 是网易 id） */
        const val REMOTE_WY_ALBUM_ID = -2L

        /** RemoteMusicController.toLocalSong 给 lx 自定义音源网络歌的 albumId 标记 */
        const val REMOTE_LX_ALBUM_ID = -3L

        /** RemoteMusicController.toLocalSong 给 B 站兜底网络歌的 albumId 标记 */
        const val REMOTE_BILI_ALBUM_ID = -4L
    }
}
