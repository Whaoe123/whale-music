package com.whale.music.data.remote

import com.whale.music.data.model.QsTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 歌单链接导入：把其他 App 的分享链接/文本解析成可播放的曲目列表。
 *
 * 实测（2026-11）匿名可用的源：
 * - 网易云歌单 / 单曲：music.163.com（v6 detail / song detail）
 * - QQ 音乐歌单：y.qq.com（fcg_ucc_getcdinfo_byids_cp，按 disstid）
 *
 * 网易云坑点：/api/v6/playlist/detail 匿名请求时 tracks 只返回前 6~10 首，
 * trackIds 才是全量。带上基础 Cookie（os=pc; appver）可直接拿全量 tracks；
 * 若仍不全，再用 trackIds 分批 song/detail 补齐，保证「整张导入」。
 *
 * 酷我歌单接口要 csrf token，暂不支持。
 * 解析出来的曲目交给酷我出声（复用汽水那条打分匹配链路）。
 */
object LinkImporter {

    data class Imported(val name: String, val source: String, val tracks: List<QsTrack>)

    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 基础匿名 Cookie：网易云靠它判断为 PC 网页端，才会返回全量 tracks */
    private const val NCM_BASE_COOKIE = "os=pc; appver=8.9.70; osver=Microsoft-Windows-10"

    private const val TIMEOUT = 15_000

    suspend fun import(raw: String): Imported = withContext(Dispatchers.IO) {
        val url = extractUrl(raw)
            ?: throw IllegalArgumentException("没找到链接，把分享文案整段粘进来也可以")
        val target = resolveShortLink(url)
        when {
            target.contains("music.163.com") -> importNcm(target)
            target.contains("y.qq.com") -> importQq(target)
            else -> throw IllegalArgumentException("暂不支持这个链接，目前支持网易云 / QQ 音乐歌单")
        }
    }

    // ───────── 链接处理 ─────────

    private fun extractUrl(text: String): String? {
        val regex = Regex("https?://[^\\s，。、,;；\"'）)】]+")
        return regex.find(text)?.value
    }

    /** 短链（163cn.tv / c6.y.qq.com / url.cn）跟一次跳转，拿最终地址 */
    private fun resolveShortLink(url: String): String {
        val needsFollow = listOf("163cn.tv", "url.cn", "c6.y.qq.com", "c.y.qq.com/r").any {
            url.contains(it)
        }
        if (!needsFollow) return url
        return try {
            val conn = (URL(if (url.startsWith("http")) url else "https://$url").openConnection()
                as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("User-Agent", UA)
                requestMethod = "GET"
            }
            val finalUrl = conn.url.toString()
            conn.disconnect()
            finalUrl
        } catch (e: Exception) {
            url
        }
    }

    private fun param(url: String, name: String): String? {
        return runCatching {
            val decoded = URLDecoder.decode(url, "UTF-8")
            Regex("[?&#]$name=(\\d+)").find(decoded)?.groupValues?.get(1)
        }.getOrNull()
    }

