package com.whale.music.data.remote

import com.whale.music.ncm.NcmHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

data class LyricLine(val timeMs: Long, val text: String)

/**
 * 歌词源 — 按「歌名 + 歌手」检索网易（匿名可用），LRC 解析 + 内存缓存 + 磁盘哨兵
 * 本地歌曲、在线歌曲通用。
 *
 * 负缓存策略（Sonance 同款防限流）：
 * - 「搜不到 / 没歌词」写**空哨兵文件**，TTL 内不再打接口；
 * - 网络异常（超时 / 风控）**不写缓存**，下次照常重试；
 * - 有歌词的命中记 30 天。
 */
object LyricSource {

    private val memory = HashMap<String, List<LyricLine>>()

    /** 磁盘哨兵目录（OHMusicApp 启动时挂上；没挂就只用内存缓存） */
    @Volatile
    private var diskDir: java.io.File? = null

    private const val DISK_HIT_TTL_MS = 30L * 24 * 3600_000
    private const val DISK_MISS_TTL_MS = 2L * 24 * 3600_000

    fun attachCacheDir(cacheRoot: java.io.File) {
        diskDir = java.io.File(cacheRoot, "lyrics")
    }

    /** 清歌词缓存（设置页「清理缓存」用） */
    fun clearCache() {
        memory.clear()
        val dir = diskDir ?: return
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    suspend fun load(title: String, artist: String): List<LyricLine> = withContext(Dispatchers.IO) {
        val key = "$title|$artist"
        memory[key]?.let { return@withContext it }
        readDisk(key)?.let { raw ->
            return@withContext parseLrc(raw).also { memory[key] = it }
        }
        // null = 传输失败（不缓存，下次还试）；"" = 语义空结果（写哨兵）
        val raw = try {
            val sid = searchSongId("$title $artist")
            if (sid == null) "" else fetchLyric(sid)
        } catch (e: Exception) {
            null
        } ?: return@withContext emptyList()
        writeDisk(key, raw)
        parseLrc(raw).also { memory[key] = it }
    }

    private fun fileFor(key: String): java.io.File? {
        val dir = diskDir ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "$name.lrc")
    }

    /** 返回磁盘上的歌词原文；null = 没有或已过期（过期顺手删掉）；"" = 空哨兵 */
    private fun readDisk(key: String): String? {
        val f = fileFor(key) ?: return null
        if (!f.exists()) return null
        val age = System.currentTimeMillis() - f.lastModified()
        val ttl = if (f.length() > 0) DISK_HIT_TTL_MS else DISK_MISS_TTL_MS
        if (age >= ttl) {
            f.delete()
            return null
        }
        return try {
            f.readText()
        } catch (e: Exception) {
            null
        }
    }

    private fun writeDisk(key: String, raw: String) {
        val f = fileFor(key) ?: return
        try {
            f.parentFile?.mkdirs()
            f.writeText(raw)
        } catch (e: Exception) {
            runCatching { f.delete() }
        }
    }

    private fun searchSongId(keyword: String): Long? {
        val url = "https://music.163.com/api/search/get/web?s=" +
            URLEncoder.encode(keyword, "UTF-8") + "&type=1&limit=1"
        val json = NcmHttp.get(url, "")
        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return null
        val id = songs.optJSONObject(0)?.optLong("id") ?: return null
        return id.takeIf { it != 0L }
    }

    private fun fetchLyric(songId: Long): String {
        val json = NcmHttp.get(
            "https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1",
            ""
        )
        val lrcObj: JSONObject = json.optJSONObject("lrc") ?: return ""
        val lrc = lrcObj.optString("lyric")
        if (lrc.isBlank()) return ""
        // tv=-1 已经把译文一并返回了；不取白不取 —— 有译文就并到同一行
        val tlrc = json.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        return if (tlrc.isBlank()) lrc else mergeTranslation(lrc, tlrc)
    }

    /**
     * 把翻译歌词并到原文的**同一行**（`原文 / 译文`）。
     *
     * 为什么另起一行不行：解析器会把没有时间戳的裸行整个丢掉；
     * 同一时间戳写两行又会生成两条，高亮只认其中一条，看着像错位。
     * 并到同一行就是一个时间戳、一行文字、一个高亮。
     *
     * 时间戳要按**毫秒**配对，不能按字符串 —— 原文 `[00:30.542]` 和
     * 译文 `[00:30.54]` 格式常不一致，按字符串配对会整段对不上。
     *
     * （算法移植自 Sonance 播放器 `NeteaseMatch.mergeTranslation`）
     */
    private fun mergeTranslation(lrc: String, translation: String): String {
        val translations = HashMap<Int, String>()
        for (raw in translation.split('\n')) {
            val (ms, text) = splitTaggedLine(raw) ?: continue
            if (text.isNotEmpty()) translations[ms] = text
        }
        if (translations.isEmpty()) return lrc

        val out = StringBuilder(lrc.length * 2)
        for (raw in lrc.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parsed = splitTaggedLine(line)
            if (parsed == null) {
                // 元信息行（[ti:] / [ar:] …）原样保留
                out.append(line).append('\n')
                continue
            }
            val (ms, text) = parsed
            out.append(line)
            val tr = translations[ms]
            // 译文和原文一样就不重复贴（有些歌的"翻译"其实是原文）
            if (tr != null && tr != text) out.append(TRANSLATION_SEP).append(tr)
            out.append('\n')
        }
        return out.toString().trimEnd()
    }

    /** 拆出 `[时间戳]正文` 的毫秒与正文；认不出来返回 null */
    private fun splitTaggedLine(raw: String): Pair<Int, String>? {
        val line = raw.trim()
        if (!line.startsWith("[")) return null
        val close = line.indexOf(']')
        if (close <= 1) return null
        val ms = timeKeyMs(line.substring(1, close)) ?: return null
        return ms to line.substring(close + 1).trim()
    }

    /** `mm:ss` / `mm:ss.xx` / `mm:ss.xxx` / `hh:mm:ss` → 毫秒；认不出来返回 null */
    private fun timeKeyMs(tag: String): Int? {
        val parts = tag.trim().split(':')
        if (parts.size < 2 || parts.size > 3) return null
        return try {
            val ms = if (parts.size == 3) {
                ((parts[0].trim().toLong() * 3600 + parts[1].trim().toLong() * 60) * 1000L) +
                    (parts[2].trim().toDouble() * 1000).toLong()
            } else {
                (parts[0].trim().toLong() * 60 * 1000L) + (parts[1].trim().toDouble() * 1000).toLong()
            }
            ms.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        } catch (t: Throwable) {
            null
        }
    }

    /** 原文与译文的分隔符（换行会让解析器把译文当裸行丢掉，所以用它） */
    private const val TRANSLATION_SEP = " / "

    /** 解析 LRC：[mm:ss.xx] 或 [mm:ss:xx] */
    fun parseLrc(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()
        val regex = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        val out = mutableListOf<LyricLine>()
        lrc.lineSequence().forEach { line ->
            val matches = regex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    1 -> frac.toLongOrNull()?.times(100) ?: 0
                    2 -> frac.toLongOrNull()?.times(10) ?: 0
                    3 -> frac.toLongOrNull() ?: 0
                    else -> 0
                }
                out += LyricLine(timeMs = min * 60_000 + sec * 1_000 + ms, text = text)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
