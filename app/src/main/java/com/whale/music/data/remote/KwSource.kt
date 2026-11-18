package com.whale.music.data.remote

import com.whale.music.data.model.RemoteSong
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 酷我音乐数据源 — 搜索 / 推荐 / 播放直链解析
 *
 * 接口均为酷我客户端/网页公开接口（与米柚音乐同款）：
 * - 搜索:   https://www.kuwo.cn/search/searchMusicBykeyWord （匿名可用）
 * - 推荐:   http://wapi.kuwo.cn/openapi/v1/recommend/tab/top （匿名可用）
 * - 取直链: https://changenotice.kuwo.cn/mobi.s?type=convert_url_with_sign （匿名可用，320kmp3/2000kflac）
 */
object KwSource {

    private const val TIMEOUT = 12_000
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val COVER_BASE = "https://img2.kuwo.cn/star/albumcover/"

    /** 默认音质 320kmp3；无损 2000kflac 供下载大文件时选用 */
    const val BR_320 = "320kmp3"
    const val BR_FLAC = "2000kflac"

    private fun httpGet(url: String, referer: String? = null): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("User-Agent", UA)
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

    fun toRemoteSong(source: String, rid: Long, title: String, artist: String, album: String, coverUrl: String, durationMs: Long) =
        RemoteSong(
            songId = "${source}_$rid",
            source = source,
            rid = rid,
            title = title,
            artist = artist,
            album = album,
            coverUrl = coverUrl,
            duration = durationMs
        )

    // ───────── 搜索 ─────────

    fun search(keyword: String, page: Int = 1, pageSize: Int = 30): List<RemoteSong> {
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://www.kuwo.cn/search/searchMusicBykeyWord?vipver=1&client=kt&ft=music" +
            "&cluster=0&strategy=2012&encoding=utf8&rformat=json&mobi=1&issubtitle=1" +
            "&show_copyright_off=1&pn=${page - 1}&rn=$pageSize&all=$enc"
        val json = httpGet(url, "https://www.kuwo.cn/")
        val list = JSONObject(json).optJSONArray("abslist") ?: return emptyList()
        val result = mutableListOf<RemoteSong>()
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val rid = s.optString("MUSICRID").removePrefix("MUSIC_").toLongOrNull() ?: continue
            // 缩略图路径（120/...）统一升级成 800，否则播放页放大后发糊
            val cover = com.whale.music.util.CoverUrl.hd(
                COVER_BASE + s.optString("web_albumpic_short")
            )
            result += toRemoteSong(
                "kw", rid,
                s.optString("SONGNAME"), s.optString("ARTIST"), s.optString("ALBUM", ""),
                cover, (s.optString("DURATION", "0").toDoubleOrNull() ?: 0.0).times(1000).toLong()
            )
        }
        return result
    }

    // ───────── 推荐 ─────────

    fun recommend(num: Int = 30): List<RemoteSong> {
        val url = "http://wapi.kuwo.cn/openapi/v1/recommend/tab/top?offset=10&num=$num&default_discover=0" +
            "&source=kwplayer_ar_11.1.8.2_newpcguanwangmobile.apk&loginUid=0&loginSid=0" +
            "&prod=kwplayer_ar_11.1.8.2&platform=ar&corp=kuwo&approval=false&vipver=11.1.8.2&newver=3&allpay=0&notrace=0"
        val json = httpGet(url)
        val list = JSONObject(json).optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val result = mutableListOf<RemoteSong>()
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val rid = s.optString("rid").toLongOrNull() ?: continue
            val img = s.optString("img")
            val cover = com.whale.music.util.CoverUrl.hd(
                if (img.startsWith("http")) img else COVER_BASE + img
            )
            result += toRemoteSong(
                "kw", rid,
                s.optString("name"), s.optString("artist"), s.optString("album"),
                cover, s.optString("duration", "0").toDoubleOrNull()?.times(1000)?.toLong() ?: 0L
            )
        }
        return result
    }

    // ───────── 播放直链 ─────────

    /** 取播放直链；br 见 BR_320 / BR_FLAC。返回 null 表示拿不到 */
    fun playUrl(rid: Long, br: String = BR_320): String? {
        return try {
            val url = "https://changenotice.kuwo.cn/mobi.s?f=web&user=0" +
                "&source=kwplayercar_ar_6.0.0.9_B_jiakong_vh.apk&type=convert_url_with_sign" +
                "&br=$br&sig=0&rid=$rid&mode=prefetch&from=PC&surl=1&apiv=1"
            val data = JSONObject(httpGet(url)).optJSONObject("data") ?: return null
            // 正文里的 & 可能转义成 &amp;
            val surl = data.optString("surl")
                .replace("&amp;", "&")
            if (surl.startsWith("http") && !surl.contains("\"None\"")) surl else null
        } catch (e: Exception) {
            null
        }
    }

    /** 按「歌名 + 歌手」在酷我重新搜索，返回第一条（米柚的跨源解析套路） */
    fun convertByName(title: String, artist: String): RemoteSong? {
        return try {
            search("$title $artist", pageSize = 5).firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
