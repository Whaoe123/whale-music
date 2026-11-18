package com.whale.music.ncm

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 网易云网页版接口（全部经实测可用，plain HTTPS，无加密）：
 *  - POST /api/login/qrcode/unikey          扫码 unikey
 *  - POST /api/login/qrcode/client/login    轮询扫码状态 800/801/802/803
 *  - GET  /api/nuser/account/get            登录态
 *  - GET  /api/user/playlist                用户歌单（找红心歌单）
 *  - GET  /api/v6/playlist/detail           歌单曲目 id
 *  - GET  /api/song/detail                  批量歌曲详情
 *  - GET  /api/song/enhance/player/url      播放链接
 *  - GET  /api/search/get/web               搜索
 *  - POST /api/like                         红心回写
 */
object NcmHttp {

    private const val TIMEOUT = 12_000

    fun request(
        url: String,
        cookie: String,
        method: String = "GET",
        form: String? = null,
        session: NcmSession? = null
    ): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            // 关键：不跟随重定向，否则 302 那一跳的 Set-Cookie（MUSIC_U 登录态）会被吞掉
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", NCM_UA)
            setRequestProperty("Referer", "$BASE/")
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
            if (form != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        if (form != null) {
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        session?.absorb(conn)
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        } ?: ""
        conn.disconnect()
        android.util.Log.d(
            "NcmHttp",
            "HTTP $code ${method} ${url.substringAfter("music.163.com")} " +
                "cookie=${cookie.count { it == '=' }}个 body=${body.take(100)}"
        )
        if (body.isEmpty()) return JSONObject().put("code", code)
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            // 非 JSON（如重定向页）→ 用 HTTP 状态码占位，交由上层按「未知响应」处理
            JSONObject().put("code", code)
        }
    }

    fun get(url: String, cookie: String, session: NcmSession? = null): JSONObject =
        request(url, cookie, "GET", null, session)

    fun post(url: String, cookie: String, form: String, session: NcmSession? = null): JSONObject =
        request(url, cookie, "POST", form, session)
}

object NcmApi {

    /** 最近一次扫码状态响应的原始信息（诊断行显示用） */
    @Volatile
    var lastCheckInfo: String = ""

    // ───────── 扫码登录 ─────────

    /** 取码前预热：访问一次首页拿到 NMTID 等完整 Cookie 组，显著降低后续 -462 风控概率 */
    suspend fun warmup(session: NcmSession) {
        if (session.cookieNames().contains("NMTID")) return
        try {
            NcmHttp.get("$BASE/", session.cookieHeader(), session)
        } catch (_: Exception) {
            // 预热失败不阻断登录流程
        }
    }

    /** 获取扫码 unikey */
    suspend fun unikey(session: NcmSession): String {
        val json = NcmHttp.post("$BASE/api/login/qrcode/unikey", session.cookieHeader(), "type=3", session)
        return json.optString("unikey")
    }

    /** 轮询扫码状态：800 过期 / 801 等待 / 802 已扫待确认 / 803 成功 / -462 风控；未知响应返回 null（保持原状态） */
    suspend fun qrCheck(session: NcmSession, key: String): QrStatus? {
        val json = NcmHttp.post(
            "$BASE/api/login/qrcode/client/login",
            session.cookieHeader(),
            "key=${URLEncoder.encode(key, "UTF-8")}&type=3",
            session
        )
        // 803 时正文可能携带 cookie 字段
        if (json.optInt("code", Int.MIN_VALUE) == 803) session.absorbCookieString(json.optString("cookie"))
        val raw = json.optInt("code", Int.MIN_VALUE)
        lastCheckInfo = "raw=$raw ${json.optString("message").take(16)}"
        return when (raw) {
            803 -> QrStatus.SUCCESS
            802 -> QrStatus.SCANNED
            800 -> QrStatus.EXPIRED
            801 -> QrStatus.WAITING
            -462 -> QrStatus.RISK_CONTROLLED
            8821 -> QrStatus.BLOCKED
            else -> null
        }
    }

    // ───────── 推荐 ─────────

