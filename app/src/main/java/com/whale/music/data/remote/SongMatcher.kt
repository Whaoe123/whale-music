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
 * 4. 版本标记按**语义组**比对（`live` / `现场` / `演唱会` 算同一组），
 *    两边同组不罚反加，单边有才扣分；
 * 5. 专辑相同额外加分（尾部版本词剥离后相同加小分），
 *    这是「标题歌手都对」之外最便宜的佐证；
 * 6. 综合分低于 [ACCEPT_MIN_SCORE] 视为不可信，宁可不播。
 */
object SongMatcher {

    /** 命中阈值：低于此分的候选宁可不播也不播成别的歌 */
    const val ACCEPT_MIN_SCORE = 20

    /** 歌名完全一致的基础分（达到它就没必要再降级检索了） */
    const val EXACT_TITLE_SCORE = 40

    /** 时长硬门：两边都已知时相差超过这个值直接淘汰同名异版本 */
    const val DURATION_MAX_DIFF_MS = 30_000L

    /** 版本标记归一到语义组：字面不同但指的是同一个版本（本地写 `(live)`、线上写「现场版」） */
    private val VERSION_GROUPS: List<Pair<String, List<String>>> = listOf(
        "live" to listOf("live", "现场", "演唱会", "unplugged live"),
        "instrumental" to listOf("instrumental", "伴奏", "offvocal", "off vocal", "纯音乐", "卡拉ok"),
        "remix" to listOf("remix", "混音", "dj", "电音", "舞曲版"),
        "acoustic" to listOf("acoustic", "不插电", "木吉他"),
        "cover" to listOf("cover", "翻唱"),
        "demo" to listOf("demo", "小样"),
        "remaster" to listOf("remaster", "remastered", "重制", "重置")
    )

    /** 候选带了这个版本、原曲没带时的扣分（伴奏/现场混进来是最难听的错误） */
    private val VERSION_PENALTY = mapOf(
        "live" to -14,
        "instrumental" to -24,
        "remix" to -10,
        "acoustic" to -8,
        "cover" to -8,
        "demo" to -6,
        "remaster" to -4
    )

    /** 专辑占位名：没信息量，不能拿来当佐证 */
    private val ALBUM_PLACEHOLDERS = setOf(
        "", "未知专辑", "未知", "unknown", "unknownalbum", "various", "variousartists",
        "网络歌手", "单曲", "无专辑", "未分类", "bilibili", "哔哩哔哩", "哔哩哔哩弹幕网"
    )

    /** 专辑名尾部的版本/介质词：剥掉后同名的视为同一张（豪华版、现场版…） */
    private val ALBUM_EDITION_WORDS = listOf(
        "deluxe edition", "deluxe", "special edition", "remastered", "remaster",
        "live version", "live", "acoustic", "instrumental", "explicit",
        "豪华版", "豪华", "重制版", "重置版", "特别版", "纪念版", "现场版",
        "演唱会版", "完整版", "无损版", "高音质版", "版"
    )

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

        // 版本标记按语义组比对：两边同组（都是 live / 都是伴奏）加一点，
        // 只有单边标了版本才扣分 —— 否则「味道 (live)」对「味道 演唱会现场版」
        // 会互相误杀，明明是同一个版本却匹配不上
        val trackVersion = versionTag(track.title)
        val hitVersion = versionTag(hit.title)
        // 伴奏是「同名的另一条音轨」，不是要找的那首：除非原曲本身就是伴奏，直接淘汰
        if (hitVersion == "instrumental" && trackVersion != "instrumental") return -1
        when {
            trackVersion == null && hitVersion == null -> Unit
            trackVersion != null && trackVersion == hitVersion -> s += 12
            trackVersion == null -> s += VERSION_PENALTY[hitVersion] ?: 0
            hitVersion == null -> s -= 10
            else -> s -= 18
        }

        // 专辑佐证：同名同歌手再对上专辑，几乎可以确认是同一首（占位名不算）
        s += albumScore(hit.album, track.album)

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

    // ───────── 版本标记 ─────────

    /**
     * 标题里的版本标记，返回**语义组名**（没有版本词返回 null）。
     *
     * 返回组名而不是原始词，是为了让 `live` / `现场` / `演唱会` 这类
     * 不同写法能互相认同 —— 本地标签和线上曲库的版本写法几乎从不一致。
     */
    fun versionTag(raw: String): String? {
        val s = raw.lowercase()
        return VERSION_GROUPS.firstOrNull { (_, keys) -> keys.any { containsTag(s, it) } }?.first
    }

