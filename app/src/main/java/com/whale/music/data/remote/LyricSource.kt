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
        val obj: JSONObject = json.optJSONObject("lrc") ?: return ""
        return obj.optString("lyric")
    }

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
