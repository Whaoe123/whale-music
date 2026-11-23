package com.whale.music.data.sync

import com.whale.music.data.dao.CustomPlaylistDao
import com.whale.music.data.model.CustomPlaylist
import com.whale.music.data.model.CustomPlaylistSong
import com.whale.music.ncm.NcmApi
import com.whale.music.ncm.NcmSession
import com.whale.music.ncm.NcmSongDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs the logged-in NetEase Cloud "liked songs" playlist into a fixed
 * library playlist. Every sync replaces all rows (no duplicates, no leftovers).
 */
@Singleton
class NcmLikedSync @Inject constructor(
    private val session: NcmSession,
    private val dao: CustomPlaylistDao
) {
    private val mutex = Mutex()

    /** Last successful sync time (ms), used for startup throttling. */
    @Volatile
    var lastSyncMs: Long = 0L
        private set

    data class Result(
        val count: Int,
        val error: String? = null,
        val missing: Int = 0,
        val detail: String = ""
    ) {
        val ok: Boolean get() = error == null && count > 0
    }

    /**
     * Pull the whole liked playlist and write it into the library.
     * Returns the number of songs written, or an error message.
     */
    suspend fun sync(force: Boolean = false): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            // 短时间内重复调用直接跳过（登录成功瞬间可能触发两次）
            if (!force && System.currentTimeMillis() - lastSyncMs < 30_000L && lastSyncMs > 0L) {
                return@withLock Result(0, "throttled")
            }
            if (!session.hasCookies()) {
                return@withLock Result(0, "not_logged_in")
            }
            val uid = session.profile.value?.userId
                ?: session.refreshProfile()?.userId
            if (uid == null) {
                return@withLock Result(0, "profile_failed")
            }
            val fetch = runCatching { NcmApi.likedSongsDetailed(session, uid) }.getOrElse {
                return@withLock Result(0, "network_failed")
            }
            val songs = fetch.songs
            if (songs.isEmpty()) {
                return@withLock Result(0, "empty")
            }
            writeLocked(songs)
            lastSyncMs = System.currentTimeMillis()
            Result(songs.size, missing = fetch.missing, detail = fetch.diagnostics())
        }
    }

    /** Write an already-fetched list (used by loadLiked to avoid a second request). */
    suspend fun save(songs: List<NcmSongDto>): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (songs.isEmpty()) return@withLock Result(0, "empty")
            writeLocked(songs)
            lastSyncMs = System.currentTimeMillis()
            Result(songs.size)
        }
    }

    /**
     * 把一首歌的「喜欢」写回网易云，并**就地**补/删同步歌单里的那一行 ——
     * 这样爱心立刻跟手变化，不用等下次全量同步。
     *
     * 只动一行是刻意的：全量 sync() 会把整张红心歌单重拉一遍，
     * 用户每点一次爱心就拉几千首太浪费。
     */
    suspend fun setLiked(
        songId: Long,
        liked: Boolean,
        title: String,
        artist: String,
        album: String,
        coverUrl: String,
        durationMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!session.hasCookies()) return@withLock false
            val ok = runCatching { NcmApi.like(session, songId, liked) }.getOrDefault(false)
            if (!ok) return@withLock false

            val existing = dao.playlistByKind(CustomPlaylist.KIND_NCM_SYNC)
            val pid = existing?.id ?: if (liked) {
                dao.insertPlaylist(
                    CustomPlaylist(
                        name = CustomPlaylist.NCM_SYNC_NAME,
                        coverUrl = coverUrl,
                        kind = CustomPlaylist.KIND_NCM_SYNC,
                        source = "netease"
                    )
                )
            } else {
                return@withLock true // 歌单都还不存在，取消就没什么可删的
            }
            val key = CustomPlaylistSong.extKey("ncm", songId.toString())
            if (liked) {
                dao.insertSong(
                    CustomPlaylistSong(
                        playlistId = pid,
                        songKey = key,
                        title = title,
                        artist = artist,
                        album = album,
                        coverUrl = coverUrl,
                        duration = durationMs,
                        position = 0
                    )
                )
            } else {
                dao.removeSong(pid, key)
            }
            true
        }
    }

    private suspend fun writeLocked(songs: List<NcmSongDto>) {
        val existing = dao.playlistByKind(CustomPlaylist.KIND_NCM_SYNC)
        val pid = existing?.id ?: dao.insertPlaylist(
            CustomPlaylist(
                name = CustomPlaylist.NCM_SYNC_NAME,
                coverUrl = songs.firstOrNull()?.coverUrl.orEmpty(),
                kind = CustomPlaylist.KIND_NCM_SYNC,
                source = "netease"
            )
        )
        val rows = songs.mapIndexed { index, s ->
            CustomPlaylistSong(
                playlistId = pid,
                songKey = CustomPlaylistSong.extKey("ncm", s.id.toString()),
                title = s.title,
                artist = s.artist,
                album = s.album,
                coverUrl = s.coverUrl.orEmpty(),
                duration = s.durationMs,
                position = index
            )
        }
        dao.replaceSongs(pid, rows)
        dao.setCover(pid, songs.firstOrNull()?.coverUrl.orEmpty())
    }
}
