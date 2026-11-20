package com.whale.music.data.lx

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.whale.music.data.model.QsTrack
import com.whale.music.data.model.RemoteSong
import com.whale.music.data.remote.SongMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 洛雪（lx）自定义音源管理：脚本导入 / 启停 / 删除 + 搜索 / 取流 / 歌词 / 封面。
 *
 * - 脚本存 `filesDir/lx_sources/<id>.js`，元信息与启用状态存同名 `.meta`；
 * - 每个脚本一个 [LxRuntime] 沙箱（懒加载，删除时销毁）；
 * - 搜索结果的 musicInfo 落盘缓存（播放取流要用脚本给的原始条目）；
 * - 曲目匹配复用 [SongMatcher] 的三重核对（歌手 + 时长 + 歌名），与酷我同一套标准。
 */
@Singleton
class LxSourceManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    data class LxScript(
        val id: String,
        val name: String,
        val enabled: Boolean
    )

    private val dir = File(context.filesDir, "lx_sources").apply { mkdirs() }
    private val runtimes = ConcurrentHashMap<String, LxRuntime>()
    private val probes = ConcurrentHashMap<String, LxRuntime.Probe>()

    private val _scripts = MutableStateFlow(loadList())
    val scripts: StateFlow<List<LxScript>> = _scripts.asStateFlow()

    /** 搜索结果 musicInfo 缓存：songIdKey → {__script, info}（落盘，重启可继续播） */
    private val musicInfo = ConcurrentHashMap<String, JSONObject>()
    private val infoFile = File(dir, "music_info.json")

    /** 会话内匹配缓存（与酷我 kwMatchCache 同思路：命中/未命中都记） */
    private val matchHit = ConcurrentHashMap<String, RemoteSong>()
    private val matchMiss = ConcurrentHashMap.newKeySet<String>()

    init {
        runCatching {
            if (infoFile.exists()) {
                val obj = JSONObject(infoFile.readText())
                obj.keys().forEach { k -> musicInfo[k] = obj.getJSONObject(k) }
            }
        }
    }

    // ───────── 脚本管理 ─────────

    /** 从 SAF Uri 导入 .js 脚本；返回 null = 成功，否则是给用户看的错误原因 */
    suspend fun importFrom(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(uri) ?: "未命名音源"
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return@withContext "读不到文件"
            if (!text.contains("musicUrl")) {
                return@withContext "这不像洛雪音源脚本（没有 musicUrl 方法）"
            }
            val id = "s${System.currentTimeMillis().toString(36)}"
            File(dir, "$id.js").writeText(text)
            File(dir, "$id.meta").writeText(
                JSONObject().put("name", name.removeSuffix(".js")).put("enabled", true).toString()
            )
            refreshList()
            null
        } catch (e: Exception) {
            "导入失败：${e.message ?: "未知错误"}"
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        runCatching {
            val meta = readMeta(id)
            meta.put("enabled", enabled)
            File(dir, "$id.meta").writeText(meta.toString())
        }
        refreshList()
        if (!enabled) destroyRuntime(id)
    }

    fun delete(id: String) {
        destroyRuntime(id)
        File(dir, "$id.js").delete()
        File(dir, "$id.meta").delete()
        refreshList()
    }

    private fun refreshList() {
        _scripts.value = loadList()
    }

    private fun loadList(): List<LxScript> {
        val files = dir.listFiles { f -> f.name.endsWith(".js") } ?: return emptyList()
        return files.mapNotNull { f ->
            val id = f.name.removeSuffix(".js")
            val meta = readMeta(id)
            LxScript(id, meta.optString("name", id), meta.optBoolean("enabled", true))
        }.sortedBy { it.name }
    }

    private fun readMeta(id: String): JSONObject = runCatching {
        JSONObject(File(dir, "$id.meta").readText())
    }.getOrDefault(JSONObject().put("name", id).put("enabled", true))

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }.getOrNull()

    // ───────── 运行时 ─────────

    /** 懒加载脚本沙箱；失败返回 null。只允许在后台线程调用（内部会阻塞等主线程） */
    private fun runtimeFor(script: LxScript): LxRuntime? {
        runtimes[script.id]?.let { return it }
        val file = File(dir, "${script.id}.js")
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val rt = LxRuntime(context)
        val probe = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.suspendCancellableCoroutine<LxRuntime.Probe> { cont ->
                rt.loadScript(text, headerInfoJson(script.name, text)) { p ->
                    cont.resumeWith(Result.success(p))
                }
            }
        }
        if (!probe.ok) {
            Log.w("LxSource", "脚本[${script.name}]编译失败：${probe.error}")
            rt.destroy()
            return null
        }
        probes[script.id] = probe
        runtimes[script.id] = rt
        return rt
    }

    /** 脚本头注释里的 @name / @version / @author 等，喂给 lx.currentScriptInfo */
    private fun headerInfoJson(fallbackName: String, text: String): String {
        fun read(key: String): String =
            Regex("@$key\\s+(.+)").find(text)?.groupValues?.get(1)?.trim() ?: ""
        return JSONObject().apply {
            put("name", read("name").ifBlank { fallbackName })
            put("description", read("description"))
            put("version", read("version"))
            put("author", read("author"))
            put("homepage", read("homepage"))
        }.toString()
    }

    private fun destroyRuntime(id: String) {
        runtimes.remove(id)?.destroy()
        probes.remove(id)
    }

    private fun scriptOf(id: String): LxScript? = _scripts.value.firstOrNull { it.id == id }

    // ───────── 搜索 ─────────

    /**
     * 按脚本搜索（脚本内可能有多个 source 条目，逐个调 musicSearch 后按
     * 「歌名+歌手」去重合并），映射成 RemoteSong（source="lx"）。
     * 同时把脚本返回的 musicInfo 落缓存 —— 取流要用。
     */
    suspend fun search(query: String, scriptId: String, pageSize: Int = 30): List<RemoteSong> =
        withContext(Dispatchers.IO) {
            val script = scriptOf(scriptId)?.takeIf { it.enabled } ?: return@withContext emptyList()
            val rt = runtimeFor(script) ?: return@withContext emptyList()
            val probe = probes[script.id] ?: return@withContext emptyList()
            if (!probe.hasSearch) return@withContext emptyList()

            val out = LinkedHashMap<String, RemoteSong>()
            for (src in probe.sources) {
                val args = JSONArray().put(query).put(1).put(pageSize).toString()
                val res = rt.invoke(src, "musicSearch", args)
                if (!res.ok) {
                    Log.w("LxSource", "musicSearch($src) 失败：${res.payload}")
                    continue
                }
                for (song in parseHits(res.payload, script.id, src)) {
                    val key = SongMatcher.normalizeTitle(song.title) + "|" +
                        SongMatcher.normalizeTitle(song.artist)
                    if (key !in out) out[key] = song
                }
            }
            saveInfo()
            out.values.toList()
        }

    /** 解析 musicSearch 返回的 {list:[...]}；sourceKey 记进 musicInfo 供取流用 */
    private fun parseHits(payload: String, scriptId: String, sourceKey: String): List<RemoteSong> {
        val obj = runCatching { JSONObject(payload) }.getOrNull()
        val arr: JSONArray = obj?.optJSONArray("list")
            ?: runCatching { JSONArray(payload) }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<RemoteSong>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val lxId = firstText(m, "songId", "songMid", "id", "musicId") ?: continue
            val title = firstText(m, "songName", "name", "title") ?: continue
            val artist = artistText(m)
            val album = firstText(m, "albumName", "album") ?: ""
            val duration = durationMs(m)
            val rid = ridOf(scriptId, lxId)
            val songId = "lx_$rid"
            // 落 musicInfo 缓存：取流时原样交回脚本（循环外统一落盘）
            musicInfo[songId] = JSONObject()
                .put("__script", scriptId)
                .put("__source", sourceKey)
                .put("info", m)
            out += RemoteSong(
                songId = songId,
                source = "lx",
                rid = rid,
                title = title,
                artist = artist,
                album = album,
                coverUrl = firstText(m, "pic", "cover", "picUrl", "image") ?: "",
                duration = duration
            )
        }
        return out
    }

    // ───────── 取流 ─────────

    /** lx 歌曲 → 播放直链；quality 形如 128k / 320k / flac / hires */
    suspend fun playUrl(song: RemoteSong, quality: String): String? = withContext(Dispatchers.IO) {
        val info = musicInfo[song.songId] ?: return@withContext null
        val scriptId = info.optString("__script")
        val script = scriptOf(scriptId)?.takeIf { it.enabled } ?: return@withContext null
        val rt = runtimeFor(script) ?: return@withContext null
        val probe = probes[scriptId] ?: return@withContext null
        val sourceKey = info.optString("__source")
            .takeIf { it in probe.sources } ?: probe.sources.firstOrNull() ?: return@withContext null
        val musicInfoJson = info.optJSONObject("info")?.toString() ?: return@withContext null

        // 质量链：按脚本声明的 qualitys 过滤，取第一个双方都认的档位
        val declared = probe.qualitys[sourceKey].orEmpty()
        val chain = qualityChain(quality)
            .filter { declared.isEmpty() || it in declared }
            .ifEmpty { listOf(declared.firstOrNull() ?: "128k") }
        for (q in chain) {
            val args = JSONArray().put(JSONObject(musicInfoJson)).put(q).toString()
            val res = rt.invoke(sourceKey, "musicUrl", args)
            if (!res.ok) {
                Log.w("LxSource", "musicUrl($q) 失败：${res.payload}")
                continue
            }
            val url = parseUrl(res.payload)
            if (!url.isNullOrBlank()) return@withContext url
        }
        null
    }

    /** 首选档位 → 降级链（与 LX 客户端习惯一致） */
    private fun qualityChain(preferred: String): List<String> = when (preferred) {
        "128k" -> listOf("128k")
        "192k" -> listOf("192k", "320k", "128k")
        "flac" -> listOf("flac", "flac24bit", "hires", "320k", "128k")
        "hires" -> listOf("hires", "flac", "flac24bit", "320k")
        else -> listOf("320k", "128k")
    }

    private fun parseUrl(payload: String): String? {
        runCatching { JSONObject(payload).optString("url") }.getOrNull()
            ?.takeIf { it.startsWith("http") }?.let { return it }
        runCatching { JSONObject(payload).optString("data") }.getOrNull()
            ?.takeIf { it.startsWith("http") }?.let { return it }
        // 脚本直接返回字符串 url
        return payload.trim().trim('"').takeIf { it.startsWith("http") }
    }

    // ───────── 歌词 / 封面 ─────────

    suspend fun lyric(song: RemoteSong, lrcType: Int = 2): String? = withContext(Dispatchers.IO) {
        val info = musicInfo[song.songId] ?: return@withContext null
        val scriptId = info.optString("__script")
        val script = scriptOf(scriptId)?.takeIf { it.enabled } ?: return@withContext null
        val rt = runtimeFor(script) ?: return@withContext null
        val probe = probes[scriptId] ?: return@withContext null
        if (!probe.hasLyric) return@withContext null
        val src = info.optString("__source").takeIf { it in probe.sources }
            ?: probe.sources.firstOrNull() ?: return@withContext null
        val args = JSONArray().put(info.optJSONObject("info")).put(lrcType).toString()
        val res = rt.invoke(src, "lyric", args)
        if (!res.ok) return@withContext null
        runCatching { JSONObject(res.payload).optString("lyric") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: res.payload.trim().trim('"').takeIf { it.contains("[") }
    }

    suspend fun pic(song: RemoteSong): String? = withContext(Dispatchers.IO) {
        if (song.coverUrl.startsWith("http")) return@withContext song.coverUrl
        val info = musicInfo[song.songId] ?: return@withContext null
        val scriptId = info.optString("__script")
        val script = scriptOf(scriptId)?.takeIf { it.enabled } ?: return@withContext null
        val rt = runtimeFor(script) ?: return@withContext null
        val probe = probes[scriptId] ?: return@withContext null
        if (!probe.hasPic) return@withContext null
        val src = info.optString("__source").takeIf { it in probe.sources }
            ?: probe.sources.firstOrNull() ?: return@withContext null
        val args = JSONArray().put(info.optJSONObject("info")).toString()
        val res = rt.invoke(src, "pic", args)
        if (!res.ok) return@withContext null
        val p = runCatching { JSONObject(res.payload).optString("pic") }.getOrNull()
            ?: res.payload.trim().trim('"')
        p.takeIf { it.startsWith("http") }
    }

    // ───────── 兜底匹配（酷我找不到时） ─────────

    /**
     * 按「歌名 + 歌手」在启用的 lx 脚本里找同一首歌。
     * 与酷我同一套 [SongMatcher] 三重核对，宁可不播也不播错。
     */
    suspend fun matchLx(track: QsTrack): RemoteSong? = withContext(Dispatchers.IO) {
        matchHit[track.id]?.let { return@withContext it }
        if (track.id in matchMiss) return@withContext null
        for (script in _scripts.value.filter { it.enabled }) {
            val wantArtist = track.artist.takeIf { SongMatcher.meaningfulArtist(it) }.orEmpty()
            val queries = buildList {
                if (wantArtist.isNotBlank()) add("${track.title} $wantArtist".trim())
                if (track.title.isNotBlank()) add(track.title)
            }.distinct()
            for (q in queries) {
                val hits = runCatching { search(q, script.id, 20) }.getOrDefault(emptyList())
                val best = SongMatcher.pickBest(hits, track) ?: continue
                matchHit[track.id] = best.first
                return@withContext best.first
            }
        }
        matchMiss += track.id
        null
    }

    /** 全量清缓存（设置页「清理缓存」用） */
    fun clearCaches() {
        matchHit.clear()
        matchMiss.clear()
    }

    // ───────── 工具 ─────────

    private fun saveInfo() {
        runCatching {
            // 上限 400 条，超了丢最老的（ConcurrentHashMap 迭代序近似插入序）
            while (musicInfo.size > 400) {
                val eldest = musicInfo.keys.firstOrNull() ?: break
                musicInfo.remove(eldest)
            }
            val obj = JSONObject()
            musicInfo.forEach { (k, v) -> obj.put(k, v) }
            infoFile.writeText(obj.toString())
        }
    }

    private fun firstText(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k) ?: continue
            val s = v.toString()
            if (s.isNotBlank() && s != "null") return s
        }
        return null
    }

    private fun artistText(o: JSONObject): String {
        // singerName / artist 可能是字符串或数组
        for (k in listOf("singerName", "artist", "artists", "singer", "author")) {
            val v = o.opt(k) ?: continue
            when (v) {
                is JSONArray -> {
                    val parts = (0 until v.length()).mapNotNull { i ->
                        when (val x = v.opt(i)) {
                            is JSONObject -> x.optString("name").takeIf { it.isNotBlank() }
                            else -> x?.toString()?.takeIf { it.isNotBlank() }
                        }
                    }
                    if (parts.isNotEmpty()) return parts.joinToString("/")
                }
                else -> {
                    val s = v.toString()
                    if (s.isNotBlank() && s != "null") return s
                }
            }
        }
        return ""
    }

    /** interval 大概率是秒（洛雪惯例），小于 10000 的按秒换算成毫秒 */
    private fun durationMs(o: JSONObject): Long {
        val v = firstText(o, "interval", "duration", "time")?.toLongOrNull() ?: return 0L
        return if (v in 1..9_999) v * 1000 else v
    }

    /** lx 歌曲 id → 稳定 63 位正 Long（RemoteSong.rid / 本地 Song.id 都要用） */
    fun ridOf(scriptId: String, lxId: String): Long = ridOfImpl(scriptId, lxId)

    companion object {
        /** 纯函数：同一 (scriptId, lxId) 永远映射到同一个正 Long，重启不变 */
        fun ridOfImpl(scriptId: String, lxId: String): Long {
            val d = MessageDigest.getInstance("SHA-1")
                .digest("$scriptId|$lxId".toByteArray(Charsets.UTF_8))
            var h = 0L
            for (i in 0 until 8) h = (h shl 8) or (d[i].toLong() and 0xFF)
            return h and Long.MAX_VALUE
        }
    }
}
