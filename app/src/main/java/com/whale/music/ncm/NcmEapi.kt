package com.whale.music.ncm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 音质档位。
 *
 * [apiValue] 直接就是接口的 `level` 参数，服务端只认这几个字符串，别改。
 */
enum class NcmLevel(val apiValue: String, val needsVip: Boolean) {
    STANDARD("standard", false),
    HIGHER("higher", false),
    EXHIGH("exhigh", false),
    LOSSLESS("lossless", true),
    HIRES("hires", true);

    /** 请求时带哪个容器：免费档 mp3，无损两档 flac */
    val encodeType: String
        get() = if (this == LOSSLESS || this == HIRES) "flac" else "mp3"

    companion object {
        fun of(value: String?): NcmLevel =
            entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) } ?: STANDARD
    }
}

/** 一次取流的实际结果。[level] 是接口**返回**的档位，可能低于请求档 */
data class NcmStream(
    val url: String,
    val level: NcmLevel,
    val br: Int,
    /** 容器：mp3 / flac / flac24bit */
    val type: String,
    /** 只给了试听片段（权益没生效） */
    val trial: Boolean
)

/**
 * 网易云官方客户端 EAPI 取流（音质升级）。
 *
 * ## 为什么需要它
 *
 * 旧的 `GET /api/song/enhance/player/url?br=320000` 是**网页版通道**：
 * 固定 320k、拿不到无损、部分曲目被降成 128k。
 * 换成官方客户端同款的 EAPI 通道后，按 `level` 参数点单，
 * 会员号可稳定拿无损（flac），免费号也能稳定 320k。
 *
 * ## 通道与签名
 *
 * - 主机：`interface3.music.163.com`，路径 `/api/...` → `/eapi/...`
 * - 请求体：`AES-128-ECB(PKCS5)` + MD5 校验串 → 大写十六进制 `params`
 * - Cookie / header 必须带 `os` `appver`（伪装官方客户端）和 `MUSIC_U` `__csrf`（登录态）
 * - 双客户端回退：PC（os=pc）拿不到完整流时换 Android 通道再试
 *
 * 取流逻辑参考 Sonance 播放器（github.com/gxslcl/Sonance）的 `NeteaseStream`。
 */
object NcmEapi {

    private const val TAG = "NcmEapi"

    private const val EAPI_BASE = "https://interface3.music.163.com"
    private const val EAPI_KEY = "e82ckenh8dichen8"

    private const val TIMEOUT = 12_000
    /** 一次批量多少个 id：再大响应体容易被截断，且解析失败很难发现 */
    private const val BATCH = 100
    /** 接口没报 expi 时的兜底寿命（秒） */
    private const val DEFAULT_TTL_SEC = 900L
    /** 地址带签名会过期，缓存留 60 秒余量 */
    private const val EXPIRE_SAFETY_MS = 60_000L

    private const val PC_APPVER = "3.0.18.203097"
    private const val ANDROID_APPVER = "8.10.05"

    private const val PC_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"
    private const val ANDROID_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 一套「客户端身份」：Cookie 的 os/appver、header、UA 必须一致，否则服务端不认 */
    private data class Client(val os: String, val appver: String, val osver: String, val ua: String)

    private val PC = Client("pc", PC_APPVER, "10", PC_UA)
    private val ANDROID = Client("android", ANDROID_APPVER, "13", ANDROID_UA)

    /** 顺序即优先级：PC 官方签名优先，其次是 Android 通道 */
    private val CLIENTS = listOf(PC, ANDROID)

    /**
     * 「自动」档的推导：会员无损、免费 320k。
     * 用户在设置里选了具体档位时不会走到这里（NcmSession.qualityLevel() 优先）。
     */
    fun autoLevel(isVip: Boolean): NcmLevel =
        if (isVip) NcmLevel.LOSSLESS else NcmLevel.EXHIGH

    /**
     * 地址 → 该地址**实际下发**的档位。
     *
     * 播放页的 Hi-Res 小金标靠它判定在线歌：请求的是 lossless，
     * 服务端可能只给 320k，显示必须以实际返回为准（不自欺）。
     * 只在内存里，随进程存活。
     */
    private val urlLevels = ConcurrentHashMap<String, NcmLevel>()

