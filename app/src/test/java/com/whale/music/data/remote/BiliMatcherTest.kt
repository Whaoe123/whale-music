package com.whale.music.data.remote

import com.whale.music.data.model.QsTrack
import com.whale.music.data.model.RemoteSong
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** BiliMatcher 纯逻辑单测：阈值、版本词罚分、归一化保括号内容 */
class BiliMatcherTest {

    private fun hit(title: String, artist: String = "某UP主", durationMs: Long = 216_000) =
        RemoteSong(
            songId = "bili_1", source = "bili", rid = 1L,
            title = title, artist = artist, album = "哔哩哔哩",
            coverUrl = "", duration = durationMs
        )

    private fun track(
        title: String = "紫荆花盛开",
        artist: String = "李荣浩/梁咏琪",
        durationMs: Long = 209_000
    ) = QsTrack(
        id = "t1", title = title, artist = artist,
        album = "", cover = "", duration = durationMs
    )

    @Test
    fun `标题含歌名且时长接近 - 过线`() {
        val s = BiliMatcher.score(
            hit("单曲循环！  李荣浩梁咏琪深情合唱【紫荆花盛开】", "音乐大玩家"),
            track()
        )
        assertTrue("score=$s", s >= BiliMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `标题完全不含歌名 - 淘汰`() {
        val s = BiliMatcher.score(hit("今天也要开心呀 纯音乐合集"), track())
        assertTrue(s < BiliMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `翻唱不提原唱歌手 - 仍可过线（B站翻唱是常态）`() {
        val s = BiliMatcher.score(hit("【紫荆花盛开】超好听翻唱", "路人UP"), track())
        assertTrue("score=$s", s >= BiliMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `伴奏直接淘汰`() {
        val s = BiliMatcher.score(hit("【紫荆花盛开】钢琴伴奏版", "路人UP"), track())
        assertTrue(s < BiliMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `原曲标题自带伴奏时 - 伴奏候选不罚`() {
        val s = BiliMatcher.score(hit("【紫荆花盛开】伴奏", "路人UP"), track(title = "紫荆花盛开 伴奏"))
        assertTrue("score=$s", s >= BiliMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `教学视频重罚`() {
        val s = BiliMatcher.score(hit("紫荆花盛开 吉他教学 入门指法", "路人UP"), track())
        assertTrue(s < BiliMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `时长差五分钟以上 - 扣分`() {
        val s = BiliMatcher.score(hit("【紫荆花盛开】", "路人UP", durationMs = 900_000), track())
        assertTrue(s < BiliMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `归一化保留括号里的歌名`() {
        assertEquals("单曲循环李荣浩合唱紫荆花盛开", BiliMatcher.normalize("单曲循环！李荣浩合唱【紫荆花盛开】"))
    }

    @Test
    fun `coreTitle 去掉版本后缀`() {
        assertEquals("紫荆花盛开", BiliMatcher.coreTitle("紫荆花盛开 (Live)"))
    }

    @Test
    fun `歌名高度相似也可入选但不乱接别的歌`() {
        // 「紫荆花盛开」vs「紫荆花开了」：相似度够 → 不淘汰，但靠加分过不过线另说
        val similar = BiliMatcher.score(hit("紫荆花开了", "路人UP"), track())
        val other = BiliMatcher.score(hit("海阔天空 翻唱", "路人UP"), track())
        assertTrue(similar >= BiliMatcher.ACCEPT_MIN_SCORE)
        assertTrue(other < BiliMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `空歌名不匹配任何候选`() {
        assertEquals(-1, BiliMatcher.score(hit("任意视频"), track(title = "  ")))
    }

    @Test
    fun `normalize 去掉高亮标签`() {
        assertFalse(BiliMatcher.normalize("【<em class=\"keyword\">歌</em>名】").contains("em"))
    }
}
