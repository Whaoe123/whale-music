package com.whale.music.data.remote

import com.whale.music.data.model.QsPage
import com.whale.music.data.model.QsPlaylist
import com.whale.music.data.model.QsTrack
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_OK
import java.net.URL

/**
 * 汽水音乐数据源（匿名可用的那一批接口）
 *
 * 实测（2026-11）：
 * - 推荐流   POST https://beta-luna.douyin.com/luna/discover/mix         ✅ 匿名
 * - 歌单详情 POST https://beta-luna.douyin.com/luna/playlist/detail      ✅ 匿名
 * - 单曲信息 GET  /luna/h5/seo_track                                     ✅ 匿名（含 LRC）
 * - PC 搜索  POST api.qishui.com/luna/pc/search/track                    ❌ 需要 X-Helios/X-Medusa 签名
 *
 * 所以这里只做「拿内容」，不做音频解析 —— 音频交给酷我。
 */
object QishuiSource {

    private const val BASE = "https://beta-luna.douyin.com"
    private const val REFERER = "https://music.douyin.com/"
    private const val TIMEOUT = 15_000

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 封面尺寸：列表用 500，播放页用 800 */
    private const val COVER_BASE = "https://p3-luna.douyinpic.com/img/"

    /** 登录 Cookie 提供者，由 UI 层在创建 ViewModel 时挂上；为空则走匿名 */
    var cookieProvider: (() -> String)? = null

    // ───────── HTTP ─────────

    private fun request(
        path: String,
        method: String,
        body: String? = null,
        query: String? = null,
        withCookie: Boolean = false
    ): JSONObject? = try {
        val url = BASE + path + (query?.let { "?$it" } ?: "")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", REFERER)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            // 只有降级重试才带登录 Cookie：实测带着完整抖音 Cookie 请求时，
            // luna 网关可能要求 App 级签名头，反而拿不到内容
            if (withCookie) {
                cookieProvider?.invoke()?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("Cookie", it)
                }
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val text = (if (code == HTTP_OK) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        conn.disconnect()
        // 服务端偶发返回前缀非 JSON 的兜底内容，直接判失败
        text?.trim()?.takeIf { it.startsWith("{") }?.let { JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    // ───────── 推荐电台（汽水打开就推的那个流）─────────

    /** 「为你推荐」里的主题电台（浪漫情歌 / 慵懒流行 / 通勤…） */
    data class QsRadio(val id: String, val title: String, val desc: String)

    /**
     * 拉取首页「为你推荐」电台列表。
     * POST /luna/discover（匿名可用），和 discover/mix 是两个接口。
     */
    fun recommendRadios(count: Int = 10): List<QsRadio> {
        val payload = JSONObject().put("count", count).toString()
        var json = request("/luna/discover", "POST", payload)
        if (json == null) {
            json = request("/luna/discover", "POST", payload, withCookie = true)
        }
        if (json == null) return emptyList()
        val blocks = json.optJSONArray("blocks") ?: return emptyList()
        val out = mutableListOf<QsRadio>()
        for (b in 0 until blocks.length()) {
            val block = blocks.optJSONObject(b) ?: continue
            val inners = block.optJSONArray("inner_block") ?: continue
            for (i in 0 until inners.length()) {
                val inner = inners.optJSONObject(i) ?: continue
                val res = inner.optJSONArray("resources")?.optJSONObject(0) ?: continue
                if (res.optString("type") != "radio") continue
                val id = res.optString("resource_id")
                if (id.isBlank()) continue
                val style = res.optJSONObject("style")
                out += QsRadio(
                    id = id,
                    title = style?.optString("title").orEmpty().ifBlank { inner.optString("title") },
                    desc = style?.optString("desc").orEmpty()
                )
            }
        }
        return out
    }

    /**
     * 电台流曲目（A 路线：登录后带 Cookie 试拉）。
     * 匿名下这些端点都会被签名挡住，所以失败就返回空列表，由调用方降级。
     */
    fun radioTracks(radioId: String, count: Int = 30): List<QsTrack> {
        if (cookieProvider?.invoke().isNullOrBlank()) return emptyList()
        val payloads = listOf(
            JSONObject().put("queue_id", radioId).put("queue_type", "radio").put("count", count),
            JSONObject().put("radio_id", radioId).put("count", count),
            JSONObject().put("playlist_id", radioId).put("count", count)
        )
        for (p in payloads) {
            val json = request("/luna/player/feed", "POST", p.toString(), withCookie = true)
                ?: request("/luna/radio/detail", "POST", p.toString(), withCookie = true)
                ?: continue
            val tracks = parseTracks(json)
            if (tracks.isNotEmpty()) return tracks
        }
        return emptyList()
    }

    /** media_resources → QsTrack（歌单详情与电台流共用这个结构） */
    private fun parseTracks(json: JSONObject): List<QsTrack> {
        val arr = json.optJSONArray("media_resources") ?: return emptyList()
        val tracks = mutableListOf<QsTrack>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("type") != "track") continue
            val t = item.optJSONObject("entity")
                ?.optJSONObject("track_wrapper")
                ?.optJSONObject("track") ?: continue
            val id = t.optString("id")
            if (id.isBlank()) continue
            val album = t.optJSONObject("album")
            tracks += QsTrack(
                id = id,
                title = t.optString("name"),
                artist = artistsOf(t.optJSONArray("artists")),
                album = album?.optString("name").orEmpty(),
                cover = cover(album?.optJSONObject("url_cover"), 500),
                duration = t.optLong("duration", 0L)
            )
        }
        return tracks
    }

    // ───────── 推荐歌单 ─────────

    /**
     * 首页推荐流。返回若干歌单卡片（汽水的 discover/mix 目前只下发歌单，不下发单曲）。
     */
    fun recommend(count: Int = 12): List<QsPlaylist> {
        val payload = JSONObject().put("count", count).toString()
        // 主路径匿名（稳定）；匿名失败才带登录 Cookie 重试（个性化）
        var json = request("/luna/discover/mix", "POST", payload)
        if (json == null) {
            json = request("/luna/discover/mix", "POST", payload, withCookie = true)
        }
        if (json == null) return emptyList()
        return parseDiscover(json)
    }

    /** 解析 discover/mix 响应（登录嗅探拿到的也是这个结构） */
    fun parseDiscover(json: JSONObject): List<QsPlaylist> {
        val blocks = json.optJSONArray("inner_block") ?: return emptyList()
        val out = mutableListOf<QsPlaylist>()
        val seen = mutableSetOf<String>()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            val resources = block.optJSONArray("resources") ?: continue
            for (j in 0 until resources.length()) {
                val pl = resources.optJSONObject(j)
                    ?.optJSONObject("entity")
                    ?.optJSONObject("playlist") ?: continue
                val id = pl.optString("id")
                if (id.isBlank() || !seen.add(id)) continue
                out += QsPlaylist(
                    id = id,
                    title = pl.optString("title"),
                    cover = cover(pl.optJSONObject("url_cover"), 500),
                    trackCount = pl.optInt("count_tracks", 0),
                    owner = pl.optJSONObject("owner")?.optString("nickname").orEmpty(),
                    desc = pl.optString("desc").replace("\uD83C\uDFB5", "").trim()
                )
            }
        }
        return out
    }