    /**
     * 每日推荐音乐（v3 discovery）。匿名返回通用推荐，登录后为个人口味推荐。
     * 字段为新版结构（ar/al/dt/fee），parseSong 直接解析。
     */
    suspend fun recommendSongs(session: NcmSession, limit: Int = 50): List<NcmSongDto> {
        val json = NcmHttp.get(
            "$BASE/api/v3/discovery/recommend/songs?limit=$limit",
            session.cookieHeader()
        )
        val arr = json.optJSONObject("data")?.optJSONArray("dailySongs") ?: return emptyList()
        val result = mutableListOf<NcmSongDto>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { parseSong(it) }?.let { result.add(it) }
        }
        return result
    }

    // ───────── 账号与歌单 ─────────

    /**
     * 公开歌单/榜单的完整曲目 —— 匿名可用，不需要登录。
     *
     * 榜单 id：热歌榜 3778678 / 飙升榜 19723756 / 新歌榜 3779629 / 原创榜 2884035。
     * 偶发 -462（加密验证风控）时重试两次，实测基本都能过。
     */
    suspend fun publicPlaylistSongs(playlistId: Long, limit: Int = 1000): List<NcmSongDto> {
        repeat(3) { attempt ->
            val json = runCatching {
                NcmHttp.get("$BASE/api/v6/playlist/detail?id=$playlistId&n=$limit", "")
            }.getOrNull() ?: return@repeat
            if (json.optInt("code") == 200) {
                val arr = json.optJSONObject("playlist")?.optJSONArray("tracks")
                    ?: return emptyList()
                val result = mutableListOf<NcmSongDto>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { parseSong(it) }?.let { result.add(it) }
                }
                if (result.isNotEmpty()) return result
            }
            // 被风控：稍微错开再试
            delay(600L * (attempt + 1))
        }
        return emptyList()
    }

    /**
     * Fetch every song of the user's liked playlist, in playlist order.
     * trackIds is always complete even when tracks is truncated; missing
     * details are filled in batches via song/detail (with retries), and a
     * v3 full-track endpoint is used as a fallback so nothing is dropped
     * silently when the first response is truncated or risk-controlled.
     */
    suspend fun likedSongs(session: NcmSession, uid: Long): List<NcmSongDto> =
        likedSongsDetailed(session, uid).songs

    /** 红心歌单抓取结果：requested = 应有曲数，songs = 实际拿到的详情 */
    data class LikedFetch(
        val songs: List<NcmSongDto>,
        val requested: Int,
        val trackIdsCount: Int = 0,
        val tracksCount: Int = 0,
        val filled: Int = 0,
        val attempts: Int = 1
    ) {
        /** 接口没给到详情的曲数（版权/风控等），用于提示用户，不静默吞掉 */
        val missing: Int get() = (requested - songs.size).coerceAtLeast(0)

        /** 给用户看的诊断行：清单多少 / 返回多少 / 补齐多少 / 最终多少 */
        fun diagnostics(): String =
            "清单 $trackIdsCount · 返回 $tracksCount · 补齐 $filled · 最终 ${songs.size}（请求 $attempts 次）"
    }

    suspend fun likedSongsDetailed(session: NcmSession, uid: Long): LikedFetch {
        val playlistId = likedPlaylistId(session, uid) ?: return LikedFetch(emptyList(), 0)

        val byId = LinkedHashMap<Long, NcmSongDto>()
        val orderedIds = LinkedHashSet<Long>()
        var lastSize = -1
        var attempts = 0

        // v6 可能被风控随机截断：连打几次取并集，直到不再有新 id
        for (attempt in 0 until 3) {
            attempts = attempt + 1
            runCatching {
                val json = NcmHttp.get(
                    "$BASE/api/v6/playlist/detail?id=$playlistId&n=1000",
                    session.cookieHeader()
                )
                val pl = json.optJSONObject("playlist") ?: return@runCatching
                pl.optJSONArray("tracks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { parseSong(it) }?.let { byId[it.id] = it }
                    }
                }
                pl.optJSONArray("trackIds")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optLong("id") ?: continue
                        if (id > 0) orderedIds.add(id)
                    }
                }
            }
            if (orderedIds.size == lastSize) break // 没有新 id 了，说明这次和上次一样全
            lastSize = orderedIds.size
            if (attempt < 2) delay(700L)
        }

        if (orderedIds.isEmpty()) {
            orderedIds.addAll(byId.keys)
        }
        if (orderedIds.isEmpty()) return LikedFetch(emptyList(), 0, attempts = attempts)

        // 补齐缺失详情（v6 tracks 被截断的那部分）
        val beforeFill = byId.size
        fillDetails(session, orderedIds, byId)
        val filled = byId.size - beforeFill

        // 还缺 → 再打一次 v6（可能上一次被截断）+ 再补一次详情
        if (orderedIds.any { !byId.containsKey(it) }) {
            runCatching {
                val json = NcmHttp.get(
                    "$BASE/api/v6/playlist/detail?id=$playlistId&n=1000",
                    session.cookieHeader()
                )
                val pl = json.optJSONObject("playlist") ?: return@runCatching
                pl.optJSONArray("tracks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { parseSong(it) }?.let { byId[it.id] = it }
                    }
                }
                pl.optJSONArray("trackIds")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optLong("id") ?: continue
                        if (id > 0) orderedIds.add(id)
                    }
                }
            }
            attempts++
            fillDetails(session, orderedIds, byId)
        }

        val songs = orderedIds.mapNotNull { byId[it] }.ifEmpty { byId.values.toList() }
        return LikedFetch(
            songs = songs,
            requested = orderedIds.size.coerceAtLeast(songs.size),
            trackIdsCount = orderedIds.size,
            tracksCount = byId.size,
            filled = filled,
            attempts = attempts
        )
    }

    /** 把 orderedIds 里还没有详情的批量补上 */
    private suspend fun fillDetails(
        session: NcmSession,
        orderedIds: List<Long>,
        byId: MutableMap<Long, NcmSongDto>
    ) {
        val missing = orderedIds.filter { !byId.containsKey(it) }
        if (missing.isEmpty()) return
        songDetails(session, missing).forEach { byId[it.id] = it }
    }

    /** 红心歌单 id（specialType=1 或名为「我喜欢的音乐」） */
    suspend fun likedPlaylistId(session: NcmSession, uid: Long): Long? {
        val json = NcmHttp.get("$BASE/api/user/playlist?uid=$uid&limit=100", session.cookieHeader())
        val list = json.optJSONArray("playlist") ?: return null
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            if (p.optInt("specialType") == 1 || p.optString("name") == "我喜欢的音乐") {
                return p.optLong("id")
            }
        }
        return null
    }

    /** Full track ids of a playlist. s=8 triggers -462 risk control, so use plain n=1000. */
    suspend fun playlistTrackIds(session: NcmSession, playlistId: Long): List<Long> {
        val ids = mutableListOf<Long>()
        repeat(2) { attempt ->
            val json = runCatching {
                NcmHttp.get(
                    "$BASE/api/v6/playlist/detail?id=$playlistId&n=1000",
                    session.cookieHeader()
                )
            }.getOrNull() ?: return@repeat
            val pl = json.optJSONObject("playlist") ?: return@repeat
            pl.optJSONArray("trackIds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optLong("id") ?: continue
                    if (id > 0) ids += id
                }
            }
            if (ids.isEmpty()) {
                pl.optJSONArray("tracklist")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optLong("id") ?: continue
                        if (id > 0) ids += id
                    }
                }
            }
            if (ids.isNotEmpty()) return ids
            delay(500L * (attempt + 1))
        }
        return ids
    }

    /** Batch song details (100 per request; larger batches get throttled).
     *  响应必须校验 code + songs 数量，失败退避重试，避免风控时静默丢歌。 */
    suspend fun songDetails(session: NcmSession, ids: List<Long>): List<NcmSongDto> {
        if (ids.isEmpty()) return emptyList()
        val result = mutableListOf<NcmSongDto>()
        ids.chunked(100).forEach { batch ->
            var done = false
            repeat(3) { attempt ->
                if (done) return@repeat
                val json = runCatching {
                    NcmHttp.get(
                        "$BASE/api/song/detail?ids=" + URLEncoder.encode(JSONArray(batch).toString(), "UTF-8"),
                        session.cookieHeader()
                    )
                }.getOrNull()
                val songs = json?.optJSONArray("songs")
                if (json != null && json.optInt("code") == 200 && songs != null && songs.length() > 0) {
                    for (i in 0 until songs.length()) {
                        songs.optJSONObject(i)?.let { parseSong(it) }?.let { result.add(it) }
                    }
                    done = true
                } else {
                    delay(700L * (attempt + 1)) // 被风控 / 空响应：错开重试
                }
            }
            if (!done) {
                android.util.Log.w("NcmApi", "song/detail 补齐失败：${batch.size} 首取不到详情")
            }
        }
        return result
    }

    // ───────── 播放链接 ─────────

    /**
     * 批量取播放链接，返回 id → https 地址。
     * 取不到的歌（版权/接口不发链接）不出现在结果里，由调用方决定提示方式。
     */
    suspend fun playUrls(session: NcmSession, ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, String>()
        ids.chunked(500).forEach { batch ->
            val json = NcmHttp.get(
                "$BASE/api/song/enhance/player/url?ids=" +
                    URLEncoder.encode(JSONArray(batch).toString(), "UTF-8") + "&br=320000",
                session.cookieHeader()
            )
            val data = json.optJSONArray("data") ?: return@forEach
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.isEmpty()) continue
                // VIP 歌匿名态会返回 30 秒试听片段（freeTrialInfo 非空）。
                // 试听没用，跳过让上层按「歌名+歌手」转酷我拿完整版。
                val trial = item.optJSONObject("freeTrialInfo")
                if (trial != null && trial.length() > 0) continue
                result[item.optLong("id")] = url.replaceFirst("http://", "https://")
            }
        }
        return result
    }

    // ───────── 搜索 ─────────

    suspend fun search(session: NcmSession, keyword: String, limit: Int = 30, offset: Int = 0): List<NcmSongDto> {
        val json = NcmHttp.get(
            "$BASE/api/search/get/web?s=" + URLEncoder.encode(keyword, "UTF-8") +
                "&type=1&limit=$limit&offset=$offset",
            session.cookieHeader()
        )
        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        val result = mutableListOf<NcmSongDto>()
        for (i in 0 until songs.length()) {
            songs.optJSONObject(i)?.let { parseSong(it) }?.let { result.add(it) }
        }
        return result
    }

    // ───────── 红心回写 ─────────

    /** 点赞/取消点赞；返回是否成功 */
    suspend fun like(session: NcmSession, songId: Long, like: Boolean): Boolean {
        val csrf = session.cookieHeader()
            .split("; ").firstOrNull { it.startsWith("__csrf=") }
            ?.substringAfter("=") ?: ""
        val json = NcmHttp.post(
            "$BASE/api/like",
            session.cookieHeader(),
            "id=$songId&like=$like&csrf=${URLEncoder.encode(csrf, "UTF-8")}",
            session
        )
        return json.optInt("code") == 200
    }

    // ───────── 解析 ─────────

    /** 兼容 v1（artists/album/dt）与新版（ar/al）字段 */
    internal fun parseSong(o: JSONObject): NcmSongDto {
        val artists = o.optJSONArray("ar") ?: o.optJSONArray("artists")
        val artist = (0 until (artists?.length() ?: 0))
            .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
            .filter { it.isNotEmpty() }
            .joinToString(" / ")
        val album = o.optJSONObject("al") ?: o.optJSONObject("album")
        val cover = album?.optString("picUrl")?.takeIf { it.isNotEmpty() }
            ?: album?.optString("img1v1Url")?.takeIf { it.isNotEmpty() }
        return NcmSongDto(
            id = o.optLong("id"),
            title = o.optString("name"),
            artist = artist.ifEmpty { "未知歌手" },
            album = album?.optString("name") ?: "",
            albumId = album?.optLong("id") ?: 0L,
            durationMs = o.optLong("dt").takeIf { it > 0 } ?: o.optLong("duration"),
            coverUrl = cover?.replace("http://", "https://")?.let { com.whale.music.util.CoverUrl.hd(it) },
            fee = o.optInt("fee")
        )
    }
}
