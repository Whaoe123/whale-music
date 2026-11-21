package com.whale.music.data.remote

import com.whale.music.data.model.RemoteSong
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 哔哩哔哩数据源 —— 只做最后一级兜底：
 * 网易 / 酷我 / lx 全都拿不到的歌（没版权、冷门下架），按「歌名 + 歌手」
 * 在 B 站搜视频，取视频的音频流来播。B 站结果多是翻唱 / 现场，
 * 质量参差，所以顺序上永远排在正式音源之后。
 *
 * 接口均为网页端公开接口（不登录可用）：
 * - 搜索:   https://search.bilibili.com/all?keyword= （网页 HTML 内嵌 JSON）
 * - 详情:   https://api.bilibili.com/x/web-interface/view?aid= （补 cid）
 * - 取流:   https://api.bilibili.com/x/player/playurl?fnval=16 （DASH 音频轨，CDN 不校验防盗链）
 */
object BiliSource {

    private const val TIMEOUT = 12_000
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 查询词级缓存（含搜不到的负缓存），批量兜底时不反复打空查询 */
    private val searchCache = com.whale.music.util.TtlCache<List<RemoteSong>>(maxSize = 128)
    private const val SEARCH_HIT_TTL_MS = 10 * 60_000L
    private const val SEARCH_MISS_TTL_MS = 30 * 60_000L

    /** aid → (bvid, cid)：搜索结果自带，view 接口补查的也记下，取流不再多打一枪 */
    private val cidCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, Long>>()

    fun clearCaches() {
        searchCache.clear()
        cidCache.clear()
    }

    private fun httpGet(url: String, referer: String? = null): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Encoding", "identity")
            if (referer != null) setRequestProperty("Referer", referer)
        }
        return try {
            conn.responseCode
            conn.inputStream.use { s ->
                val out = StringBuilder()
                val buf = ByteArray(8192)
                while (true) {
                    val n = s.read(buf)
                    if (n <= 0) break
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                }
                out.toString()
            }
        } finally {
            conn.disconnect()
        }
    }

    // ───────── 搜索 ─────────

    fun search(keyword: String): List<RemoteSong> {
        searchCache.get(keyword)?.let { return it }
        val result = searchUncached(keyword)
        searchCache.put(
            keyword, result,
            if (result.isEmpty()) SEARCH_MISS_TTL_MS else SEARCH_HIT_TTL_MS
        )
        return result
    }

    private fun searchUncached(keyword: String): List<RemoteSong> {
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val html = httpGet(
            "https://search.bilibili.com/all?keyword=$enc&order=totalrank",
            "https://www.bilibili.com/"
        )
        // 网页把结果塞在 window.__INITIAL_STATE__={...} 里；后面可能还有别的脚本，
        // 用 JSONTokener 只解析第一个 JSON 值，尾部垃圾不管
        val key = "window.__INITIAL_STATE__="
        val i = html.indexOf(key)
        if (i < 0) return emptyList()
        val end = html.indexOf("</script>", i).let { if (it > 0) it else html.length }
        val blob = try {
            JSONTokener(html.substring(i + key.length, end).trim().removeSuffix(";"))
                .nextValue() as? JSONObject
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val hits = mutableListOf<JSONObject>()
        collectVideos(blob, hits)
        // 按 aid 去重，保序（网页已按综合排序，靠前的更靠谱）
        val result = LinkedHashMap<Long, RemoteSong>()
        for (h in hits) {
            val aid = h.optLong("aid", 0L)
            if (aid <= 0) continue
            val title = cleanHtml(h.optString("title"))
            if (title.isBlank()) continue
            val bvid = h.optString("bvid")
            val cid = h.optLong("cid", 0L)
            if (bvid.isNotBlank() && cid > 0) cidCache[aid] = bvid to cid
            if (result.containsKey(aid)) continue
            var pic = h.optString("pic")
            if (pic.startsWith("//")) pic = "https:$pic"
            result[aid] = RemoteSong(
                songId = "bili_$aid",
                source = "bili",
                rid = aid,
                title = title,
                artist = cleanHtml(h.optJSONObject("owner")?.optString("name").orEmpty()),
                album = "哔哩哔哩",
                coverUrl = pic,
                // duration 是秒
                duration = h.optLong("duration", 0L) * 1000L
            )
        }
        return result.values.toList()
    }

    /** 递归收集带 bvid 的视频条目（结果散落在 __INITIAL_STATE__ 的嵌套结构里） */
    private fun collectVideos(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                if (out.size < 60 && node.has("bvid") && node.has("title") && node.optLong("aid") > 0) {
                    out += node
                }
                val keys = node.keys()
                while (keys.hasNext()) collectVideos(node.opt(keys.next()), out)
            }
            is JSONArray -> {
                var i = 0
                while (i < node.length() && out.size < 60) {
                    collectVideos(node.opt(i), out)
                    i++
                }
            }
        }
    }

    /** 标题里有 <em class="keyword"> 高亮标签和 HTML 实体，剥掉再用 */
    private fun cleanHtml(raw: String): String = raw
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .trim()

    // ───────── 播放直链 ─────────

    /**
     * 取音频直链（DASH 里带宽最高的一条）。
     * CDN 不校验防盗链，播放器默认请求头就能拉流。
     */
    fun playUrl(aid: Long): String? {
        return try {
            val (bvid, cid) = cidCache[aid] ?: run {
                val json = httpGet(
                    "https://api.bilibili.com/x/web-interface/view?aid=$aid",
                    "https://www.bilibili.com/"
                )
                val d = JSONObject(json).optJSONObject("data") ?: return null
                val bv = d.optString("bvid")
                val c = d.optLong("cid", 0L)
                if (bv.isBlank() || c <= 0) return null
                cidCache[aid] = bv to c
                bv to c
            }
            val json = httpGet(
                "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&fnval=16&fourk=1",
                "https://www.bilibili.com/video/$bvid"
            )
            val audios = JSONObject(json).optJSONObject("data")
                ?.optJSONObject("dash")?.optJSONArray("audio") ?: return null
            var best: String? = null
            var bestBandwidth = -1L
            for (i in 0 until audios.length()) {
                val a = audios.optJSONObject(i) ?: continue
                val u = a.optString("baseUrl").ifBlank { a.optString("base_url") }
                val bw = a.optLong("bandwidth", 0L)
                if (u.isNotBlank() && bw > bestBandwidth) {
                    bestBandwidth = bw
                    best = u
                }
            }
            best
        } catch (e: Exception) {
            null
        }
    }
}
