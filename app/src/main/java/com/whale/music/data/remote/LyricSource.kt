package com.whale.music.data.remote

import com.whale.music.ncm.NcmHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

data class LyricLine(val timeMs: Long, val text: String)

/**
 * 歌词源 — 按「歌名 + 歌手」检索网易（匿名可用），LRC 解析 + 内存缓存
 * 本地歌曲、在线歌曲通用
 */
object LyricSource {

    private val cache = HashMap<String, List<LyricLine>>()

    suspend fun load(title: String, artist: String): List<LyricLine> = withContext(Dispatchers.IO) {
        val key = "$title|$artist"
        cache[key]?.let { return@withContext it }
        val lines = try {
            val sid = searchSongId("$title $artist") ?: return@withContext emptyList()
            val lrc = fetchLyric(sid)
            parseLrc(lrc)
        } catch (e: Exception) {
            emptyList()
        }
        cache[key] = lines
        lines
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
