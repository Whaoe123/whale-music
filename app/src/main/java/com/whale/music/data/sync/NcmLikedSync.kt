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

    data class Result(val count: Int, val error: String? = null) {
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
            val songs = runCatching { NcmApi.likedSongs(session, uid) }.getOrElse {
                return@withLock Result(0, "network_failed")
            }
            if (songs.isEmpty()) {
                return@withLock Result(0, "empty")
            }
            writeLocked(songs)
            lastSyncMs = System.currentTimeMillis()
            Result(songs.size)
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
