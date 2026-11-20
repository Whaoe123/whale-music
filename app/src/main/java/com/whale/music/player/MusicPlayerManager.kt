package com.whale.music.player

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.whale.music.data.model.Song
import com.whale.music.data.repository.MusicRepository
import com.whale.music.data.sync.SongKey
import com.whale.music.util.CoverResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI 面向的 MediaController 门面。
 *
 * 播放器本身位于 [MusicPlayerService]，因此 Activity 销毁后播放不会中断。
 */
@Singleton
class MusicPlayerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: MusicRepository,
    private val playbackMemory: com.whale.music.data.local.PlaybackMemory
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()
    private val pendingLock = Any()

    /**
     * 当前队列长度（跨线程可见）。hasQueue() 可能在任意线程被调用，
     * 不能直接摸 MediaController —— Media3 要求所有 Player 方法都必须在主线程。
     */
    @Volatile
    private var queueCount = 0
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var progressJob: Job? = null
    private var playlist: List<Song> = emptyList()
    private var songsById: Map<String, Song> = emptyMap()
    private val coverUrls = mutableMapOf<String, String>()

    /** 收藏网络歌时取封面用（mediaId = 平台原始 id） */
    fun coverUrlFor(mediaId: String): String? = coverUrls[mediaId]
    private var lastPersistedPosition = 0L
    /** 上次写入开机快照的曲目 key，避免进度循环里重复写盘 */
    private var lastStartupKey = ""

    private val prefs = context.getSharedPreferences("ohmusic_prefs", Context.MODE_PRIVATE)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    /** 当前封面（本地取 MediaStore，在线取解析出的封面 URL）— 播放页/背景统一使用 */
    private val _artwork = MutableStateFlow<Any?>(null)
    val artwork: StateFlow<Any?> = _artwork.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _playMode = MutableStateFlow(
        PlayMode.entries.getOrElse(prefs.getInt("play_mode", 0)) { PlayMode.SEQUENCE }
    )
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    private val controllerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaController = controller ?: return
            if (!mediaController.isConnected || mediaItem == null) return
            val position = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                mediaController.currentPosition.coerceAtLeast(0L)
            } else {
                0L
            }
            persistCurrentMedia(mediaItem.mediaId, position)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val mediaController = controller ?: return
            if (mediaController.isConnected && !isPlaying && mediaController.mediaItemCount > 0) {
                persistPosition(mediaController.currentPosition.coerceAtLeast(0L))
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            syncState(player)
        }
    }

    /** 首帧后或首次播放操作时再连接 Service，避免播放器初始化阻塞冷启动关键路径。 */
    fun connect() {
        ensureController()
    }

    private fun ensureController() {
        if (controller?.isConnected == true || controllerFuture != null) return

        val future = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, MusicPlayerService::class.java))
        ).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val connectedController = future.get()
                    controller = connectedController
                    connectedController.addListener(controllerListener)
                    applyPlayMode(connectedController)
                    syncState(connectedController)
                    synchronized(pendingLock) {
                        pendingActions.toList().also { pendingActions.clear() }
                    }.forEach { runCatching { it(connectedController) } }
                    scope.launch { restorePlaylistIfNeeded(connectedController) }
                } catch (error: Exception) {
                    controllerFuture = null
                    Log.e(TAG, "连接播放服务失败", error)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun setPlaylist(
        songs: List<Song>,
        startIndex: Int = 0,
        extraCovers: Map<String, String> = emptyMap(),
        startPositionMs: Long = 0L
    ) {
        if (songs.isEmpty()) return
        playlist = songs
        queueCount = songs.size
        songsById = songs.associateBy { it.id.toString() }
        coverUrls.putAll(extraCovers)
        val safeIndex = startIndex.coerceIn(songs.indices)
        val snapshot = songs.toList()
        // 首曲也要进快照 key（onMediaItemTransition 在从头播时不一定触发）
        snapshot.getOrNull(safeIndex)?.let {
            lastStartupKey = SongKey.keyOf(it.title, it.artist)
        }

        if (songs.size <= LARGE_QUEUE_THRESHOLD) {
            persistQueue(snapshot, safeIndex, startPositionMs)
            withController { mediaController ->
                mediaController.setMediaItems(snapshot.map(::toMediaItem), safeIndex, startPositionMs)
                applyPlayMode(mediaController)
                mediaController.prepare()
                mediaController.play()
            }
        } else {
            // 大列表：MediaItem 构建与持久化移到后台，避免主线程卡顿导致点歌迟钝
            scope.launch {
                val items = withContext(Dispatchers.Default) {
                    snapshot.map(::toMediaItem)
                }
                withContext(Dispatchers.Default) { persistQueue(snapshot, safeIndex, startPositionMs) }
                withController { mediaController ->
                    mediaController.setMediaItems(items, safeIndex, startPositionMs)
                    applyPlayMode(mediaController)
                    mediaController.prepare()
                    mediaController.play()
                }
            }
        }
    }

    /**
     * 追加歌曲到当前队列末尾（不打断播放）
     * 用于在线歌曲渐进解析：先播当前首，其余解析完成后追加
     */
    fun appendToQueue(songs: List<Song>, extraCovers: Map<String, String> = emptyMap()) {
        if (songs.isEmpty()) return
        val existing = songsById.keys
        val fresh = songs.filter { it.id.toString() !in existing }
        if (fresh.isEmpty()) return
        playlist = playlist + fresh
        queueCount += fresh.size
        songsById = songsById + fresh.associateBy { it.id.toString() }
        coverUrls.putAll(extraCovers)
        withController { mediaController ->
            mediaController.addMediaItems(fresh.map(::toMediaItem))
            applyPlayMode(mediaController)
        }
    }

    /** 删除本地文件后把这首歌从播放队列里摘掉，避免播到已被删除的文件 */
    fun removeFromQueue(songId: Long) {
        val key = songId.toString()
        playlist = playlist.filterNot { it.id == songId }
        if (queueCount > 0) queueCount--
        songsById = songsById - key
        coverUrls.remove(key)
        withController { player ->
            for (i in 0 until player.mediaItemCount) {
                if (player.getMediaItemAt(i).mediaId == key) {
                    player.removeMediaItem(i)
                    break
                }
            }
        }
    }

    fun play(song: Song, songs: List<Song> = playlist, extraCovers: Map<String, String> = emptyMap()) {
        if (songs.isEmpty()) return
        // 手动播本地/酷我歌曲：清掉汽水上下文与开机快照，避免冷启动把用户点的歌覆盖回去
        playbackMemory.clearQishui()
        playbackMemory.clearStartupSnapshot()
        val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        setPlaylist(songs, index, extraCovers)
    }

    fun togglePlay() = withController { player ->
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() = withController { player ->
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else if (player.mediaItemCount > 0 && _playMode.value == PlayMode.SEQUENCE) {
            player.seekToDefaultPosition(0)
        }
    }

    fun previous() = withController { player ->
        if (player.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
            player.seekTo(0)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else if (player.mediaItemCount > 0 && _playMode.value == PlayMode.SEQUENCE) {
            player.seekToDefaultPosition(player.mediaItemCount - 1)
        }
    }

    fun seekTo(fraction: Float) = withController { player ->
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return@withController
        player.seekTo((fraction.coerceIn(0f, 1f) * duration).toLong())
        syncProgress(player)
    }

    /** 按毫秒定位（恢复上次进度用） */
    fun seekToMs(ms: Long) = withController { player ->
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val target = if (duration != null) ms.coerceIn(0L, duration) else ms.coerceAtLeast(0L)
        player.seekTo(target)
        syncProgress(player)
    }

    /**
     * 当前是否已有可播队列（自动播放前先判断，避免抢用户的手动操作）。
     * 读线程安全计数，不在调用线程上摸 MediaController（Media3 只允许主线程）。
     */
    fun hasQueue(): Boolean = queueCount > 0

    /** 播放队列快照（播放页「更多」用） */
    fun queueSnapshot(): List<Song> = playlist

    /** 跳到队列第 index 首 */
    fun playAt(index: Int) = withController { player ->
        if (index in playlist.indices) player.seekToDefaultPosition(index)
    }

    fun togglePlayMode() {
        _playMode.value = nextPlayMode(_playMode.value)
        prefs.edit { putInt("play_mode", _playMode.value.ordinal) }
        withController(::applyPlayMode)
    }

    fun release() {
        progressJob?.cancel()
        controller?.removeListener(controllerListener)
        controller?.release()
        controller = null
        controllerFuture?.cancel(false)
        controllerFuture = null
    }

    private fun withController(action: (MediaController) -> Unit) {
        // Media3 的 MediaController 只允许在主线程调用，
        // 任何来自 IO 线程的播放操作（如冷启动自动播放）都必须先切回主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { withController(action) }
            return
        }
        val current = controller
        if (current != null && current.isConnected) {
            runCatching { action(current) }
                .onFailure { Log.e(TAG, "播放器操作失败", it) }
        } else {
            synchronized(pendingLock) { pendingActions += action }
            ensureController()
        }
    }

    private fun applyPlayMode(player: Player) {
        player.repeatMode = repeatModeFor(_playMode.value)
        player.shuffleModeEnabled = shuffleEnabledFor(_playMode.value)
    }

    private fun syncState(player: Player) {
        if (player is MediaController && !player.isConnected) return
        _isPlaying.value = player.isPlaying
        val mediaId = player.currentMediaItem?.mediaId
        _currentSong.value = mediaId?.let(songsById::get) ?: _currentSong.value
        _artwork.value = mediaId?.let { id ->
            coverUrls[id]?.toUri() ?: songsById[id]?.let { CoverResolver.resolveSong(it.id, it.albumId) }
        } ?: _currentSong.value?.let { CoverResolver.resolveSong(it.id, it.albumId) }
        if (!mediaId.isNullOrEmpty()) {
            prefs.edit { putString(KEY_CURRENT_MEDIA_ID, mediaId) }
        }
        syncProgress(player)

        if (!player.isPlaying && player.mediaItemCount > 0) {
            persistPosition(player.currentPosition.coerceAtLeast(0L))
        }

        if (player.isPlaying) startProgressLoop() else stopProgressLoop()
    }

    private fun syncProgress(player: Player) {
        val safeDuration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val safePosition = player.currentPosition.coerceAtLeast(0L)
        _duration.value = safeDuration
        _currentPosition.value = safePosition
        _progress.value = if (safeDuration > 0) {
            (safePosition.toFloat() / safeDuration).coerceIn(0f, 1f)
        } else {
            0f
        }
        if (kotlin.math.abs(safePosition - lastPersistedPosition) >= POSITION_SAVE_INTERVAL_MS) {
            persistPosition(safePosition)
        }
    }

    private fun startProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (true) {
                controller?.let(::syncProgress)
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun toMediaItem(song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(song.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setArtworkUri(
                    coverUrls[song.id.toString()]?.toUri()
                        ?: CoverResolver.resolveSong(song.id, song.albumId)
                )
                .build()
        )
        .build()

    private suspend fun restorePlaylistIfNeeded(mediaController: MediaController) {
        if (!mediaController.isConnected || mediaController.mediaItemCount > 0) return
        // 有有效的开机快照时，把恢复权整个交给 StartupViewModel（在线歌不在本地表里，
        // 这里按 id 找不回来，若强行恢复半截本地队列反而会把 hasQueue 撑成 true）。
        // 用户手动点歌会清掉快照，本地歌曲的恢复照常走本方法。
        if (playbackMemory.hasStartupSnapshot &&
            playbackMemory.isStartupSnapshotFresh(4 * 60 * 60 * 1000L)
        ) return
        val ids = prefs.getString(KEY_QUEUE_IDS, null)
            ?.split(',')
            ?.mapNotNull(String::toLongOrNull)
            .orEmpty()
        if (ids.isEmpty()) return

        val songsByStoredId = repository.allSongs.first().associateBy(Song::id)
        val restoredSongs = ids.mapNotNull(songsByStoredId::get)
        if (restoredSongs.isEmpty() || mediaController.mediaItemCount > 0) return

        playlist = restoredSongs
        queueCount = restoredSongs.size
        songsById = restoredSongs.associateBy { it.id.toString() }
        val currentId = prefs.getString(KEY_CURRENT_MEDIA_ID, null)
        val currentIndex = restoredSongs.indexOfFirst { it.id.toString() == currentId }
            .coerceAtLeast(0)
        val position = prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L)
        mediaController.setMediaItems(restoredSongs.map(::toMediaItem), currentIndex, position)
        applyPlayMode(mediaController)
        mediaController.prepare()
        syncState(mediaController)
    }

    private fun persistQueue(songs: List<Song>, currentIndex: Int, position: Long) {
        prefs.edit {
            putString(KEY_QUEUE_IDS, songs.joinToString(",") { it.id.toString() })
            putString(KEY_CURRENT_MEDIA_ID, songs.getOrNull(currentIndex)?.id?.toString())
            putLong(KEY_POSITION, position)
        }
        lastPersistedPosition = position
    }

    private fun persistPosition(position: Long) {
        lastPersistedPosition = position
        prefs.edit { putLong(KEY_POSITION, position) }
        // 汽水链路额外记一份，冷启动靠它恢复现场
        if (playbackMemory.playlistId.isNotEmpty()) {
            playbackMemory.positionMs = position
        }
        // 开机歌单快照：持续写精准进度（内部有「快照不存在则不写」保护）
        if (lastStartupKey.isNotEmpty()) {
            playbackMemory.updateStartupProgress(lastStartupKey, position)
        }
    }

    private fun persistCurrentMedia(mediaId: String, position: Long) {
        lastPersistedPosition = position
        prefs.edit {
            putString(KEY_CURRENT_MEDIA_ID, mediaId)
            putLong(KEY_POSITION, position)
        }
        // 切歌时更新快照里「当前是哪一首」，进度归零
        songsById[mediaId]?.let { song ->
            lastStartupKey = SongKey.keyOf(song.title, song.artist)
            playbackMemory.updateStartupProgress(lastStartupKey, position)
        }
    }

    private companion object {
        const val TAG = "MusicPlayerManager"
        const val LARGE_QUEUE_THRESHOLD = 150
        const val PROGRESS_INTERVAL_MS = 500L
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        const val POSITION_SAVE_INTERVAL_MS = 5_000L
        const val KEY_QUEUE_IDS = "queue_ids"
        const val KEY_CURRENT_MEDIA_ID = "current_media_id"
        const val KEY_POSITION = "position_ms"
    }
}

enum class PlayMode {
    SEQUENCE,
    REPEAT_ONE,
    SHUFFLE
}
