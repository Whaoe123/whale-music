package com.whale.music.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whale.music.data.local.PlaybackMemory
import com.whale.music.data.model.QsTrack
import com.whale.music.data.remote.KwSource
import com.whale.music.data.remote.RemoteMusicController
import com.whale.music.data.sync.NcmLikedSync
import com.whale.music.data.sync.SongKey
import com.whale.music.ncm.NcmApi
import com.whale.music.ncm.NcmSession
import com.whale.music.ncm.NcmSongDto
import com.whale.music.player.MusicPlayerManager
import com.whale.music.util.CrashGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 冷启动自动播放 —— 记住上次听到哪一首、哪一秒，4 小时内接着放。
 *
 * 优先顺序：
 * 1. 开机快照未超过 4 小时 → 从快照里那首、那个精准进度续播（不重新拉推荐）；
 * 2. 快照过期 / 没有 → 按登录态重新拉：已登录网易云每日推荐，未登录热歌榜；
 * 3. 网易云整条链路失败 → 酷我热歌榜兜底（匿名可用）。
 *
 * 重新拉成功后会写入一份新快照。所有音频一律交给酷我出声。
 * 开关：设置页的「启动自动播放」关闭时完全不打扰。
 * 所有对播放器的调用一律切回主线程（Media3 只允许主线程操作）。
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val controller: RemoteMusicController,
    private val playerManager: MusicPlayerManager,
    private val memory: PlaybackMemory,
    private val ncmSession: NcmSession,
    private val ncmLikedSync: NcmLikedSync
) : ViewModel() {

    private var started = false

    fun maybeAutoPlay() {
        if (started) return
        started = true
        // 已登录则顺便同步一次网易云收藏（独立于自动播放开关，静默执行）
        if (ncmSession.hasCookies() && ncmLikedSync.lastSyncMs == 0L) {
            viewModelScope.launch {
                runCatching { ncmLikedSync.sync() }
            }
        }
        if (!memory.autoplayEnabled) return
        // 上次启动崩溃过：这次先不放，让 App 稳稳打开，用户可以手动点播
        if (CrashGuard.skipAutoplayOnce) return

        viewModelScope.launch {
            try {
                // 等首帧和权限弹窗过去，避免和冷启动绘制抢资源
                delay(900)
                if (withContext(Dispatchers.Main) { playerManager.hasQueue() }) return@launch
                // 4 小时内优先从快照精准续播
                if (resumeFromSnapshot()) return@launch
                playSmart()
            } catch (e: Exception) {
                // 自动播放失败绝不许把 App 带崩
                android.util.Log.w("StartupViewModel", "自动播放失败", e)
            }
        }
    }

    /** @return true 表示已从快照恢复，无需再拉新推荐 */
    private suspend fun resumeFromSnapshot(): Boolean {
        if (!memory.isStartupSnapshotFresh(SNAPSHOT_TTL_MS)) return false
        val state = memory.loadStartupState() ?: return false
        val tracks = state.tracks
        if (tracks.isEmpty()) return false
        val idx = findTrackIndex(tracks, state.currentKey)
        val err = withContext(Dispatchers.Main) {
            controller.playQishui(tracks, idx, state.positionMs, fromStartup = true)
        }
        return err == null
    }

    /** 用归一化 key 找回「上次听到的是第几首」，找不到就从头 */
    private fun findTrackIndex(tracks: List<QsTrack>, key: String): Int {
        if (key.isBlank()) return 0
        val exact = tracks.indexOfFirst { SongKey.keyOf(it.title, it.artist) == key }
        if (exact >= 0) return exact
        val loose = tracks.indexOfFirst { key in SongKey.variants(it.title, it.artist) }
        return loose.coerceAtLeast(0)
    }

    private suspend fun playSmart() {
        // ── 1. 网易云：登录日用推荐，未登录用热歌榜 ──
        val ncmTracks = withContext(Dispatchers.IO) {
            runCatching {
                val source = if (ncmSession.hasCookies()) {
                    NcmApi.recommendSongs(ncmSession, 50)
                } else {
                    NcmApi.publicPlaylistSongs(TOP_SONGS_PLAYLIST, 100)
                }
                source.mapNotNull { it.toQsTrack() }
            }.getOrDefault(emptyList())
        }
        if (ncmTracks.isNotEmpty()) {
            val err = withContext(Dispatchers.Main) {
                controller.playQishui(ncmTracks, 0, 0L, fromStartup = true)
            }
            if (err == null) {
                memory.saveStartupSnapshot(ncmTracks, "ncm")
                return
            }
        }

        // ── 2. 网易云整条链路失败 → 酷我热歌榜兜底（匿名可用，整曲完整下发）──
        val kwSongs = withContext(Dispatchers.IO) {
            runCatching { KwSource.recommend(30) }.getOrDefault(emptyList())
        }
        if (kwSongs.isNotEmpty()) {
            val tracks = kwSongs.map { it.toQsTrack() }
            withContext(Dispatchers.Main) {
                controller.playQishui(tracks, 0, 0L, fromStartup = true)
            }
            memory.saveStartupSnapshot(tracks, "kw")
        }
    }

    private fun NcmSongDto.toQsTrack(): QsTrack? {
        if (title.isBlank()) return null
        return QsTrack(
            id = "ncm_$id",
            title = title,
            artist = artist,
            album = album,
            cover = coverUrl.orEmpty(),
            duration = durationMs
        )
    }

    private fun com.whale.music.data.model.RemoteSong.toQsTrack(): QsTrack = QsTrack(
        id = songId,
        title = title,
        artist = artist,
        album = album,
        cover = coverUrl,
        duration = duration,
        kwRid = if (source == "kw") rid else -1L
    )

    private companion object {
        /** 网易云热歌榜，匿名可读，每天更新 */
        const val TOP_SONGS_PLAYLIST = 3778678L
        /** 快照有效期：4 小时后才重新拉推荐 */
        const val SNAPSHOT_TTL_MS = 4 * 60 * 60 * 1000L
    }
}
