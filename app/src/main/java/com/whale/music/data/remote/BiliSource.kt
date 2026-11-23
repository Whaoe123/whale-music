package com.whale.music.data.remote

import com.whale.music.data.model.RemoteSong
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 哔哩哔哩数据源 —— 只做最后一级兜底：
 * 网易 / 酷我 / lx 全都拿不到的歌（没版权、冷门下架），按「歌名 + 歌手」
 * 在 B 站搜视频，取视频的音频流来播。B 站结果多是翻唱 / 现场，
 * 质量参差，所以顺序上永远排在正式音源之后。
 *
 * 接口均为公开接口（不登录可用）：
 * - 搜索: https://api.bilibili.com/x/web-interface/wbi/search/type （WBI 签名，返回 20 条 JSON）
 * - 详情: https://api.bilibili.com/x/web-interface/view?aid= （补 cid）
 * - 取流: https://api.bilibili.com/x/player/playurl?fnval=16 （DASH 音频轨）
 *
 * ## 为什么不再抓搜索网页
 *
 * search.bilibili.com 已经换成 Vite + Pinia 的 SPA：桌面版页面里
 * `window.__INITIAL_STATE__` 出现 **0 次**，数据变成压缩过的 JS 对象字面量
 * （变量名被压成 `aB`、`f` 这种），JSON 解析器根本吃不下。
 * 手机 UA 的页面目前还留着 `__INITIAL_STATE__`，但那只是没切完 ——
 * 一旦切完，B 站音源会整条静默失效（搜索恒返回空，没有异常、没有日志）。
 *
 * 所以搜索改走官方接口 + WBI 签名：结构稳定、有明确的 `code`/`message`，
 * 失败能看出来，也能重试。实测不带 cookie 就能拿到 20 条。
 *
 * ## 取流的最后一公里
 *
 * `*.bilivideo.com/.cn` 的 CDN 会校验请求头 —— 手机 UA / 缺 Referer 会 403，
 * 需要「桌面 Chrome UA + Referer: https://www.bilibili.com/」。
 * 音频流不是由这里拉，而是由 MusicPlayerService 的 BiliAwareHttpFactory 注入头后播放。
 */
object BiliSource {

    private const val TIMEOUT = 12_000
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val REFERER = "https://www.bilibili.com/"

    /** 查询词级缓存（含搜不到的负缓存），批量兜底时不反复打空查询 */
    private val searchCache = com.whale.music.util.TtlCache<List<RemoteSong>>(maxSize = 128)
    private const val SEARCH_HIT_TTL_MS = 10 * 60_000L
    private const val SEARCH_MISS_TTL_MS = 30 * 60_000L

    /** aid → (bvid, cid)：搜索结果自带，view 接口补查的也记下，取流不再多打一枪 */
    private val cidCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, Long>>()

    // ───────── WBI 签名 ─────────

    /**
     * WBI 混淆表（B 站前端常量）：把 `img_key + sub_key`（各 32 位，共 64 字符）
     * 按这张表重排，取前 32 位就是签名用的 mixin key。
     */
    private val MIXIN_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    @Volatile private var wbiImg = ""
    @Volatile private var wbiSub = ""
    @Volatile private var wbiAt = 0L
    private const val WBI_TTL_MS = 6 * 3600_000L

    /** 风控时补的 buvid3/buvid4；正常路径用不上 */
    @Volatile private var cookie = ""

    /** 签名串里必须剔掉的字符（B 站前端同款） */
    private const val WBI_STRIP = "!'()*"

    fun clearCaches() {
        searchCache.clear()
        cidCache.clear()
    }

    private fun httpGet(url: String, referer: String? = REFERER): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Encoding", "identity")
            if (referer != null) setRequestProperty("Referer", referer)
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
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
        var root = callSearch(keyword)
        if (root == null || root.optInt("code", -1) != 0) {
            // 密钥过期 / 风控（-352 签名错、-412 被挡）：换一套 cookie 和密钥重签一次
            resetAuth()
            root = callSearch(keyword)
        }
        val arr = root?.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()