    /** 从 WebView 嗅探到的原始文本解析 */
    fun parseDiscoverText(text: String): List<QsPlaylist> = try {
        parseDiscover(JSONObject(text))
    } catch (e: Exception) {
        emptyList()
    }

    // ───────── 歌单曲目 ─────────

    fun playlistTracks(
        playlistId: String,
        cursor: String? = null,
        count: Int = 30
    ): QsPage {
        val payload = JSONObject().apply {
            put("playlist_id", playlistId)
            put("count", count)
            if (!cursor.isNullOrBlank()) put("cursor", cursor)
        }
        val json = request("/luna/playlist/detail", "POST", payload.toString())
            ?: request("/luna/playlist/detail", "POST", payload.toString(), withCookie = true)
            ?: return QsPage()

        val tracks = parseTracks(json)
        return QsPage(
            tracks = tracks,
            nextCursor = json.optString("next_cursor").takeIf { it.isNotBlank() },
            hasMore = json.optBoolean("has_more", false)
        )
    }

    // ───────── 单曲（补元数据 / 歌词用） ─────────

    /** 取单曲详情，返回 (标题, 歌手, 封面, 毫秒时长, LRC 文本) */
    fun trackDetail(trackId: String): JSONObject? =
        request("/luna/h5/seo_track", "GET", query = "track_id=$trackId&device_platform=web")

    // ───────── 工具 ─────────

    private fun artistsOf(arr: JSONArray?): String {
        if (arr == null || arr.length() == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isBlank()) continue
            if (sb.isNotEmpty()) sb.append('/')
            sb.append(name)
        }
        return sb.toString()
    }

    /**
     * 汽水封面地址拼接：urls[0] + uri + "~c5_{size}x{size}.jpg"
     * 实测 500 尺寸约 37KB，800 尺寸可作播放页大图。
     */
    private fun cover(coverObj: JSONObject?, size: Int): String {
        if (coverObj == null) return ""
        val uri = coverObj.optString("uri")
        if (uri.isBlank()) return ""
        val urls = coverObj.optJSONArray("urls")
        val host = if (urls != null && urls.length() > 0 && urls.optString(0).isNotBlank()) {
            urls.optString(0)
        } else {
            COVER_BASE
        }
        return host + uri + "~c5_${size}x${size}.jpg"
    }

    /** 供 UI 把 500 图升级成 800 */
    fun hd(url: String): String =
        if (url.contains("~c5_")) url.replace(Regex("~c5_\\d+x\\d+\\.jpg"), "~c5_800x800.jpg") else url
}
