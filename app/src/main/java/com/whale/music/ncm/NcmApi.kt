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
 *  - GET  /api/v6/playlist/detail           歌单曲目 id（参数轮换，见 V6_PARAM_SETS）
 *  - POST /api/v3/song/detail               批量歌曲详情（米柚同款，c= 参数，500/批，主用）
 *  - GET  /api/song/detail                  批量歌曲详情（POST 被风控时的兜底，100/批）
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
        val (code, body) = fetchBody(url, cookie, method, form, session)
        if (body.isEmpty()) return JSONObject().put("code", code)
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            // 非 JSON（如重定向页）→ 用 HTTP 状态码占位，交由上层按「未知响应」处理
            JSONObject().put("code", code)
        }
    }

    /** 只要原始文本（网页版歌单页兜底用） */
    fun rawGet(url: String, cookie: String): String = fetchBody(url, cookie, "GET", null, null).second

    private fun fetchBody(
        url: String,
        cookie: String,
        method: String,
        form: String?,
        session: NcmSession?
    ): Pair<Int, String> {
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
            "HTTP $code $method ${url.substringAfter("music.163.com")} " +
                "cookie=${cookie.count { it == '=' }}个 body=${body.take(100)}"
        )
        return code to body
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

    /**
     * v6 歌单详情的参数轮换集（米柚用第一套 n=50000&s=8）。
     * 网易云 -462 风控按 IP / 请求频率动态波动，没有绝对安全的参数 —— 一套失手就换下一套。
     */
    private val V6_PARAM_SETS = listOf("&n=50000&s=8", "&n=1000", "")

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
        val expected: Int = 0,
        val attempts: Int = 1
    ) {
        /** 接口没给到详情的曲数（版权/风控等），用于提示用户，不静默吞掉 */
        val missing: Int get() = (requested - songs.size).coerceAtLeast(0)

        /** 给用户看的诊断行：歌单应有多少 / 清单多少 / 详情多少 / 最终多少 */
        fun diagnostics(): String = buildString {
            if (expected > 0) append("歌单 $expected · ")
            append("清单 $trackIdsCount · 详情 $tracksCount · 补齐 $filled · 最终 ${songs.size}")
            append("（请求 $attempts 次）")
        }
    }

    suspend fun likedSongsDetailed(session: NcmSession, uid: Long): LikedFetch {
        val playlistId = likedPlaylistId(session, uid) ?: return LikedFetch(emptyList(), 0)

        val byId = LinkedHashMap<Long, NcmSongDto>()
        val orderedIds = LinkedHashSet<Long>()
        var expected = 0
        var attempts = 0

        /**
         * 打一次 v6 歌单详情：参数轮换（风控是按 IP/频率动态变化的，没有绝对安全的参数，
         * 一套失手就换下一套），把 tracks / trackIds 都并进已有结果。
         * 返回 true = 这一轮拿到了新 id。
         */
        fun refreshV6(): Boolean {
            var gained = false
            for (params in V6_PARAM_SETS) {
                runCatching {
                    val json = NcmHttp.get(
                        "$BASE/api/v6/playlist/detail?id=$playlistId$params",
                        session.cookieHeader()
                    )
                    if (json.optInt("code", 200) != 200) return@runCatching
                    val pl = json.optJSONObject("playlist") ?: return@runCatching
                    val tc = pl.optInt("trackCount", 0)
                    if (tc > expected) expected = tc
                    pl.optJSONArray("tracks")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { parseSong(it) }?.let {
                                if (!byId.containsKey(it.id)) gained = true
                                byId[it.id] = it
                            }
                        }
                    }
                    pl.optJSONArray("trackIds")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val id = arr.optJSONObject(i)?.optLong("id") ?: continue
                            if (id > 0 && orderedIds.add(id)) gained = true
                        }
                    }
                }
                // 这套参数已经拿全（trackIds 达到歌单曲数）→ 不用再试别的参数，省请求
                if (expected > 0 && orderedIds.size >= expected) break
            }
            return gained
        }

        // ① 连打几次取并集，直到不再有新 id（米柚方案：只认 trackIds，参数轮换 + 重试）
        for (attempt in 0 until 3) {
            attempts = attempt + 1
            val gained = refreshV6()
            if (!gained || (expected > 0 && orderedIds.size >= expected)) break
            if (attempt < 2) delay(700L)
        }

        // ② 网页版兜底：API 还是不全（或压根没给）时，从歌单页 HTML 抓 id + 歌名
        //    —— HTML 里的顺序就是歌单顺序，且不受接口截断影响
        var htmlTitles: Map<Long, String> = emptyMap()
        if (orderedIds.isEmpty() || (expected > 0 && orderedIds.size < expected)) {
            htmlTitles = fetchPlaylistHtml(session, playlistId)
            if (htmlTitles.size > orderedIds.size) {
                orderedIds.clear()
                orderedIds.addAll(htmlTitles.keys)
            } else {
                htmlTitles.keys.forEach { if (!orderedIds.contains(it)) orderedIds.add(it) }
            }
        }

        if (orderedIds.isEmpty()) orderedIds.addAll(byId.keys)
        if (orderedIds.isEmpty()) return LikedFetch(emptyList(), 0, expected = expected, attempts = attempts)

        // ③ 补齐缺失详情（按 id 批量取，带重试）
        val beforeFill = byId.size
        fillDetails(session, orderedIds, byId)
        val filled = byId.size - beforeFill

        // ④ 还缺 → 再打一轮 v6（参数轮换，可能上一次被截断）+ 再补一次详情
        if (orderedIds.any { !byId.containsKey(it) }) {
            refreshV6()
            attempts++
            fillDetails(session, orderedIds, byId)
        }

        // ⑤ 最后兜底：有网页版歌名、但接口死活不给详情的 → 用歌名先建条目，
        //    播放时优先按网易 id 直连取链（RemoteMusicController.resolveTrack），
        //    拿不到链接才按「歌名+歌手」核对后转酷我
        var fromHtml = 0
        for (id in orderedIds) {
            if (byId.containsKey(id)) continue
            val title = htmlTitles[id] ?: continue
            byId[id] = NcmSongDto(
                id = id,
                title = title,
                artist = "未知歌手",
                album = "",
                albumId = 0L,
                durationMs = 0L,
                coverUrl = null,
                fee = 0
            )
            fromHtml++
        }

        val songs = orderedIds.mapNotNull { byId[it] }.ifEmpty { byId.values.toList() }
        return LikedFetch(
            songs = songs,
            requested = orderedIds.size.coerceAtLeast(songs.size),
            trackIdsCount = orderedIds.size,
            tracksCount = byId.size,
            filled = filled + fromHtml,
            expected = expected,
            attempts = attempts
        )
    }

    /**
     * 网页版歌单页兜底：抓 `href="/song?id=xxx">歌名` 列表（id → 歌名）。
     * 需要登录 Cookie 才能看到红心歌单；HTML 结构由服务端渲染，不受 JSON 接口截断影响。
     */
    private fun fetchPlaylistHtml(session: NcmSession, playlistId: Long): Map<Long, String> {
        val out = LinkedHashMap<Long, String>()
        runCatching {
            val html = NcmHttp.rawGet("$BASE/playlist?id=$playlistId", session.cookieHeader())
            if (html.length < 500) return out
            val re = Regex("""<a[^>]+href="/song\?id=(\d+)"[^>]*>([^<]{1,120})</a>""")
            for (m in re.findAll(html)) {
                val id = m.groupValues[1].toLongOrNull() ?: continue
                val title = m.groupValues[2]
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&#39;", "'").replace("&quot;", "\"")
                    .trim()
                if (id > 0 && title.isNotEmpty() && !out.containsKey(id)) out[id] = title
            }
            android.util.Log.d("NcmApi", "网页版兜底：抓到 ${out.size} 首（歌单 $playlistId）")
        }
        return out
    }

    /** 把 orderedIds 里还没有详情的批量补上 */
    private suspend fun fillDetails(
        session: NcmSession,
        orderedIds: Collection<Long>,
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

    /**
     * 批量歌曲详情 —— 米柚同款：POST /api/v3/song/detail，body `c=[{"id":...},…]`，
     * 500 首一批（实测 1273 首歌 3 次请求全拿全，3.7s）。该形态是网易云 App 原生接口，
     * 比旧方案 GET /api/song/detail?ids=（100/批、13 次请求）触发风控的概率低得多。
     * POST 连续失败时退回旧 GET 形态兜底；响应必须校验 code + songs，失败退避重试。
     */
    suspend fun songDetails(session: NcmSession, ids: List<Long>): List<NcmSongDto> {
        if (ids.isEmpty()) return emptyList()
        val result = mutableListOf<NcmSongDto>()
        ids.chunked(500).forEach { batch ->
            var done = false
            repeat(3) { attempt ->
                if (done) return@repeat
                val c = JSONArray().apply { batch.forEach { put(JSONObject().put("id", it)) } }.toString()
                val json = runCatching {
                    NcmHttp.post(
                        "$BASE/api/v3/song/detail",
                        session.cookieHeader(),
                        "c=" + URLEncoder.encode(c, "UTF-8"),
                        session
                    )
                }.getOrNull()
                done = collectSongs(json, result)
                if (!done) delay(700L * (attempt + 1)) // 被风控 / 空响应：错开重试
            }
            // POST 被风控 → 退回旧 GET 形态（100/批）兜底
            if (!done) {
                val missing = batch.filter { id -> result.none { it.id == id } }
                missing.chunked(100).forEach { sub ->
                    var got = false
                    repeat(2) { attempt ->
                        if (got) return@repeat
                        val json = runCatching {
                            NcmHttp.get(
                                "$BASE/api/song/detail?ids=" + URLEncoder.encode(JSONArray(sub).toString(), "UTF-8"),
                                session.cookieHeader()
                            )
                        }.getOrNull()
                        got = collectSongs(json, result)
                        if (!got) delay(700L * (attempt + 1))
                    }
                }
            }
            val stillMissing = batch.count { id -> result.none { it.id == id } }
            if (stillMissing > 0) {
                android.util.Log.w("NcmApi", "song/detail 补齐后仍缺 $stillMissing/${batch.size} 首")
            }
        }
        return result
    }

    /** 校验 song/detail 响应并把 songs 收进 result；结构对就返回 true（允许 0 首，避免无谓重试） */
    private fun collectSongs(json: JSONObject?, result: MutableList<NcmSongDto>): Boolean {
        if (json == null || !json.has("songs") || json.optInt("code") != 200) return false
        val songs = json.optJSONArray("songs") ?: return false
        for (i in 0 until songs.length()) {
            songs.optJSONObject(i)?.let { parseSong(it) }?.let { result.add(it) }
        }
        return true
    }

    // ───────── 播放链接 ─────────

    /**
     * 批量取播放链接，返回 id → https 地址。
     * 取不到的歌（版权/接口不发链接）不出现在结果里，由调用方决定提示方式。
     *
     * ## 音质：优先官方 EAPI 通道，失败回退网页接口
     *
     * - **EAPI（[NcmEapi]）**：按 `level` 点单，会员自动无损（flac），免费号稳定 320k；
     *   带 30 秒截断流的多信号试听识别，试听片段自动跳过。
     * - **网页接口（原有逻辑）**：`br=320000` 固定档，作为 EAPI 被风控/改版时的兜底，
     *   保证「能出声」这条底线不因为换通道而丢。
     *
     * @param durations 已知总时长（毫秒），给试听识别用；不知道可不传
     */
    suspend fun playUrls(
        session: NcmSession,
        ids: List<Long>,
        durations: Map<Long, Long> = emptyMap()
    ): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, String>()

        // ① EAPI 通道：档位取用户在设置里选的；选「自动」才按会员身份推
        //    （会员无损 / 免费 320k），逐档降级 + PC/Android 双客户端
        val level = session.qualityLevel() ?: NcmEapi.autoLevel(session.isVip())
        try {
            val eapi = NcmEapi.playUrls(session, ids, level, durations)
            for ((id, url) in eapi) result[id] = url.replaceFirst("http://", "https://")
        } catch (e: Exception) {
            android.util.Log.w("NcmApi", "EAPI 取流失败，走网页接口兜底", e)
        }

        // ② 还缺的（版权/风控/降档链走完仍无地址）→ 网页接口补齐。
        //    网页通道没有无损，最高只给 320k，所以 br 按所选档位映射、但封顶 320k，
        //    这样选「标准」时兜底也不会倒退成 320k。
        val fallbackBr = when (level) {
            NcmLevel.STANDARD -> 128000
            NcmLevel.HIGHER -> 192000
            else -> 320000
        }
        val missing = ids.distinct().filter { it !in result }
        if (missing.isEmpty()) return result
        missing.chunked(500).forEach { batch ->
            val json = NcmHttp.get(
                "$BASE/api/song/enhance/player/url?ids=" +
                    URLEncoder.encode(JSONArray(batch).toString(), "UTF-8") + "&br=$fallbackBr",
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

    /**
     * 带热度的单曲搜索（cloudsearch，POST 匿名可读）—— 发现页「网易云」音源。
     * 与 [search] 的区别：返回体里带 pop（0-100），调用方按它降序排，最火的排最前。
     */
    suspend fun searchWithPop(session: NcmSession, keyword: String, limit: Int = 30): List<NcmSongDto> {
        val form = "s=" + URLEncoder.encode(keyword, "UTF-8") +
            "&type=1&limit=$limit&offset=0&total=true"
        val json = NcmHttp.request(
            "$BASE/api/cloudsearch/pc", session.cookieHeader(), "POST", form, session
        )
        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        val result = mutableListOf<NcmSongDto>()
        for (i in 0 until songs.length()) {
            songs.optJSONObject(i)?.let { parseSong(it) }?.let { result.add(it) }
        }
        return result.sortedByDescending { it.pop }
    }

    // ───────── 发现页：我的云歌单 / 私人FM / 歌单搜索 ─────────

    /** 发现页用的歌单卡片（我的云歌单 / 歌单搜索共用） */
    data class NcmPlaylistCard(
        val id: Long,
        val name: String,
        val coverUrl: String,
        val trackCount: Int
    )

    /** 我的云歌单（登录态）—— 发现页「我的云歌单」行 */
    suspend fun userPlaylists(session: NcmSession, limit: Int = 30): List<NcmPlaylistCard> {
        val uid = session.profile.value?.userId ?: return emptyList()
        val json = NcmHttp.get(
            "$BASE/api/user/playlist?uid=$uid&limit=$limit&offset=0",
            session.cookieHeader()
        )
        return parsePlaylistCards(json.optJSONArray("playlist"))
    }

    /** 歌单搜索（type=1000）—— 发现页搜索的「歌单」tab */
    suspend fun searchPlaylists(session: NcmSession, keyword: String, limit: Int = 20): List<NcmPlaylistCard> {
        val json = NcmHttp.get(
            "$BASE/api/search/get/web?s=" + URLEncoder.encode(keyword, "UTF-8") +
                "&type=1000&limit=$limit",
            session.cookieHeader()
        )
        return parsePlaylistCards(json.optJSONObject("result")?.optJSONArray("playlists"))
    }

    private fun parsePlaylistCards(arr: JSONArray?): List<NcmPlaylistCard> {
        if (arr == null) return emptyList()
        val out = mutableListOf<NcmPlaylistCard>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id")
            if (id <= 0L) continue
            out += NcmPlaylistCard(
                id = id,
                name = o.optString("name"),
                coverUrl = o.optString("coverImgUrl"),
                trackCount = o.optInt("trackCount")
            )
        }
        return out
    }

    /**
     * 私人FM 一批歌（登录态）—— 发现页「私人FM」卡。
     * 拿不到（未登录 / 风控）返回空，调用方提示。
     */
    suspend fun personalFm(session: NcmSession): List<NcmSongDto> {
        val json = NcmHttp.get("$BASE/api/v1/radio/get", session.cookieHeader())
        val arr = json.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<NcmSongDto>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { parseSong(it) }?.let { result.add(it) }
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
            fee = o.optInt("fee"),
            // cloudsearch 把 pop 返回成 100.0 这种小数，optInt 走 Double 会丢，统一取 double 再转
            pop = o.optDouble("pop", 0.0).toInt().coerceIn(0, 100)
        )
    }
}
