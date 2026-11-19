package com.whale.music.data.remote

import android.util.Log
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.provider.MediaStore
import com.whale.music.data.local.PlaybackMemory
import com.whale.music.data.model.QsTrack
import com.whale.music.data.model.RemoteSong
import com.whale.music.data.model.Song
import com.whale.music.ncm.NcmApi
import com.whale.music.ncm.NcmEapi
import com.whale.music.ncm.NcmSession
import com.whale.music.ncm.NcmStream
import com.whale.music.player.MusicPlayerManager
import com.whale.music.util.CoverResolver
import com.whale.music.util.CoverUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在线歌曲统一控制：播放解析（酷我优先，网易兜底互转）、下载入库
 */
@Singleton
class RemoteMusicController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playerManager: MusicPlayerManager,
    private val session: NcmSession,
    private val memory: PlaybackMemory
) {

    /** 单次播放列表最多解析的曲目数，避免一次点播全列表请求爆炸 */
    private val maxResolve = 300

    /** URL 缓存：同一首歌重复点击秒开 */
    private val urlCache = ConcurrentHashMap<String, String>()

    /** 汽水 → 酷我 的匹配缓存，key 是汽水 trackId */
    private val kwMatchCache = ConcurrentHashMap<String, RemoteSong>()
    private val kwMissCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 后台批量解析协程。挂上异常处理器：批解析失败只记日志，
     * 绝不能让未捕获异常把进程带走（后台网络失败是常态）。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e ->
                Log.w("RemoteMusicController", "后台解析失败", e)
            }
    )

    /**
     * 解析单曲直链。
     * 酷我源直接取；网易源先走网易接口，拿不到则按「歌名+歌手」转酷我解析。
     */
    suspend fun resolveUrl(song: RemoteSong): String? = withContext(Dispatchers.IO) {
        urlCache[song.songId]?.let { return@withContext it }
        val resolved = resolveUrlUncached(song)
        if (resolved != null) urlCache[song.songId] = resolved
        resolved
    }

    /**
     * 切换音质后立刻清掉已解析地址与 EAPI 缓存。
     *
     * 不清的话：下一次点播会命中 `urlCache` 里的旧档位地址，
     * 用户看到「已选无损」但还在播 320k —— 选项就成了空壳。
     */
    fun clearResolvedUrls() {
        urlCache.clear()
        NcmEapi.clearCache()
        Log.i("RemoteMusicController", "音质已切换，清空地址缓存")
    }

    private suspend fun resolveUrlUncached(song: RemoteSong): String? {
        if (song.source == "kw") {
            KwSource.playUrl(song.rid)?.let { return it }
            // 这条 rid 失效了 → 按「歌名 + 歌手」在酷我重新核对一条活的
            return matchKw(toMatchTrack(song))?.rid?.let { KwSource.playUrl(it) }
        }
        val wyUrl = try {
            // 带上已知时长：EAPI 会用它识别「接口没标、实际只有 30 秒」的截断试听流
            NcmApi.playUrls(session, listOf(song.rid), mapOf(song.rid to song.duration))[song.rid]
        } catch (e: Exception) {
            null
        }
        if (wyUrl != null && wyUrl.isNotBlank()) return wyUrl
        // 网易没发链接 → 按「歌名 + 歌手」去酷我匹配同一首（带核对，不再盲取搜索第一条）
        return matchKw(toMatchTrack(song))?.rid?.let { KwSource.playUrl(it) }
    }

    /** 把在线歌单里的一首歌转成检索用的曲目形态（匹配缓存 key 用 songId） */
    private fun toMatchTrack(song: RemoteSong) = QsTrack(
        id = song.songId,
        title = song.title,
        artist = song.artist,
        album = song.album,
        cover = song.coverUrl,
        duration = song.duration
    )

    /**
     * 批量解析并投递到播放器。
     * @return 错误信息；null = 成功
     */
    suspend fun play(start: RemoteSong, list: List<RemoteSong>): String? {
        // 用户手动点歌：开机快照作废，下次冷启动重新拉推荐
        memory.clearStartupSnapshot()
        // ① 先只解析被点的那一首 → 立刻出声，不等整个列表
        val startUrl = resolveUrl(start) ?: return "这首拿不到播放链接，换一首试试"
        playerManager.setPlaylist(listOf(toLocalSong(start, startUrl)), 0, coversOf(start))

        // ② 其余歌曲后台并发解析（每批 6 首），完成后追加到队列，不打断当前播放
        val rest = list.filter { it.songId != start.songId }.take(maxResolve - 1)
        if (rest.isNotEmpty()) {
            scope.launch {
                rest.chunked(6).forEach { chunk ->
                    val playable = mutableListOf<Song>()
                    val covers = mutableMapOf<String, String>()
                    val results = chunk.map { song -> async { song to resolveUrl(song) } }.awaitAll()
                    for ((song, url) in results) {
                        if (url == null) continue
                        playable += toLocalSong(song, url)
                        if (song.coverUrl.isNotBlank()) covers[song.rid.toString()] = CoverUrl.hd(song.coverUrl)
                    }
                    if (playable.isNotEmpty()) {
                        // MediaController 操作回到主线程
                        withContext(Dispatchers.Main) {
                            playerManager.appendToQueue(playable, covers)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun toLocalSong(s: RemoteSong, url: String) = Song(
        id = s.rid,
        title = s.title,
        artist = s.artist,
        album = s.album,
        duration = s.duration,
        // 网络歌标记：酷我 -1 / 网易 -2，播放器按此区分收藏写哪张表
        albumId = if (s.source == "wy") ALBUM_ID_WY else ALBUM_ID_KW,
        uri = url
    )

    // ───────── 汽水音乐：汽水出内容，酷我出声音 ─────────

    /**
     * 播放一组汽水曲目。
     *
     * 汽水的音频对会员曲是服务端卡口的（只下发 40~60 秒试听），所以这里不做音频解析，
     * 改为按「歌名 + 歌手」在酷我里找同一首歌来播 —— 曲库完整度反而更高。
     *
     * 仍然是渐进式：被点的那首匹配完立刻出声，其余在后台每批 6 首并发匹配后追加队列。
     *
     * @return 错误信息；null = 成功
     */
    suspend fun playQishui(
        tracks: List<QsTrack>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        fromStartup: Boolean = false
    ): String? {
        if (tracks.isEmpty()) return "列表里没有歌曲"
        // 手动点歌（第二页 / 曲库歌单）会让开机快照作废；
        // 只有冷启动自动播放与续播走 fromStartup=true，快照由 StartupViewModel 自己管。
        if (!fromStartup) memory.clearStartupSnapshot()
        var idx = startIndex.coerceIn(0, tracks.lastIndex)

        // 被点的那首选不到 / 拿不到链接就往后顺延：导入歌单经常第一首就是版权受限/冷门曲，
        // 不能让整张歌单卡在开头。
        var matched: RemoteSong? = null
        var startUrl: String? = null
        var sawUrlFail = false
        while (idx < tracks.size) {
            val cand = withContext(Dispatchers.IO) { resolveTrack(tracks[idx]) }
            val u = cand?.let { resolveUrl(it) }
            if (cand != null && u != null) {
                matched = cand
                startUrl = u
                break
            }
            if (cand != null) sawUrlFail = true
            idx++
        }
        val first = matched
        if (first == null || startUrl == null) {
            return if (sawUrlFail) "这首歌暂时拿不到播放链接" else "这个歌单里的歌暂时都没匹配到"
        }

        playerManager.setPlaylist(
            listOf(toLocalSong(first, startUrl)),
            0,
            coversOf(first, tracks[idx]),
            startPositionMs
        )

        val others = tracks.filterIndexed { i, _ -> i != idx }.take(maxResolve - 1)
        if (others.isNotEmpty()) {
            scope.launch {
                // 边匹配边追加：每批 6 首完成立刻进队列，用户听得到后续曲目在长出来
                others.chunked(6).forEach { chunk ->
                    val playable = mutableListOf<Song>()
                    val covers = mutableMapOf<String, String>()
                    val results = chunk.map { t -> async { t to resolveTrack(t) } }.awaitAll()
                    for ((t, kw) in results) {
                        if (kw == null) continue
                        val url = resolveUrl(kw) ?: continue
                        playable += toLocalSong(kw, url)
                        covers.putAll(coversOf(kw, t))
                    }
                    if (playable.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            playerManager.appendToQueue(playable, covers)
                        }
                    }
                }
            }
        }
        return null
    }

    /** 封面优先用汽水那张（跟列表一致），汽水没图才回落酷我专辑图 */
    private fun coversOf(kw: RemoteSong, qs: QsTrack): Map<String, String> {
        val cover = qs.cover.ifBlank { kw.coverUrl }
        return if (cover.isBlank()) emptyMap()
        else mapOf(kw.rid.toString() to CoverUrl.hd(cover))
    }

    /**
     * 歌单加载完之后，在后台先把前几首匹配好。
     * 这样用户点到时只剩「取直链」一步，不会觉得点了没反应。
     */
    suspend fun prefetchQishui(tracks: List<QsTrack>) = withContext(Dispatchers.IO) {
        val pending = tracks.take(8).filter {
            ncmIdOf(it.id) == null && !kwMatchCache.containsKey(it.id) && it.id !in kwMissCache
        }
        if (pending.isEmpty()) return@withContext
        pending.chunked(4).forEach { chunk ->
            chunk.map { t -> async { matchKw(t) } }.awaitAll()
        }
    }

    /**
     * 曲目 → 可播放的源曲目。
     *
     * 带网易真实 id 的（同步红心 / 链接导入 / 启动推荐）直接按 id 走网易源 ——
     * id 对上了就是同一首歌，不存在「按名字找错歌」；
     * 其余（汽水 / QQ / 纯元数据行）才按「歌名 + 歌手」核对匹配酷我。
     */
    private fun resolveTrack(track: QsTrack): RemoteSong? {
        ncmIdOf(track.id)?.let { rid ->
            return RemoteSong(
                songId = "wy_$rid",
                source = "wy",
                rid = rid,
                title = track.title,
                artist = track.artist,
                album = track.album,
                coverUrl = track.cover,
                duration = track.duration
            )
        }
        return matchKw(track)
    }

    /**
     * 从曲目 key 解析网易云真实歌曲 id；不是网易来源返回 null。
     * 支持形态：ncm_<id>、wy_<id>、ext_ncm_<id>、remote_wy_<id>、ext_网易云_ncm_<id>（链接导入）。
     */
    private fun ncmIdOf(key: String): Long? {
        val k = key.removePrefix("remote_")
        if (k.startsWith("ext_ncm_")) return k.removePrefix("ext_ncm_").toLongOrNull()
        if (k.startsWith("ncm_")) return k.removePrefix("ncm_").toLongOrNull()
        if (k.startsWith("wy_")) return k.removePrefix("wy_").toLongOrNull()
        val at = k.lastIndexOf("_ncm_")
        if (at >= 0) return k.substring(at + 5).toLongOrNull()?.takeIf { it > 0 }
        return null
    }

    /** 汽水曲目 → 已匹配的酷我 songId（爱心点亮判断用；没匹配到返回 null） */
    fun kwSongIdOf(trackId: String): String? = kwMatchCache[trackId]?.songId

    /** 收藏用：把汽水曲目解析成酷我 RemoteSong（带缓存，没匹配到返回 null） */
    suspend fun resolveKw(track: QsTrack): RemoteSong? = withContext(Dispatchers.IO) {
        matchKw(track)
    }

    /**
     * 曲目 → 酷我曲目（歌单播放 / 网易兜底都走这里）。
     *
     * 打分顺序：歌名完全一致且有共同歌手 > 歌名完全一致 > 歌名互相包含。
     * 歌名只是「像」、或两边歌手都明确却对不上的候选一律淘汰 ——
     * 宁可这首跳过，也不把同名的另一首歌端给用户。
     */
    private fun matchKw(track: QsTrack): RemoteSong? {
        if (kwMatchCache.size > 600) {
            kwMatchCache.clear()
            kwMissCache.clear()
        }
        kwMatchCache[track.id]?.let { return it }
        if (track.id in kwMissCache) return null

        // 歌单里已经存了酷我 rid（收藏 / 搜索结果加进来的）→ 就用这条，
        // 不再搜索：已知确定的曲目永远比按名字重新找的可靠
        if (track.kwRid > 0) {
            val exact = RemoteSong(
                songId = "kw_${track.kwRid}",
                source = "kw",
                rid = track.kwRid,
                title = track.title,
                artist = track.artist,
                album = track.album,
                coverUrl = track.cover,
                duration = track.duration
            )
            kwMatchCache[track.id] = exact
            return exact
        }

        // 三级检索：歌名+歌手 → 只搜歌名 → 去掉括号后缀再搜
        // 导入歌单里带 "feat." / "(Live)" / 中文书名号的很多，一轮搜不到很常见；
        // 歌手是「未知歌手」这类占位词时不进检索词，免得污染搜索结果
        val wantArtist = track.artist.takeIf { meaningfulArtist(it) }.orEmpty()
        val queries = buildList {
            if (wantArtist.isNotBlank()) add("${track.title} $wantArtist".trim())
            if (track.title.isNotBlank()) add(track.title)
            val bare = track.title.replace(Regex("[(（\\[【].*?[)）\\]】]"), "").trim()
            if (bare.isNotBlank() && bare != track.title) {
                add(if (wantArtist.isNotBlank()) "$bare $wantArtist" else bare)
            }
        }.distinct()

        var best: RemoteSong? = null
        var bestScore = Int.MIN_VALUE
        for (q in queries) {
            val hits = try {
                KwSource.search(q, pageSize = 8)
            } catch (e: Exception) {
                emptyList()
            }
            for (h in hits) {
                val s = score(h, track)
                if (s > bestScore) {
                    bestScore = s
                    best = h
                }
            }
            // 已经找到「歌名完全一致」的候选，没必要再降级检索
            if (bestScore >= 40) break
        }

        // 低于 20 = 歌名只是「有点像」，宁可不播也不播成别的歌
        val result = if (bestScore >= 20) best else null
        if (result != null) kwMatchCache[track.id] = result else kwMissCache += track.id
        return result
    }

    /**
     * 候选打分。判断依据依次是：歌名一致度、是否带版本后缀、歌手核对、时长接近度。
     *
     * 关键规则：两边歌手都能识别时必须对得上 —— 同名不同歌手的候选直接淘汰
     * （-1），不再靠 +15/+26 这种加分去“追回来”。只有歌手一侧缺失
     * （网页兜底的「未知歌手」、酷我没回歌手）才退回歌名 + 时长判断。
     */
    private fun score(hit: RemoteSong, track: QsTrack): Int {
        val ht = normalizeTitle(hit.title)
        val wt = normalizeTitle(track.title)
        if (ht.isEmpty() || wt.isEmpty()) return -1

        // 歌手核对：能核对就必须一致，否则再像也不要
        val wantArtists = splitArtists(track.artist)
        val hitArtists = splitArtists(hit.artist)
        val artistCheckable = wantArtists.isNotEmpty() && hitArtists.isNotEmpty()
        if (artistCheckable && !artistsOverlap(hit.artist, wantArtists)) return -1

        var s = when {
            ht == wt -> 40
            ht.contains(wt) || wt.contains(ht) -> 20
            similar(ht, wt) -> 6
            else -> return -1
        }

        // 原样标题（没有括号后缀）更可信
        if (hit.title.trim() == track.title.trim()) s += 15

        // 原版没标 Live / 伴奏，候选标了，扣分
        val trackHasTag = track.title.contains("live", true) || track.title.contains("伴奏")
        if (!trackHasTag) {
            if (hit.title.contains("live", true)) s -= 14
            if (hit.title.contains("伴奏") || hit.title.contains("instrumental", true)) s -= 24
            if (hit.title.contains("翻唱") || hit.title.contains("cover", true)) s -= 8
            if (hit.title.contains("remix", true) && !track.title.contains("remix", true)) s -= 10
        }

        // 歌手（能核对的已在上面强制通过；核对不了的不加不减）
        if (artistCheckable) {
            s += if (normalizeTitle(hit.artist) == normalizeTitle(track.artist)) 26 else 12
        }

        // 时长
        if (track.duration > 0 && hit.duration > 0) {
            val diff = kotlin.math.abs(hit.duration - track.duration)
            s += when {
                diff < 3_000 -> 22
                diff < 10_000 -> 10
                diff > 30_000 -> -18
                else -> 0
            }
        }
        return s
    }

    /** 歌手字段是否可用于核对（空 / 占位词不算数） */
    private fun meaningfulArtist(raw: String): Boolean {
        val n = normalizeTitle(raw)
        if (n.isEmpty()) return false
        return n !in PLACEHOLDER_ARTISTS
    }

    private fun splitArtists(raw: String): List<String> =
        raw.split('/', '&', ',', '、', '，')
            .map { normalizeTitle(it) }
            .filter { meaningfulArtist(it) }

    /** 粗糙的字符串相似度：短串的字符有六成能在长串里找到就算像 */
    private fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        var hit = 0
        for (c in short) if (long.contains(c)) hit++
        return hit.toFloat() / short.length >= 0.6f
    }

    /** 去掉括号里的版本说明、标点与空白，只留主标题 */
    private fun normalizeTitle(raw: String): String = raw
        .replace(Regex("[(（\\[【].*?[)）\\]】]"), "")
        .replace(Regex("[\\s\\-—_·,，.。'\"“”‘’!！?？]"), "")
        .lowercase()

    private fun artistsOverlap(kwArtist: String, want: List<String>): Boolean {
        if (want.isEmpty()) return true
        val have = splitArtists(kwArtist)
        return have.any { h -> want.any { w -> h.contains(w) || w.contains(h) } }
    }

    companion object {
        /** 播放中歌曲的 albumId 标记：酷我来源 */
        const val ALBUM_ID_KW = -1L

        /** 播放中歌曲的 albumId 标记：网易来源（rid 是网易 id，收藏/加歌单要按 wy 建键） */
        const val ALBUM_ID_WY = -2L

        /** 不能用来核对身份的歌手占位词（normalize 后的形态） */
        private val PLACEHOLDER_ARTISTS = setOf(
            "未知歌手", "未知", "unknown", "unknownartist", "variousartists",
            "群星", "多位歌手", "多歌手", "不详", "佚名", "不明确"
        )
    }

    private fun coversOf(s: RemoteSong): Map<String, String> =
        if (s.coverUrl.isBlank()) emptyMap() else mapOf(s.rid.toString() to CoverUrl.hd(s.coverUrl))

    /**
     * 下载到系统音乐目录 Music/QLHMusic，完成后自动出现在音乐库「歌单」页。
     * @return 错误信息；null = 成功
     */
    suspend fun download(song: RemoteSong): String? = withContext(Dispatchers.IO) {
        // 网易源优先走官方下载接口：按档位点单（会员落无损 flac），比播放流稳；
        // 拿不到完整地址就回落原有的「播放链接」通道，保证照样能下。
        val direct = if (song.source != "kw") {
            runCatching {
                // 下载档位跟用户选的一致：选了无损就下 flac，选 128k 就下 128k
                NcmEapi.downloadUrl(
                    session, song.rid,
                    session.qualityLevel() ?: NcmEapi.autoLevel(session.isVip()),
                    song.duration
                )
            }.getOrNull()
        } else null
        val url = direct?.url?.replaceFirst("http://", "https://")
            ?: (resolveUrl(song) ?: return@withContext "拿不到下载链接")
        val ext = if ((direct?.type ?: "").contains("flac", ignoreCase = true) ||
            url.contains("flac", ignoreCase = true)
        ) "flac" else "mp3"
        val mime = if (ext == "flac") "audio/flac" else "audio/mpeg"
        val safeTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val safeArtist = song.artist.replace(Regex("[\\\\/:*?\"<>|]"), "_")

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safeArtist - $safeTitle.$ext")
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/QLHMusic")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val target = resolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        ) ?: return@withContext "创建下载文件失败"

        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "okhttp/4.9")
            }
            conn.inputStream.use { input ->
                resolver.openOutputStream(target)?.use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                } ?: return@withContext "写入文件失败"
            }
            // 结束 pending，入库可见
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(target, values, null, null)
            // 保留下载前的原封面，避免下载后变成灰色占位图
            saveCover(song, target)
            // 立即触发媒体扫描，音乐库刷新即可见
            MediaScannerConnection.scanFile(context, arrayOf(target.toString()), arrayOf(mime), null)
            null
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            "下载失败：${e.message ?: "网络异常"}"
        }
    }

    /** 把在线封面存成本地 jpg，按 MediaStore 媒体 id 命名，冷启动可自动重建映射 */
    private fun saveCover(song: RemoteSong, mediaUri: android.net.Uri) {
        val coverUrl = CoverUrl.hd(song.coverUrl)
        if (coverUrl.isEmpty()) return
        val mediaId = try {
            android.content.ContentUris.parseId(mediaUri)
        } catch (e: Exception) {
            -1L
        }
        if (mediaId <= 0) return
        val dir = CoverResolver.downloadCoverDir() ?: return
        val file = File(dir, "$mediaId.jpg")
        try {
            val conn = (URL(coverUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            conn.inputStream.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            }
            CoverResolver.registerDownloadCover(mediaId, file)
        } catch (e: Exception) {
            file.delete()
        }
    }
}
