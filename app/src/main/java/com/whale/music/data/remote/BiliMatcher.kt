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

    /** 标题包含歌名的基础分 */
    const val EXACT_TITLE_SCORE = 40

    /** 歌名只是「像」的基础分（要靠歌手等加分过线） */
    private const val SIMILAR_TITLE_SCORE = 14

    /** 不是歌本身 —— 原曲没标伴奏时直接淘汰 */
    private val BAD_ACCOMPANIMENT_HARD = listOf("伴奏", "纯音乐", "instrumental")
    private val BAD_ACCOMPANIMENT_SOFT = listOf("钢琴版", "吉他版")
    private val BAD_TEACH = listOf("教学", "教程", "入门", "指法", "乐谱", "简谱", "五线谱")
    private val BAD_REMIX = listOf("remix", "dj", "蹦迪", "夜店", "电音")
    private val BAD_CLIP = listOf("铃声", "剪辑", "卡点", "15秒", "30秒", "60秒", "片段", "截取", "片段")
    private val LIVE_WORDS = listOf("live", "现场")

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
        if (!hasAny(wtLow, BAD_ACCOMPANIMENT_HARD) && hasAny(htLow, BAD_ACCOMPANIMENT_HARD)) return -1
        if (hasAny(htLow, BAD_TEACH)) return -1
        if (!hasAny(wtLow, BAD_ACCOMPANIMENT_SOFT) && hasAny(htLow, BAD_ACCOMPANIMENT_SOFT)) s -= 22
        if (!hasAny(wtLow, BAD_REMIX) && hasAny(htLow, BAD_REMIX)) s -= 12
        if (hasAny(htLow, BAD_CLIP)) s -= 16
        if (!hasAny(wtLow, LIVE_WORDS) && hasAny(htLow, LIVE_WORDS)) s -= 6

        // 官方 MV / 完整版更可能是想要的那个版本
        if (hasAny(htLow, listOf("官方", "mv", "完整版"))) s += 4

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