    /**
     * 英文版本词按**整词**匹配，中文按包含匹配。
     *
     * 不这么做的话 `Deliverance` 里的 `live`、`Covered` 里的 `cover`
     * 都会被当成版本标记，好歌被莫名其妙扣分。
     */
    private fun containsTag(haystack: String, word: String): Boolean =
        if (word.all { it.code < 128 }) {
            Regex("(?<![a-z])" + Regex.escape(word) + "(?![a-z])").containsMatchIn(haystack)
        } else {
            haystack.contains(word)
        }

    // ───────── 专辑 ─────────

    /** 专辑名有没有信息量（空 / 占位名不能当佐证） */
    fun meaningfulAlbum(raw: String): Boolean {
        val n = normalizeAlbum(raw)
        return n.isNotEmpty() && n !in ALBUM_PLACEHOLDERS
    }

    /**
     * 专辑名归一：去括号内容（`(Deluxe Edition)` 这类常放括号里）、
     * 剥掉尾部版本/介质词、小写、只留字母数字。
     *
     * 「叶惠美 豪华版」和「叶惠美」剥完是同一个名字，
     * 而 `Greatest Hits` 不会被剥成 `Hits`（版本词要整词尾命中）。
     */
    fun normalizeAlbum(raw: String): String {
        var s = raw
            .replace(Regex("[(（\\[【].*?[)）\\]】]"), " ")
            .lowercase()
        // 尾部版本词反复剥（可能连着两个：`live deluxe` / `现场版 重制`）
        var guard = 0
        var changed = true
        while (changed && guard++ < 4) {
            changed = false
            for (w in ALBUM_EDITION_WORDS) {
                if (stripTail(s, w) != null) {
                    s = stripTail(s, w)!!
                    changed = true
                    break
                }
            }
        }
        return s.filter { it.isLetterOrDigit() }
    }

    /**
     * 去掉尾部的一个版本词；命中返回剥好的字符串，没命中返回 null。
     * 英文词要求前面有边界（`Alive` 不能当 `live` 剥）。
     */
    private fun stripTail(value: String, suffix: String): String? {
        val v = value.trimEnd()
        if (v.length <= suffix.length || !v.endsWith(suffix)) return null
        val cut = v.dropLast(suffix.length)
        val boundary = cut.last()
        val asciiSuffix = suffix.all { it.code < 128 }
        if (asciiSuffix && boundary.isLetterOrDigit()) return null
        return cut.trimEnd(' ', '-', '_', '~', '·', '|', '/')
    }

    /** 专辑佐证分：同名 +10，剥掉版本词后同名 +4，无关 0 */
    fun albumScore(hitAlbum: String, trackAlbum: String): Int {
        if (!meaningfulAlbum(hitAlbum) || !meaningfulAlbum(trackAlbum)) return 0
        val a = normalizeAlbum(hitAlbum)
        val b = normalizeAlbum(trackAlbum)
        if (a.isEmpty() || b.isEmpty()) return 0
        return when {
            a == b -> 10
            a.contains(b) || b.contains(a) -> 4
            else -> 0
        }
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
    fun normalizeTitle(raw: String): String {
        val withoutBrackets = raw
            .replace(Regex("[(（\\[【].*?[)）\\]】]"), "")
            // 书名号只剥符号本身：《晴天》是整首歌包了书名号，内容要留
            .replace(Regex("[《》]"), "")
        return stripTrackNumber(withoutBrackets)
            .replace(Regex("[\\s\\-—_·,，.。'\"“”‘’!！?？]"), "")
            .lowercase()
    }

    /**
     * 去掉本地文件常见的开头音轨号：`01. 晴天` / `01 晴天` / `01-晴天`。
     *
     * 只认「带前导零的数字 + 空白」或「数字 + 分隔符」两种写法 ——
     * 否则 `7 Rings`、`24K Magic` 这种以数字开头的歌名会被削成
     * `rings` / `kmagic`，反而更不容易匹配。
     */
    private fun stripTrackNumber(raw: String): String {
        val s = raw.trimStart()
        Regex("^0\\d{1,2}\\s+").find(s)?.let { return s.removeRange(it.range) }
        Regex("^\\d{1,3}[.\\-_、)）]\\s*").find(s)?.let { return s.removeRange(it.range) }
        return s
    }

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
