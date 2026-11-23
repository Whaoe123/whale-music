package com.whale.music.data.remote

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.whale.music.ncm.NcmHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class LyricLine(val timeMs: Long, val text: String)

/**
 * 歌词源 — 四级：本地同名 .lrc → 磁盘哨兵 → 网易（匿名可用）→ 酷我 / lx 脚本兜底。
 * 本地歌曲、在线歌曲通用。
 *
 * 负缓存策略（Sonance 同款防限流）：
 * - 「搜不到 / 没歌词」写**空哨兵文件**，TTL 内不再打接口；
 * - 网络异常（超时 / 风控）**不写缓存**，下次照常重试；
 * - 有歌词的命中记 30 天。
 */
object LyricSource {

    private val memory = HashMap<String, List<LyricLine>>()

    /** 磁盘哨兵目录（OHMusicApp 启动时挂上；没挂就只用内存缓存） */
    @Volatile
    private var diskDir: java.io.File? = null

    /** 查歌曲文件真实路径用（把 content:// 换成 /storage/... 才能找同目录 .lrc） */
    @Volatile
    private var resolver: ContentResolver? = null

    private const val DISK_HIT_TTL_MS = 30L * 24 * 3600_000
    private const val DISK_MISS_TTL_MS = 2L * 24 * 3600_000

    fun attachCacheDir(cacheRoot: java.io.File) {
        diskDir = java.io.File(cacheRoot, "lyrics")
    }

    fun attachContext(context: Context) {
        resolver = context.applicationContext.contentResolver
    }

    /** 清歌词缓存（设置页「清理缓存」用） */
    fun clearCache() {
        memory.clear()
        val dir = diskDir ?: return
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    /**
     * @param audioUri 当前曲目的 content:// / file:// 地址，用于找同目录同名 .lrc
     * @param extra    网易没词时的兜底（酷我 → lx 脚本），由调用方注入
     */
    suspend fun load(
        title: String,
        artist: String,
        audioUri: String? = null,
        extra: (suspend () -> String?)? = null
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        val key = "$title|$artist"
        memory[key]?.let { return@withContext it }
        // ① 本地同名 .lrc：用户自己整理好的词，不会搜错歌，优先级最高
        readLocalLrc(audioUri)?.let { raw ->
            val lines = parseLrc(raw)
            if (lines.isNotEmpty()) {
                memory[key] = lines
                return@withContext lines
            }
        }
        // ② 磁盘哨兵（网易命中记 30 天 / 空词记 2 天）
        readDisk(key)?.let { raw ->
            return@withContext parseLrc(raw).also { memory[key] = it }
        }
        // ③ 网易：null = 传输失败（不缓存，下次还试）；"" = 语义空结果（写哨兵）
        val net = try {
            val sid = searchSongId("$title $artist")
            if (sid == null) "" else fetchLyric(sid)
        } catch (e: Exception) {
            null
        }
        if (!net.isNullOrBlank()) {
            writeDisk(key, net)
            return@withContext parseLrc(net).also { memory[key] = it }
        }
        // ④ 兜底：酷我逐行 LRC / lx 脚本（脚本实现了 lyric 才有）
        val fallback = try {
            extra?.invoke()
        } catch (e: Exception) {
            null
        }
        if (!fallback.isNullOrBlank()) {
            val lines = parseLrc(fallback)
            if (lines.isNotEmpty()) {
                writeDisk(key, fallback)
                memory[key] = lines
                return@withContext lines
            }
        }
        // 只有「网易明确说没词」才写空哨兵；传输失败留着下次重试
        if (net != null) writeDisk(key, "")
        emptyList()
    }

    // ───────── 本地同名 .lrc ─────────

    /**
     * 歌曲文件同目录的 `.lrc`（同名 / `.mp3.lrc` / 大写扩展名都试）。
     *
     * 安卓 11+ 分区存储下非媒体文件（.lrc 不是音频）不能按路径直读，
     * 所以这里只在「已授予所有文件访问」或旧系统上命中；
     * 读不到就静默交给网易，不打扰没有 .lrc 的歌。
     */
    private fun readLocalLrc(audioUri: String?): String? {
        if (audioUri.isNullOrBlank()) return null
        val path = audioPathOf(audioUri) ?: return null
        val f = File(path)
        val dir = f.parentFile ?: return null
        val base = f.name.substringBeforeLast('.', f.name)
        for (name in listOf("$base.lrc", "$base.LRC", "${f.name}.lrc")) {
            val file = File(dir, name)
            if (!file.isFile) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            val text = decodeLrc(bytes)
            if (text.isNotBlank()) return text
        }
        return null
    }

    /** content:// → 文件系统路径（MediaStore 的 DATA 列；拿不到返回 null） */
    private fun audioPathOf(uri: String): String? {
        if (uri.startsWith("/")) return uri
        if (uri.startsWith("file://")) return uri.removePrefix("file://")
        // 在线歌（http/https）没有本地文件，直接跳过，别去查 MediaStore
        if (!uri.startsWith("content://")) return null
        val cr = resolver ?: return null
        return runCatching {
            cr.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 解本地歌词字节流：UTF-16 / UTF-8 BOM 自动识别，
     * 非法 UTF-8 再按 GB18030 解（国内歌词站下的 .lrc 十有八九是 GBK）。
     */
    internal fun decodeLrc(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
        }
        var from = 0
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            from = 3
        }
        val body = if (from == 0) bytes else bytes.copyOfRange(from, bytes.size)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (e: Exception) {
            String(body, charset("GB18030"))
        }
    }

    private fun fileFor(key: String): java.io.File? {
        val dir = diskDir ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "$name.lrc")
    }

    /** 返回磁盘上的歌词原文；null = 没有或已过期（过期顺手删掉）；"" = 空哨兵 */
    private fun readDisk(key: String): String? {
        val f = fileFor(key) ?: return null
        if (!f.exists()) return null
        val age = System.currentTimeMillis() - f.lastModified()
        val ttl = if (f.length() > 0) DISK_HIT_TTL_MS else DISK_MISS_TTL_MS
        if (age >= ttl) {
            f.delete()
            return null
        }
        return try {
            f.readText()
        } catch (e: Exception) {
            null
        }
    }

    private fun writeDisk(key: String, raw: String) {
        val f = fileFor(key) ?: return
        try {
            f.parentFile?.mkdirs()
            f.writeText(raw)
        } catch (e: Exception) {
            runCatching { f.delete() }
        }
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
