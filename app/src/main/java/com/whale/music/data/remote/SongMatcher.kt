package com.whale.music.data.remote

import com.whale.music.data.model.QsTrack
import com.whale.music.data.model.RemoteSong

/**
 * 曲目匹配与候选打分的纯逻辑 —— 不依赖 Android、网络与任何可变状态，
 * 可以直接在 JVM 上跑单测（见 SongMatcherTest）。
 *
 * 裁判规则（宁可跳过，也不把同名的另一首歌端给用户）：
 * 1. 两边歌手都能识别时必须对得上，否则直接淘汰（-1）；
 * 2. 两边时长都已知且相差超过 [DURATION_MAX_DIFF_MS] 直接淘汰 ——
 *    同名同歌手差这么多基本就是 Live / 短版 / 加长版；
 * 3. 歌名必须一致或高度相似，只是「像」的一律不要；
 * 4. 综合分低于 [ACCEPT_MIN_SCORE] 视为不可信，宁可不播。
 */
object SongMatcher {

    /** 命中阈值：低于此分的候选宁可不播也不播成别的歌 */
    const val ACCEPT_MIN_SCORE = 20

    /** 歌名完全一致的基础分（达到它就没必要再降级检索了） */
    const val EXACT_TITLE_SCORE = 40

    /** 时长硬门：两边都已知时相差超过这个值直接淘汰同名异版本 */
    const val DURATION_MAX_DIFF_MS = 30_000L

    /** 不能用来核对身份的歌手占位词（normalize 后的形态） */
    private val PLACEHOLDER_ARTISTS = setOf(
        "未知歌手", "未知", "unknown", "unknownartist", "variousartists",
        "群星", "多位歌手", "多歌手", "不详", "佚名", "不明确"
    )

    /**
     * 三级检索词：歌名+歌手 → 只搜歌名 → 去掉括号后缀再搜。
     * 歌手是占位词时不进检索词，免得污染搜索结果。
     */
    fun queries(track: QsTrack): List<String> {
        // 歌名是空的没法按名匹配（score 也会拒绝），不浪费检索
        if (track.title.isBlank()) return emptyList()
        val wantArtist = track.artist.takeIf { meaningfulArtist(it) }.orEmpty()
        return buildList {
            if (wantArtist.isNotBlank()) add("${track.title} $wantArtist".trim())
            add(track.title)
            val bare = stripBracketSuffix(track.title).trim()
            if (bare.isNotBlank() && bare != track.title) {
                add(if (wantArtist.isNotBlank()) "$bare $wantArtist" else bare)
            }
        }.distinct()
    }

    /**
     * 在一批候选里选最优的一条；都不可信返回 null。
     * @return 命中的候选与分数
     */
    fun pickBest(hits: List<RemoteSong>, track: QsTrack): Pair<RemoteSong, Int>? {
        var best: RemoteSong? = null
        var bestScore = Int.MIN_VALUE
        for (h in hits) {
            val s = score(h, track)
            if (s > bestScore) {
                bestScore = s
                best = h
            }
        }
        return best?.takeIf { bestScore >= ACCEPT_MIN_SCORE }?.let { it to bestScore }
    }

    /**
     * 候选打分。判断依据依次是：歌手核对、时长核对、歌名一致度、版本后缀。
     *
     * 关键规则：两边歌手都能识别时必须对得上 —— 同名不同歌手的候选直接淘汰
     * （-1），不再靠 +15/+26 这种加分去「追回来」。只有歌手一侧缺失
     * （网页兜底的「未知歌手」、酷我没回歌手）才退回歌名 + 时长判断。
     */
    fun score(hit: RemoteSong, track: QsTrack): Int {
        val ht = normalizeTitle(hit.title)
        val wt = normalizeTitle(track.title)
        if (ht.isEmpty() || wt.isEmpty()) return -1

        // 歌手核对：能核对就必须一致，否则再像也不要
        val wantArtists = splitArtists(track.artist)
        val hitArtists = splitArtists(hit.artist)
        val artistCheckable = wantArtists.isNotEmpty() && hitArtists.isNotEmpty()
        if (artistCheckable && !artistsOverlap(hit.artist, wantArtists)) return -1

        // 时长硬门：两边都已知且差 > 30s → 同名不同版本（Live / 短版 / 加长版），淘汰
        if (track.duration > 0 && hit.duration > 0) {
            if (kotlin.math.abs(hit.duration - track.duration) > DURATION_MAX_DIFF_MS) return -1
        }

        var s = when {
            ht == wt -> EXACT_TITLE_SCORE
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

        // 时长软加分（差 > 30s 已在上面硬门淘汰）
        if (track.duration > 0 && hit.duration > 0) {
            val diff = kotlin.math.abs(hit.duration - track.duration)
            s += when {
                diff < 3_000 -> 22
                diff < 10_000 -> 10
                else -> 0
            }
        }
        return s
    }

    /** 歌手字段是否可用于核对（空 / 占位词不算数） */
    fun meaningfulArtist(raw: String): Boolean {
        val n = normalizeTitle(raw)
        if (n.isEmpty()) return false
        return n !in PLACEHOLDER_ARTISTS
    }

    fun splitArtists(raw: String): List<String> =
        raw.split('/', '&', ',', '、', '，')
            .map { normalizeTitle(it) }
            .filter { meaningfulArtist(it) }

    /** 粗糙的字符串相似度：短串的字符有六成能在长串里找到就算像 */
    fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        var hit = 0
        for (c in short) if (long.contains(c)) hit++
        return hit.toFloat() / short.length >= 0.6f
    }

    /** 去掉括号里的版本说明、标点与空白，只留主标题 */
    fun normalizeTitle(raw: String): String = raw
        .replace(Regex("[(（\\[【].*?[)）\\]】]"), "")
        // 书名号只剥符号本身：《晴天》是整首歌包了书名号，内容要留
        .replace(Regex("[《》]"), "")
        .replace(Regex("[\\s\\-—_·,，.。'\"“”‘’!！?？]"), "")
        .lowercase()

    /** 只去掉括号后缀、保留其余原样（检索词用，不动标点与大小写） */
    private fun stripBracketSuffix(raw: String): String =
        raw.replace(Regex("[(（\\[【].*?[)）\\]】]"), "")
            .replace(Regex("[《》]"), "")

    fun artistsOverlap(kwArtist: String, want: List<String>): Boolean {
        if (want.isEmpty()) return true
        val have = splitArtists(kwArtist)
        return have.any { h -> want.any { w -> h.contains(w) || w.contains(h) } }
    }
}