    /** cookie 为空时不带 Cookie；非空时附带（用于网易云拿全量 tracks） */
    private fun getJson(url: String, referer: String, cookie: String? = null): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) throw IllegalStateException("链接内容为空（HTTP $code）")
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    // ───────── 网易云 ─────────

    private fun importNcm(url: String): Imported {
        val id = param(url, "id") ?: throw IllegalArgumentException("网易云链接里没解析到歌单/歌曲 ID")
        val referer = "https://music.163.com/"
        return if (url.contains("/playlist") || url.contains("#/playlist")) {
            importNcmPlaylist(id, referer)
        } else {
            val json = getJson(
                "https://music.163.com/api/v3/song/detail?ids=%5B$id%5D",
                referer,
                NCM_BASE_COOKIE
            )
            val songs = json.optJSONArray("songs") ?: throw IllegalStateException("歌曲不存在")
            val t = songs.optJSONObject(0) ?: throw IllegalStateException("歌曲不存在")
            Imported("网易云单曲", "网易云", listOf(ncmTrack(t)))
        }
    }

    /**
     * 整张歌单导入：
     * 1) 先带基础 Cookie 请求 v6 detail，正常会直接返回全量 tracks；
     * 2) 若 tracks 少于 trackIds（匿名/风控被截断），用 trackIds 分批 song/detail 补齐；
     * 3) 最终按 trackIds 顺序输出，保证与网易云里看到的顺序一致。
     */
    private fun importNcmPlaylist(id: String, referer: String): Imported {
        var json = getJson(
            "https://music.163.com/api/v6/playlist/detail?id=$id&n=1000",
            referer,
            NCM_BASE_COOKIE
        )
        var pl = json.optJSONObject("playlist")
        // -462 风控：退一档重试一次（不带 n=1000，改回默认）
        if (pl == null || json.optInt("code", 200) != 200) {
            json = getJson(
                "https://music.163.com/api/v6/playlist/detail?id=$id",
                referer,
                NCM_BASE_COOKIE
            )
            pl = json.optJSONObject("playlist")
        }
        if (pl == null) throw IllegalStateException("歌单不存在或已删除（或触发了风控，稍后重试）")

        val name = pl.optString("name").ifBlank { "网易云歌单" }
        val idsArr = pl.optJSONArray("trackIds") ?: JSONArray()
        val tracksArr = pl.optJSONArray("tracks") ?: JSONArray()

        // 先收集 tracks 里已有的详情（字段最全）
        val byId = LinkedHashMap<Long, QsTrack>()
        for (i in 0 until tracksArr.length()) {
            val t = tracksArr.optJSONObject(i) ?: continue
            val tid = t.optLong("id")
            if (tid > 0) byId[tid] = ncmTrack(t)
        }

        // 收集全量 id（trackIds 优先，缺失时退回 tracks 自身的 id）
        val orderedIds = mutableListOf<Long>()
        for (i in 0 until idsArr.length()) {
            val tid = idsArr.optJSONObject(i)?.optLong("id") ?: continue
            if (tid > 0) orderedIds += tid
        }
        if (orderedIds.isEmpty()) {
            for (i in 0 until tracksArr.length()) {
                val tid = tracksArr.optJSONObject(i)?.optLong("id") ?: continue
                if (tid > 0) orderedIds += tid
            }
        }

        // 缺哪些补哪些：分批 song/detail，每批 100，失败不拖垮整张
        val missing = orderedIds.filter { !byId.containsKey(it) }
        if (missing.isNotEmpty()) {
            missing.chunked(100).forEach { batch ->
                val idsJson = batch.joinToString(",", "[", "]")
                val encoded = URLEncoder.encode(idsJson, "UTF-8")
                runCatching {
                    val detail = getJson(
                        "https://music.163.com/api/song/detail?ids=$encoded",
                        referer,
                        NCM_BASE_COOKIE
                    )
                    val songs = detail.optJSONArray("songs") ?: return@runCatching
                    for (k in 0 until songs.length()) {
                        val t = songs.optJSONObject(k) ?: continue
                        val tid = t.optLong("id")
                        if (tid > 0) byId[tid] = ncmTrack(t)
                    }
                }
            }
        }

        // 按 trackIds 顺序输出；补齐失败的最后兜底为已有详情顺序
        val tracks = orderedIds.mapNotNull { byId[it] }.ifEmpty { byId.values.toList() }
        if (tracks.isEmpty()) throw IllegalStateException("这个歌单里没解析到歌曲（可能是私密歌单）")
        return Imported(name, "网易云", tracks)
    }

    private fun ncmTrack(t: JSONObject): QsTrack {
        val ar = t.optJSONArray("ar") ?: t.optJSONArray("artists")
        val artists = mutableListOf<String>()
        if (ar != null) {
            for (i in 0 until ar.length()) {
                ar.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(artists::add)
            }
        }
        val al = t.optJSONObject("al") ?: t.optJSONObject("album")
        val cover = al?.optString("picUrl").orEmpty()
        val duration = t.optLong("dt", 0L).let {
            if (it > 0) it else t.optLong("duration", 0L)
        }
        return QsTrack(
            id = "ncm_" + t.optLong("id"),
            title = t.optString("name"),
            artist = artists.joinToString(" / "),
            album = al?.optString("name").orEmpty(),
            cover = if (cover.isBlank()) "" else "$cover?param=500y500",
            duration = duration
        )
    }

    // ───────── QQ 音乐 ─────────

    private fun importQq(url: String): Imported {
        val disstid = param(url, "disstid")
            ?: param(url, "id")
            ?: Regex("/playlist/(\\d+)").find(url)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("QQ 音乐链接里没解析到歌单 ID")
        val api = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
            "?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=$disstid&format=json"
        val json = getJson(api, "https://y.qq.com/")
        val cd = json.optJSONArray("cdlist")?.optJSONObject(0)
            ?: throw IllegalStateException("歌单不存在或需要登录")
        val songlist = cd.optJSONArray("songlist") ?: throw IllegalStateException("歌单里没解析到歌曲")
        val tracks = mutableListOf<QsTrack>()
        for (i in 0 until songlist.length()) {
            val s = songlist.optJSONObject(i) ?: continue
            val title = s.optString("songname").ifBlank { s.optString("title") }
            if (title.isBlank()) continue
            val singers = mutableListOf<String>()
            s.optJSONArray("singer")?.let { arr ->
                for (k in 0 until arr.length()) {
                    arr.optJSONObject(k)?.optString("name")?.takeIf { it.isNotBlank() }?.let(singers::add)
                }
            }
            val albumMid = s.optString("albummid").ifBlank { s.optJSONObject("album")?.optString("mid").orEmpty() }
            val cover = if (albumMid.isBlank()) "" else
                "https://y.qq.com/music/photo_new/T002R300x300M000$albumMid.jpg"
            val songMid = s.optString("songmid").ifBlank { s.optString("mid") }
            tracks += QsTrack(
                id = "qq_" + (songMid.ifBlank { "$i" }),
                title = title,
                artist = singers.joinToString(" / "),
                album = s.optJSONObject("album")?.optString("name")
                    .takeUnless { it.isNullOrBlank() } ?: s.optString("albumname"),
                cover = cover,
                duration = s.optLong("interval", 0L) * 1000L
            )
        }
        if (tracks.isEmpty()) throw IllegalStateException("歌单里没解析到歌曲")
        return Imported(
            cd.optString("dissname").ifBlank { "QQ 音乐歌单" },
            "QQ音乐",
            tracks
        )
    }
}
