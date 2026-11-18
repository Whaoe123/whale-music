package com.whale.music.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whale.music.data.dao.RemoteSongDao
import com.whale.music.data.model.RemoteSong
import com.whale.music.data.model.Song
import com.whale.music.data.repository.MusicRepository
import com.whale.music.data.sync.LikedCatalog
import com.whale.music.data.sync.SongKey
import com.whale.music.player.MusicPlayerManager
import com.whale.music.player.PlayMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val likedCatalog: LikedCatalog
) : ViewModel() {

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
     */
    private val favoriteState = combine(
        playerManager.currentSong,
        repository.allSongs,
        remoteSongDao.getAll(),
        likedCatalog.keys
    ) { currentSong, songs, remoteFavorites, likedKeys ->
        when {
            currentSong == null -> false
            // 网易云红心只读点亮：按歌名+歌手归一化匹配，不用用户再点一次
            SongKey.variants(currentSong.title, currentSong.artist).any { it in likedKeys } -> true
            currentSong.albumId == REMOTE_ALBUM_ID ->
                remoteFavorites.any { it.songId == "kw_${currentSong.id}" }
            else -> songs.firstOrNull { it.id == currentSong.id }?.isFavorite ?: false
        }
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

    /** 爱心：本地歌写本地库；网络歌写在线收藏表（收藏后出现在音乐库列表里） */
    fun toggleFavorite() {
        val song = uiState.value.currentSong ?: return
        // 网易云红心是只读真值源：这类歌爱心恒亮，点击不反向回写
        if (SongKey.variants(song.title, song.artist).any { it in likedCatalog.keys.value }) return
        viewModelScope.launch {
            if (song.albumId == REMOTE_ALBUM_ID) {
                val songId = "kw_${song.id}"
                if (remoteSongDao.count(songId) > 0) {
                    remoteSongDao.delete(songId)
                } else {
                    remoteSongDao.insert(
                        RemoteSong(
                            songId = songId,
                            source = "kw",
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
        /** RemoteMusicController.toLocalSong 给网络歌的 albumId 标记 */
        const val REMOTE_ALBUM_ID = -1L
    }
}