        val result = LinkedHashMap<Long, RemoteSong>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val aid = o.optLong("aid", 0L)
            val title = cleanHtml(o.optString("title"))
            if (aid <= 0 || title.isBlank()) continue
            if (result.containsKey(aid)) continue
            var pic = o.optString("pic")
            if (pic.startsWith("//")) pic = "https:$pic"
            result[aid] = RemoteSong(
                songId = "bili_$aid",
                source = "bili",
                rid = aid,
                title = title,
                artist = o.optString("author"),
                album = "哔哩哔哩",
                coverUrl = pic,
                duration = parseDurationMs(o.opt("duration"))
            )
        }
        return result.values.toList()
    }

    /** 打一次搜索接口，返回顶层 JSON（含 code）；任何传输/签名失败都返回 null */
    private fun callSearch(keyword: String): JSONObject? {
        val query = wbiQuery(
            linkedMapOf(
                "search_type" to "video",
                "keyword" to keyword,
                "page" to "1"
            )
        ) ?: return null
        return try {
            val body = httpGet("https://api.bilibili.com/x/web-interface/wbi/search/type?$query")
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    /** 密钥与 cookie 全部作废，下次调用重新取 */
    private fun resetAuth() {
        wbiImg = ""
        wbiSub = ""
        wbiAt = 0L
        cookie = ""
        ensureCookie()
    }

    /**
     * 取/刷新 WBI 密钥。nav 接口没登录时 `data` 里没有用户信息，
     * 但 `wbi_img` 照样给 —— 这就是免登录也能签名的基础。
     */
    private fun ensureWbi(): Boolean {
        val now = System.currentTimeMillis()
        if (wbiImg.isNotEmpty() && wbiSub.isNotEmpty() && now - wbiAt < WBI_TTL_MS) return true
        return try {
            val json = JSONObject(httpGet("https://api.bilibili.com/x/web-interface/nav"))
            val w = json.optJSONObject("data")?.optJSONObject("wbi_img") ?: return false
            val img = w.optString("img_url").substringAfterLast('/').substringBefore('.')
            val sub = w.optString("sub_url").substringAfterLast('/').substringBefore('.')
            if (img.isEmpty() || sub.isEmpty()) return false
            wbiImg = img
            wbiSub = sub
            wbiAt = now
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 补一个游客 cookie（buvid3/buvid4）；拿不到也不影响后续重试 */
    private fun ensureCookie() {
        if (cookie.isNotEmpty()) return
        try {
            val d = JSONObject(httpGet("https://api.bilibili.com/x/frontend/finger/spi"))
                .optJSONObject("data") ?: return
            val b3 = d.optString("b_3")
            val b4 = d.optString("b_4")
            if (b3.isNotBlank()) cookie = "buvid3=$b3; buvid4=$b4"
        } catch (e: Exception) {
            // 忽略：没有 cookie 时多数情况仍能搜
        }
    }

    private fun wbiQuery(params: Map<String, String>): String? {
        if (!ensureWbi()) return null
        return signWbi(params, wbiImg, wbiSub, System.currentTimeMillis() / 1000)
    }

    /** `wts` + `w_rid` 签名；键排序、值删 `!'()*`、md5(query + mixinKey) */
    internal fun signWbi(params: Map<String, String>, imgKey: String, subKey: String, wts: Long): String {
        val mixin = wbiMixinKey(imgKey, subKey)
        val all = LinkedHashMap(params)
        all["wts"] = wts.toString()
        val query = all.keys.sorted().joinToString("&") { k ->
            wbiEncode(k) + "=" + wbiEncode(all[k].orEmpty().filterNot { it in WBI_STRIP })
        }
        return "$query&w_rid=" + md5(query + mixin)
    }

    internal fun wbiMixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        val sb = StringBuilder(32)
        for (i in 0 until 32) {
            val idx = MIXIN_TAB[i]
            sb.append(if (idx < raw.length) raw[idx] else '0')
        }
        return sb.toString()
    }

    /** 按 JS `encodeURIComponent` 的规则转义（`%20` 而不是 `+`），签名才能和网页一致 */
    internal fun wbiEncode(raw: String): String {
        val sb = StringBuilder(raw.length * 3)
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '-' || c == '_' || c == '.' || c == '~'
            ) {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0x0F])
            }
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"

    private fun md5(raw: String): String =
        MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 时长：接口给的是 `"4:45"` / `"1:02:03"`，老接口也可能给秒数（数字或字符串）。
     * 统一成毫秒 —— 时长是 B 站匹配里权重最高的判据之一，解析错了整首都会选错。
     */
    internal fun parseDurationMs(raw: Any?): Long {
        // JSON 的 null 会以 JSONObject.NULL 传进来，它的 toString() 是 "null"，
        // 后面解析不出数字自然归 0；这里不直接引用 JSONObject，纯函数才好单测
        if (raw == null) return 0L
        val s = if (raw is Number) return raw.toLong().coerceAtLeast(0L) * 1000L
        else raw.toString().trim()
        if (s.isEmpty()) return 0L
        if (!s.contains(':')) return (s.toLongOrNull() ?: 0L).coerceAtLeast(0L) * 1000L
        var total = 0L
        for (part in s.split(':')) {
            total = total * 60 + (part.trim().toLongOrNull() ?: 0L)
        }
        return total.coerceAtLeast(0L) * 1000L
    }

    /** 标题里有 `<em class="keyword">` 高亮标签和 HTML 实体，剥掉再用 */
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
     *
     * 只负责拿到 URL；真正拉流时 CDN 会查 UA / Referer（手机 UA 或没 Referer 直接 403），
     * 请求头由播放器的 BiliAwareHttpFactory 按域名补上。
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