    /** 取这个播放地址实际拿到的档位；没记录过返回 null。大小写/协议差异（http↔https）做一次归一 */
    fun levelOfUrl(url: String): NcmLevel? =
        urlLevels[url]
            ?: urlLevels[url.replaceFirst("https://", "http://")]
            ?: urlLevels[url.replaceFirst("http://", "https://")]

    private class Cached(val stream: NcmStream, val expireAtMs: Long)

    private val cache = ConcurrentHashMap<Long, Cached>()

    /** 清地址缓存：改档位 / 重新登录后调用，否则要等自然过期才生效 */
    fun clearCache() = cache.clear()

    // ───────── 取流 ─────────

    /**
     * 批量取播放地址（内部带缓存 + 档位降级 + 双客户端回退）。
     *
     * @param level 目标档位；服务端按**每首歌的权益**下发，请求无损不等于一定给无损
     * @param durations 已知总时长（毫秒），用于识别接口没标却只有 30 秒的截断试听流
     * @return id → 地址；**试听片段和取不到的歌不出现在结果里**，由调用方决定怎么兜底
     */
    suspend fun playUrls(
        session: NcmSession,
        ids: List<Long>,
        level: NcmLevel,
        durations: Map<Long, Long> = emptyMap()
    ): Map<Long, String> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val out = HashMap<Long, String>()
        var pending = ArrayList<Long>()
        val now = System.currentTimeMillis()

        for (id in ids.distinct()) {
            val hit = cache[id]
            if (hit != null && hit.expireAtMs > now && !hit.stream.trial) {
                out[id] = hit.stream.url
            } else {
                pending.add(id)
            }
        }

