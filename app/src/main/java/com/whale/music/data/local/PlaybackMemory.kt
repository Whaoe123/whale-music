package com.whale.music.data.local

import android.content.Context
import com.whale.music.data.model.QsTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放记忆：记录「上次听的开机歌单、听到第几首、听到哪一秒」。
 *
 * 两套机制并存：
 * - 老的 playlistId / trackIndex / positionMs：第二页手动点汽水歌单时用；
 * - 新的「开机歌单快照」：冷启动自动播放用，整张列表序列化存盘，
 *   切歌/暂停时写当前曲目 key + 精准进度，4 小时内接着放，超过才重新拉推荐。
 */
@Singleton
class PlaybackMemory @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val sp = context.getSharedPreferences("whale_playback", Context.MODE_PRIVATE)

    /** 启动自动播放开关，默认开 */
    var autoplayEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTOPLAY, true)
        set(value) = sp.edit().putBoolean(KEY_AUTOPLAY, value).apply()

    /** 上次播放的汽水歌单 id，空表示没有记录 */
    var playlistId: String
        get() = sp.getString(KEY_PLAYLIST, "").orEmpty()
        set(value) = sp.edit().putString(KEY_PLAYLIST, value).apply()

    /** 歌单里的第几首 */
    var trackIndex: Int
        get() = sp.getInt(KEY_INDEX, 0)
        set(value) = sp.edit().putInt(KEY_INDEX, value).apply()

    /** 播放进度（毫秒） */
    var positionMs: Long
        get() = sp.getLong(KEY_POSITION, 0L)
        set(value) = sp.edit().putLong(KEY_POSITION, value).apply()

    /** 记录一次汽水点播，进度归零 */
    fun rememberQishui(playlistId: String, index: Int) {
        sp.edit()
            .putString(KEY_PLAYLIST, playlistId)
            .putInt(KEY_INDEX, index)
            .putLong(KEY_POSITION, 0L)
            .apply()
    }

    /** 播到本地歌曲时清掉汽水上下文，避免把本地进度写成汽水的 */
    fun clearQishui() {
        sp.edit()
            .putString(KEY_PLAYLIST, "")
            .putLong(KEY_POSITION, 0L)
            .apply()
    }

    fun clearPosition() {
        sp.edit().putLong(KEY_POSITION, 0L).apply()
    }

    // ───────── 开机歌单快照 ─────────

    private var snapshotJson: String
        get() = sp.getString(KEY_SNAP_JSON, "").orEmpty()
        set(value) = sp.edit().putString(KEY_SNAP_JSON, value).apply()

    private var snapshotKey: String
        get() = sp.getString(KEY_SNAP_KEY, "").orEmpty()
        set(value) = sp.edit().putString(KEY_SNAP_KEY, value).apply()

    private var snapshotPositionMs: Long
        get() = sp.getLong(KEY_SNAP_POS, 0L)
        set(value) = sp.edit().putLong(KEY_SNAP_POS, value).apply()

    private var snapshotAt: Long
        get() = sp.getLong(KEY_SNAP_AT, 0L)
        set(value) = sp.edit().putLong(KEY_SNAP_AT, value).apply()

    var snapshotSource: String
        get() = sp.getString(KEY_SNAP_SOURCE, "").orEmpty()
        private set(value) = sp.edit().putString(KEY_SNAP_SOURCE, value).apply()

    val hasStartupSnapshot: Boolean get() = snapshotJson.isNotBlank()

    /** 拉取到一份新的开机歌单后调用；时间戳从这里起算 4 小时 */
    fun saveStartupSnapshot(tracks: List<QsTrack>, source: String) {
        if (tracks.isEmpty()) {
            clearStartupSnapshot()
            return
        }
        sp.edit()
            .putString(KEY_SNAP_JSON, encodeTracks(tracks))
            .putString(KEY_SNAP_KEY, "")
            .putLong(KEY_SNAP_POS, 0L)
            .putLong(KEY_SNAP_AT, System.currentTimeMillis())
            .putString(KEY_SNAP_SOURCE, source)
            .apply()
    }

    /** 播放过程中持续写：当前曲目 key + 精准进度（不刷新 4 小时计时） */
    fun updateStartupProgress(key: String, positionMs: Long) {
        if (!hasStartupSnapshot) return
        sp.edit()
            .putString(KEY_SNAP_KEY, key)
            .putLong(KEY_SNAP_POS, positionMs.coerceAtLeast(0L))
            .apply()
    }

    /** 用户手动点了别的歌 → 快照失效，下次冷启动重新拉推荐 */
    fun clearStartupSnapshot() {
        sp.edit()
            .remove(KEY_SNAP_JSON)
            .remove(KEY_SNAP_KEY)
            .remove(KEY_SNAP_POS)
            .remove(KEY_SNAP_AT)
            .remove(KEY_SNAP_SOURCE)
            .apply()
    }

    fun isStartupSnapshotFresh(maxAgeMs: Long): Boolean =
        hasStartupSnapshot &&
            snapshotAt > 0L &&
            System.currentTimeMillis() - snapshotAt < maxAgeMs

    data class StartupState(
        val tracks: List<QsTrack>,
        val currentKey: String,
        val positionMs: Long,
        val source: String
    )

    fun loadStartupState(): StartupState? {
        val tracks = decodeTracks(snapshotJson)
        if (tracks.isEmpty()) return null
        return StartupState(tracks, snapshotKey, snapshotPositionMs, snapshotSource)
    }

    private fun encodeTracks(tracks: List<QsTrack>): String {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("artist", t.artist)
                put("album", t.album)
                put("cover", t.cover)
                put("duration", t.duration)
                put("kwRid", t.kwRid)
            })
        }
        return arr.toString()
    }

    private fun decodeTracks(raw: String): List<QsTrack> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                QsTrack(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    cover = o.optString("cover"),
                    duration = o.optLong("duration", 0L),
                    kwRid = o.optLong("kwRid", -1L)
                )
            }.filter { it.title.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val KEY_AUTOPLAY = "autoplay"
        const val KEY_PLAYLIST = "qs_playlist"
        const val KEY_INDEX = "qs_index"
        const val KEY_POSITION = "qs_position"

        const val KEY_SNAP_JSON = "startup_tracks"
        const val KEY_SNAP_KEY = "startup_key"
        const val KEY_SNAP_POS = "startup_pos"
        const val KEY_SNAP_AT = "startup_at"
        const val KEY_SNAP_SOURCE = "startup_source"
    }
}
