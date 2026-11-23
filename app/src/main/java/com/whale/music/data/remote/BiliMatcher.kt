package com.whale.music.data.remote

import com.whale.music.data.model.QsTrack
import com.whale.music.data.model.RemoteSong
import kotlin.math.abs

/**
 * B 站候选打分 —— 纯函数，可单测（见 BiliMatcherTest）。
 *
 * 和 [SongMatcher] 的严苛不同：B 站条目是「视频」不是曲库歌曲，
 * 标题是长句（「单曲循环！XXX深情合唱【歌名】」），歌手还常是翻唱者。
 * 所以规则放宽为：
 * 1. 标题里必须出现歌名（或高度相似），否则淘汰 —— 这条不放；
 * 2. 原唱歌手出现在标题 / UP主名里加分，不出现只小扣（翻唱是常态）；
 * 3. 伴奏 / 纯音乐 / 教学直接淘汰；剪辑片段 / DJ 版等重罚；
 * 4. 时长贴近原曲加分，差太多（合集 / 直播回放）扣分。
 * 低于 [ACCEPT_MIN_SCORE] 的宁可不播。
 */
object BiliMatcher {

    /** 命中阈值：低于此分宁可不播，也不端一个别的视频上来 */
    const val ACCEPT_MIN_SCORE = 24

    /**
     * **可插队阈值**：B 站在音源顺序里排在正式音源前面，所以它一旦"认得"就会抢先出声。
     * 只有分数达到这个线的命中才值得占用播放位；达不到就当没搜到，
     * 让后面的酷我 / lx 接手（那些源给的通常是完整正版）。
     *
     * 24 分只够证明"标题里有歌名"，50 分才要求歌手或时长对上：
     * 40（只有歌名）+ 0（没歌手没时长）过不了，40+18（时长对上）能过。
     */
    const val TRUST_MIN_SCORE = 50

    /** 标题包含歌名的基础分 */
    const val EXACT_TITLE_SCORE = 40

    /** 歌名只是「像」的基础分（要靠歌手等加分过线） */
    private const val SIMILAR_TITLE_SCORE = 14

    /** 不是歌本身 —— 原曲没标伴奏时直接淘汰 */
    private val BAD_ACCOMPANIMENT_HARD = listOf("伴奏", "纯音乐", "instrumental")
    private val BAD_ACCOMPANIMENT_SOFT = listOf("钢琴版", "吉他版")

    /**
     * 内容根本不是这首歌：乐器演奏 / 谱子 / 音高分析 / 消音。
     *
     * 这些视频标题里同样带歌名和原唱，时长还和原曲几乎一样，
     * 以前只靠分数区分不出来 —— 实测搜「瞬 郑润泽」，
     * 排第一的 `【钢琴】我用钢琴手搓出郑润泽《瞬》的完整版这件事`
     * 因为标题里有「完整版」拿到加分，**压过了正片**，一播就是一曲钢琴。
     * 原曲标题本身带这些词（纯音乐碟、伴奏碟）时不淘汰。
     */
    private val BAD_INSTRUMENTAL = listOf(
        "钢琴", "指弹", "鼓谱", "吉他", "尤克里里", "古筝", "小提琴", "大提琴",
        "二胡", "琵琶", "笛子", "架子鼓", "口琴", "演奏", "手搓", "音高",
        "翻弹", "消音", "piano", "guitar"
    )
    private val BAD_TEACH = listOf("教学", "教程", "入门", "指法", "乐谱", "简谱", "五线谱")
    private val BAD_REMIX = listOf("remix", "dj", "蹦迪", "夜店", "电音")
    private val BAD_CLIP = listOf("铃声", "剪辑", "卡点", "15秒", "30秒", "60秒", "片段", "截取", "片段")
    private val LIVE_WORDS = listOf("live", "现场", "直拍", "演唱会")

    fun score(hit: RemoteSong, track: QsTrack): Int {
        val wt = coreTitle(track.title)
        if (wt.isEmpty()) return -1
        val ht = normalize(hit.title)
        if (ht.isEmpty()) return -1

        val titleContains = ht.contains(wt)
        if (!titleContains && !SongMatcher.similar(ht, wt)) return -1

        var s = if (titleContains) EXACT_TITLE_SCORE else SIMILAR_TITLE_SCORE

        // 歌手：出现在标题 / UP主名里加分；完全不提小扣但不淘汰（B 站翻唱多）
        val artists = SongMatcher.splitArtists(track.artist).filter { it.length >= 2 }
        if (artists.isNotEmpty()) {
            val hay = normalize(hit.title + " " + hit.artist)
            s += if (artists.any { hay.contains(it) }) 14 else -6
        }

        val htLow = hit.title.lowercase()
        val wtLow = track.title.lowercase()
        // 版本按语义组比对：B 站标题里「现场 / Live / 演唱会」写法五花八门，
        // 按字面判会把同版本的投稿当杂质扣分（原曲本来就是 live 时尤其明显）
        val trackVersion = SongMatcher.versionTag(track.title)
        val hitVersion = SongMatcher.versionTag(hit.title)
        val sameVersion = trackVersion != null && trackVersion == hitVersion
        if (!sameVersion && hasAny(htLow, BAD_ACCOMPANIMENT_HARD)) return -1
        if (hasAny(htLow, BAD_TEACH)) return -1
        if (hasAny(htLow, BAD_INSTRUMENTAL) && !hasAny(wtLow, BAD_INSTRUMENTAL)) return -1
        if (!sameVersion) {
            if (!hasAny(wtLow, BAD_ACCOMPANIMENT_SOFT) && hasAny(htLow, BAD_ACCOMPANIMENT_SOFT)) s -= 22
            if (!hasAny(wtLow, BAD_REMIX) && hasAny(htLow, BAD_REMIX)) s -= 12
            if (!hasAny(wtLow, LIVE_WORDS) && hasAny(htLow, LIVE_WORDS)) s -= 6
        } else {
            s += 8
        }
        if (hasAny(htLow, BAD_CLIP)) s -= 16

        // 只认「官方」这种强信号。以前把「完整版 / MV」也算加分，
        // 结果「【钢琴】…手搓出《瞬》的完整版这件事」反而压过正片
        if (hasAny(htLow, listOf("官方", "official"))) s += 4

        // 时长软加分：B 站视频常带片头片尾，放宽到 30s
        if (track.duration > 0 && hit.duration > 0) {
            val diff = abs(hit.duration - track.duration)
            s += when {
                diff <= 30_000 -> 18
                diff <= 90_000 -> 8
                diff > 300_000 -> -12
                else -> 0
            }
            // 合集 / 直播回放（超过 20 分钟）不是单曲
            if (hit.duration > 20 * 60_000) s -= 20
        }
        return s
    }

    private fun hasAny(raw: String, words: List<String>): Boolean = words.any { raw.contains(it) }

    /** 核心歌名：去掉括号版本后缀再归一化（「歌(Live)」→「歌」） */
    fun coreTitle(raw: String): String = normalize(
        raw.replace(Regex("[(（\\[【].*?[)）\\]】]"), "")
    )

    /**
     * 归一化：去高亮标签、去括号符号但**保留括号里的内容**
     * （B 站标题爱把歌名包进【】里，剥掉内容就匹配不上了）。
     */
    fun normalize(raw: String): String = raw
        .replace(Regex("<[^>]*>"), "")
        .replace(Regex("[《》【】\\[\\]（）()「」]"), "")
        .replace(Regex("[\\s\\-—_·,，.。'\"“”‘’!！?？:：;；、~～]"), "")
        .lowercase()
}
