package com.whale.music.data.remote

import com.whale.music.data.model.QsTrack
import com.whale.music.data.model.RemoteSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SongMatcher 纯函数单测 —— 覆盖「同名不同歌手 / 同名异版本」两类最容易播错歌的场景，
 * 以及检索词构造、规范化与阈值行为。改打分规则前先跑这里。
 */
class SongMatcherTest {

    private fun track(
        title: String,
        artist: String = "",
        duration: Long = 0L,
        id: String = "t1"
    ) = QsTrack(id = id, title = title, artist = artist, album = "", cover = "", duration = duration)

    private fun hit(
        title: String,
        artist: String = "",
        duration: Long = 0L,
        rid: Long = 1L
    ) = RemoteSong(
        songId = "kw_$rid", source = "kw", rid = rid,
        title = title, artist = artist, album = "", coverUrl = "", duration = duration
    )

    // ───────── ① 歌手硬门 ─────────

    @Test
    fun `same title but different artist is rejected`() {
        val s = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "林俊杰", duration = 269_000)
        )
        assertEquals(-1, s)
    }

    @Test
    fun `same title same artist scores high enough to accept`() {
        val s = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        assertTrue(s >= SongMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `artist overlap via contains still passes the gate`() {
        val s = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦/Jay Chou", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        assertTrue(s >= SongMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `placeholder artist does not gate`() {
        // 「未知歌手」是占位词：不能拿它去否决同名候选
        val s = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "未知歌手", duration = 269_000)
        )
        assertTrue(s >= SongMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `missing artist on either side does not gate`() {
        val noArtistHit = SongMatcher.score(
            hit(title = "晴天", artist = "", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        val noArtistTrack = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "", duration = 269_000)
        )
        assertTrue(noArtistHit >= SongMatcher.ACCEPT_MIN_SCORE)
        assertTrue(noArtistTrack >= SongMatcher.ACCEPT_MIN_SCORE)
    }

    // ───────── ② 时长硬门 ─────────

    @Test
    fun `duration differs more than thirty seconds is rejected`() {
        // 同名同歌手但 2:30 vs 5:00 —— 典型的 Live / 加长版，直接淘汰
        val s = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦", duration = 300_000),
            track(title = "晴天", artist = "周杰伦", duration = 150_000)
        )
        assertEquals(-1, s)
    }

    @Test
    fun `duration difference exactly at threshold is allowed`() {
        val s = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 239_000)
        )
        assertEquals(30_000L, 269_000L - 239_000L)
        assertTrue(s >= SongMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `duration difference one over threshold is rejected`() {
        val s = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 238_999)
        )
        assertEquals(-1, s)
    }

    @Test
    fun `unknown duration does not gate`() {
        val s = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦", duration = 0),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        assertTrue(s >= SongMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `duration soft tiers reward closeness`() {
        val close = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 268_000)
        )
        val medium = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 264_000)
        )
        val far = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 255_000)
        )
        assertTrue(close > medium)
        assertTrue(medium > far)
        assertEquals(12, close - medium)     // <3s 档 +22 与 <10s 档 +10 的差
        assertEquals(10, medium - far)       // <10s 档 +10 与 10~30s 档 +0 的差
    }

    // ───────── ③ 歌名一致度 ─────────

    @Test
    fun `unrelated title is rejected`() {
        val s = SongMatcher.score(
            hit(title = "七里香", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        assertEquals(-1, s)
    }

    @Test
    fun `empty title on either side is rejected`() {
        assertEquals(-1, SongMatcher.score(hit(title = ""), track(title = "晴天", artist = "周杰伦")))
        assertEquals(-1, SongMatcher.score(hit(title = "晴天"), track(title = "")))
    }

    @Test
    fun `contained title only reaches base tier`() {
        // 候选「晴天(Live)」被规范化后仍包含原名，但原版没标 Live 会被扣分
        val withSuffix = SongMatcher.score(
            hit(title = "晴天 (Live)", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        assertTrue(withSuffix >= SongMatcher.ACCEPT_MIN_SCORE)
        // 同名同版本完全一致的分数必然更高
        val exact = SongMatcher.score(
            hit(title = "晴天", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        assertTrue(exact > withSuffix)
    }

    @Test
    fun `bare similarity alone is below acceptance`() {
        // 「晴天」vs「青天」只有字符相似：6 分档，够不到 20 分阈值
        val s = SongMatcher.score(
            hit(title = "青天", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        assertTrue(s < SongMatcher.ACCEPT_MIN_SCORE)
    }

    // ───────── ④ 版本后缀罚分 ─────────

    @Test
    fun `live tag on hit is penalized when track has no tag`() {
        val s = SongMatcher.score(
            hit(title = "晴天 (Live)", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        // 40 + 0(标题原样不等) - 14 + 26 + 22
        assertEquals(74, s)
    }

    @Test
    fun `instrumental tag is heavily penalized`() {
        val s = SongMatcher.score(
            hit(title = "晴天 伴奏", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        // 20(规范化后包含原名) - 24 + 26 + 22
        assertEquals(44, s)
    }

    @Test
    fun `track already tagged live does not get hit for live version`() {
        val s = SongMatcher.score(
            hit(title = "晴天 (Live)", artist = "周杰伦", duration = 269_000),
            track(title = "晴天 (Live)", artist = "周杰伦", duration = 269_000)
        )
        assertTrue(s >= SongMatcher.ACCEPT_MIN_SCORE)
    }

    @Test
    fun `remix on hit is penalized when track is original`() {
        val s = SongMatcher.score(
            hit(title = "晴天 (Remix)", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        // 40 - 10 + 26 + 22
        assertEquals(78, s)
    }

    @Test
    fun `cover version is penalized`() {
        val s = SongMatcher.score(
            hit(title = "晴天 翻唱", artist = "周杰伦", duration = 269_000),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        // 20(规范化后包含原名) - 8 + 26 + 22
        assertEquals(60, s)
    }

    // ───────── ⑤ pickBest 选优与阈值 ─────────

    @Test
    fun `pickBest returns null when nothing reaches threshold`() {
        val best = SongMatcher.pickBest(
            listOf(
                hit(title = "青天", artist = "周杰伦", duration = 269_000),
                hit(title = "别的歌", artist = "周杰伦", duration = 269_000)
            ),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        assertNull(best)
    }

    @Test
    fun `pickBest returns the highest scoring candidate`() {
        val wrongArtist = hit(title = "晴天", artist = "林俊杰", duration = 269_000, rid = 1)
        val rightOne = hit(title = "晴天", artist = "周杰伦", duration = 269_000, rid = 2)
        val best = SongMatcher.pickBest(
            listOf(wrongArtist, rightOne),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        assertNotNull(best)
        assertEquals("kw_2", best!!.first.songId)
        assertEquals(SongMatcher.score(rightOne, track(title = "晴天", artist = "周杰伦", duration = 269_000)), best.second)
    }

    @Test
    fun `pickBest ignores rejected candidates even if alone`() {
        val best = SongMatcher.pickBest(
            listOf(hit(title = "晴天", artist = "林俊杰", duration = 269_000)),
            track(title = "晴天", artist = "周杰伦", duration = 269_000)
        )
        assertNull(best)
    }

    // ───────── ⑥ 检索词构造 ─────────

    @Test
    fun `queries put artist first when it is meaningful`() {
        val qs = SongMatcher.queries(track(title = "晴天", artist = "周杰伦"))
        assertEquals("晴天 周杰伦", qs[0])
        assertTrue("晴天" in qs)
    }

    @Test
    fun `queries skip placeholder artist`() {
        val qs = SongMatcher.queries(track(title = "晴天", artist = "未知歌手"))
        assertTrue(qs.none { it.contains("未知歌手") })
        assertTrue("晴天" in qs)
    }

    @Test
    fun `queries strip bracket suffix as third attempt`() {
        val qs = SongMatcher.queries(track(title = "晴天 (Live)", artist = "周杰伦"))
        assertEquals("晴天 (Live) 周杰伦", qs[0])
        assertTrue("晴天 (Live)" in qs)
        assertTrue("晴天 周杰伦" in qs)
    }

    @Test
    fun `queries are distinct`() {
        val qs = SongMatcher.queries(track(title = "晴天", artist = ""))
        assertEquals(qs.distinct(), qs)
    }

    @Test
    fun `queries of blank title are empty`() {
        assertTrue(SongMatcher.queries(track(title = "", artist = "周杰伦")).isEmpty())
    }

    // ───────── ⑦ 工具函数 ─────────

    @Test
    fun `meaningfulArtist rejects blanks and placeholders`() {
        assertFalse(SongMatcher.meaningfulArtist(""))
        assertFalse(SongMatcher.meaningfulArtist("未知歌手"))
        assertFalse(SongMatcher.meaningfulArtist("Various Artists"))
        assertFalse(SongMatcher.meaningfulArtist("群星"))
        assertTrue(SongMatcher.meaningfulArtist("周杰伦"))
    }

    @Test
    fun `splitArtists splits separators and drops placeholders`() {
        val parts = SongMatcher.splitArtists("周杰伦/未知歌手/五月天 & 田馥甄")
        assertEquals(listOf("周杰伦", "五月天", "田馥甄"), parts)
    }

    @Test
    fun `normalizeTitle strips brackets punctuation and case`() {
        assertEquals("晴天", SongMatcher.normalizeTitle("晴天 (Live)"))
        assertEquals("晴天", SongMatcher.normalizeTitle("《晴天》"))
        assertEquals("晴天周杰伦", SongMatcher.normalizeTitle("晴天 - 周杰伦"))
        assertEquals("晴天", SongMatcher.normalizeTitle("  晴  天 "))
    }

    @Test
    fun `artistsOverlap handles containment both ways`() {
        val want = SongMatcher.splitArtists("周杰伦")
        assertTrue(SongMatcher.artistsOverlap("周杰伦/Jay Chou", want))
        assertFalse(SongMatcher.artistsOverlap("林俊杰", want))
        assertTrue(SongMatcher.artistsOverlap("任意", emptyList()))
    }

    @Test
    fun `similar requires sixty percent characters`() {
        assertTrue(SongMatcher.similar("abc", "abcdef"))
        assertFalse(SongMatcher.similar("abc", "xyz"))
    }

    @Test
    fun `accept threshold sits below exact title score`() {
        assertTrue(SongMatcher.ACCEPT_MIN_SCORE < SongMatcher.EXACT_TITLE_SCORE)
        assertTrue(SongMatcher.DURATION_MAX_DIFF_MS > 0)
    }
}