        // 档位链：请求档拿不到时降一档再试（服务端有时整档拒绝，降档就能给）
        for (lv in chain(level)) {
            if (pending.isEmpty()) break
            for (client in CLIENTS) {
                if (pending.isEmpty()) break
                val got = fetchBatch(session, pending, lv, client, durations)
                for ((id, s) in got) {
                    if (!s.trial) {
                        out[id] = s.url
                        putCache(id, s)
                        urlLevels[s.url] = s.level
                    } else {
                        Log.i(TAG, "试听片段 id=$id req=${lv.apiValue} got=${s.level.apiValue} 跳过")
                    }
                }
                if (got.isNotEmpty()) {
                    val done = got.keys
                    pending = ArrayList(pending.filter { it !in done })
                }
            }
        }
        out
    }

    /**
     * 取正式下载地址：官方下载接口优先（比播放流更稳），拿不到回落播放接口。
     *
     * @return 拿不到完整流返回 null，调用方回落 [playUrls]
     */
    suspend fun downloadUrl(
        session: NcmSession,
        id: Long,
        level: NcmLevel,
        durationMs: Long = 0L
    ): NcmStream? = withContext(Dispatchers.IO) {
        // 1. 官方 EAPI 下载接口 v1（PC → Android）
        for (client in CLIENTS) {
            val s = requestSingle(
                session, client, "/api/song/enhance/download/url/v1",
                downloadPayload(id, level, client, session), id, durationMs
            )
            if (s != null && !s.trial) return@withContext s
        }
        // 2. 旧版下载接口（br 形式）
        val legacy = requestSingle(
            session, PC, "/api/song/enhance/download/url",
            legacyDownloadPayload(id, level, PC, session), id, durationMs
        )
        if (legacy != null && !legacy.trial) return@withContext legacy

        // 3. 都不行就回落播放接口
        for (client in CLIENTS) {
            val s = requestSingle(
                session, client, "/api/song/enhance/player/url/v1",
                streamPayload(listOf(id), level, client, session), id, durationMs
            )
            if (s != null) return@withContext s
        }
        null
    }

    // ───────── 请求与解析 ─────────

    private fun chain(level: NcmLevel): List<NcmLevel> = when (level) {
        NcmLevel.HIRES -> listOf(NcmLevel.HIRES, NcmLevel.LOSSLESS, NcmLevel.EXHIGH)
        NcmLevel.LOSSLESS -> listOf(NcmLevel.LOSSLESS, NcmLevel.EXHIGH, NcmLevel.STANDARD)
        NcmLevel.EXHIGH -> listOf(NcmLevel.EXHIGH, NcmLevel.STANDARD)
        NcmLevel.HIGHER -> listOf(NcmLevel.HIGHER, NcmLevel.STANDARD)
        NcmLevel.STANDARD -> listOf(NcmLevel.STANDARD)
    }

    /** 批量取一档；返回本批里**有地址**的条目（含试听，试听与否由调用方过滤） */
    private fun fetchBatch(
        session: NcmSession,
        ids: List<Long>,
        level: NcmLevel,
        client: Client,
        durations: Map<Long, Long>
    ): Map<Long, NcmStream> {
        val out = HashMap<Long, NcmStream>()
        for (chunk in ids.chunked(BATCH)) {
            val payload = streamPayload(chunk, level, client, session)
            val body = postEapi(
                apiPath = "/api/song/enhance/player/url/v1",
                payload = payload,
                cookie = cookieFor(client, session),
                ua = client.ua
            ) ?: continue
            parseDataArray(body)?.objects()?.forEach { item ->
                val id = item.optLong("id")
                if (id == 0L) return@forEach
                val stream = parseItem(item, durations[id] ?: 0L) ?: return@forEach
                out[id] = stream
            }
        }
        return out
    }

    private fun requestSingle(
        session: NcmSession,
        client: Client,
        apiPath: String,
        payload: JSONObject,
        id: Long,
        durationMs: Long
    ): NcmStream? {
        val body = postEapi(apiPath, payload, cookieFor(client, session), client.ua) ?: return null
        val item = parseDataArray(body)?.objects()?.firstOrNull { it.optLong("id") == id || it.has("url") }
            ?: return null
        return parseItem(item, durationMs)
    }

    /** Android 的 `org.json.JSONArray` 不是 Iterable，抽一层省得每次手写 for 循环 */
    private fun JSONArray.objects(): List<JSONObject> {
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
        return out
    }

    private fun streamPayload(ids: List<Long>, level: NcmLevel, client: Client, session: NcmSession): JSONObject =
        JSONObject().apply {
            put("ids", JSONArray(ids).toString())
            put("level", level.apiValue)
            put("encodeType", level.encodeType)
            put("header", headerJson(client, session).toString())
        }

    private fun downloadPayload(id: Long, level: NcmLevel, client: Client, session: NcmSession): JSONObject =
        JSONObject().apply {
            put("id", id.toString())
            put("ids", JSONArray(listOf(id)).toString())
            put("level", level.apiValue)
            put("encodeType", level.encodeType)
            put("header", headerJson(client, session).toString())
        }

    private fun legacyDownloadPayload(id: Long, level: NcmLevel, client: Client, session: NcmSession): JSONObject =
        JSONObject().apply {
            val br = when (level) {
                NcmLevel.HIRES, NcmLevel.LOSSLESS -> 999000
                NcmLevel.EXHIGH -> 320000
                NcmLevel.HIGHER -> 192000
                NcmLevel.STANDARD -> 128000
            }
            put("id", id.toString())
            put("br", br)
            put("header", headerJson(client, session).toString())
        }

    /** EAPI 要求把客户端身份与登录态一并放进 body 的 header 字段 */
    private fun headerJson(client: Client, session: NcmSession): JSONObject =
        JSONObject().apply {
            put("os", client.os)
            put("appver", client.appver)
            put("osver", client.osver)
            put("channel", "netease")
            put("requestId", "${System.currentTimeMillis()}_0001")
            val cookies = cookieMap(session)
            cookies["MUSIC_U"]?.takeIf { it.isNotBlank() }?.let { put("MUSIC_U", it) }
            cookies["__csrf"]?.takeIf { it.isNotBlank() }?.let { put("__csrf", it) }
        }

    /** 把会话 Cookie 按客户端身份覆盖 os/appver —— 三处必须一致 */
    private fun cookieFor(client: Client, session: NcmSession): String {
        val map = cookieMap(session)
        map["os"] = client.os
        map["appver"] = client.appver
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun cookieMap(session: NcmSession): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        for (part in session.cookieHeader().split(";")) {
            val i = part.indexOf('=')
            if (i > 0) map[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
        return map
    }

    /** 解析 data 字段（兼容对象与数组两种形态） */
    private fun parseDataArray(body: String): JSONArray? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        json.optJSONArray("data")?.let { return it }
        json.optJSONObject("data")?.let { return JSONArray().put(it) }
        return null
    }

    /**
     * 把一条响应转成 [NcmStream]；没有可用地址返回 null。
     *
     * ## 试听识别为什么要多信号
     *
     * 部分场景（Hi-Res / 日推）接口**不填** `freeTrialInfo`，但实际只下发约 30 秒的文件。
     * 所以联合判别：freeTrialInfo、CDN 路径特征、`time` 字段、
     * 「size × 8 ÷ br」估出来的时长、以及 `freeTrialPrivilege` 权益位。
     */
    private fun parseItem(item: JSONObject, expectedDurationMs: Long): NcmStream? {
        // 网易在无版权/未登录时会下发 "url": null，org.json 的 optString 会把它
        // 变成字符串 "null"，不校验就会被当成有效地址（下载报 no protocol: null）
        val url = item.optString("url").orEmpty()
        if (!url.startsWith("http")) return null

        val br = item.optInt("br", 0)
        val sizeBytes = item.optLong("size", 0L)
        val returnedTimeMs = item.optLong("time", 0L)

        val hasTrialInfo = item.opt("freeTrialInfo") != null && item.opt("freeTrialInfo") != JSONObject.NULL
        val isTrialCdnPath = url.contains("jd-musicrep-ts", ignoreCase = true)
        val estimatedMs = if (br > 0 && sizeBytes > 0L) (sizeBytes * 8_000L) / br else 0L
        val knownFullMs = maxOf(expectedDurationMs, returnedTimeMs)

        val timeIsTrial = returnedTimeMs in 1L..45_000L && (
            expectedDurationMs >= 65_000L ||
                (expectedDurationMs <= 0L && returnedTimeMs in 28_000L..32_500L)
            )
        val sizeIsTrial = estimatedMs in 1L..45_000L && (
            knownFullMs >= 65_000L ||
                (knownFullMs <= 0L && estimatedMs in 25_000L..35_000L)
            )
        val ftp = item.optJSONObject("freeTrialPrivilege")
        val privilegeDenied = ftp != null &&
            ftp.optBoolean("resConsumable", false) &&
            ftp.has("userConsumable") &&
            !ftp.optBoolean("userConsumable", true)
        val sizeLooksFull = knownFullMs > 0L && estimatedMs >= (knownFullMs * 7L) / 10L

        return NcmStream(
            url = url,
            level = NcmLevel.of(item.optString("level")),
            br = br,
            type = item.optString("type").ifBlank { "mp3" },
            trial = hasTrialInfo || isTrialCdnPath || timeIsTrial || sizeIsTrial ||
                (privilegeDenied && !sizeLooksFull)
        )
    }

    private fun putCache(id: Long, s: NcmStream) {
        // 试听片段不留缓存：下次可能拿到完整流
        if (s.trial) return
        cache[id] = Cached(s, System.currentTimeMillis() + DEFAULT_TTL_SEC * 1000L - EXPIRE_SAFETY_MS)
    }

    // ───────── 加密与传输 ─────────

    /**
     * 标准 EAPI 请求加密：`AES-128-ECB(PKCS5)` + MD5 校验串 → 大写十六进制。
     * 抽成纯函数是为了能单测 —— 拼错一个分隔符服务端只回 404，很难查。
     */
    internal fun encryptParams(apiPath: String, dataJson: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest("nobody${apiPath}use${dataJson}md5forencrypt".toByteArray(Charsets.UTF_8))
        val digestHex = digest.joinToString("") { "%02x".format(it) }
        val plain = "$apiPath-36cd479b6b5-$dataJson-36cd479b6b5-$digestHex"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(Charsets.US_ASCII), "AES"))
        }
        return cipher.doFinal(plain.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
    }

    /** 发一次 EAPI；响应不带 `e_r=true`，服务端直接回明文 JSON */
    private fun postEapi(apiPath: String, payload: JSONObject, cookie: String, ua: String): String? {
        val eapiPath = apiPath.replaceFirst("/api/", "/eapi/")
        val params = encryptParams(apiPath, payload.toString())
        return runCatching {
            val conn = (URL(EAPI_BASE + eapiPath).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Referer", "https://music.163.com/")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
            }
            conn.outputStream.use { it.write("params=$params".toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
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
            android.util.Log.d(TAG, "EAPI $code $eapiPath body=${body.take(80)}")
            body.takeIf { it.isNotEmpty() }
        }.getOrElse {
            android.util.Log.w(TAG, "EAPI 请求失败 $apiPath", it)
            null
        }
    }
}
