package com.whale.music.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词纯逻辑单测：LRC 时间戳解析 + 本地 .lrc 编码识别。
 * 网络部分（网易 / 酷我 / lx）不在这里测，靠真机验证。
 */
class LyricSourceTest {

    // ───────── parseLrc ─────────

    @Test
    fun `parses common timestamp forms to milliseconds`() {
        val lrc = """
            [00:12.34]两位小数
            [00:13.345]三位小数
            [00:14]无小数
            [01:02:03]酷我冒号风格
        """.trimIndent()

        val lines = LyricSource.parseLrc(lrc)

        assertEquals(4, lines.size)
        assertEquals(12_340L, lines[0].timeMs)
        assertEquals("两位小数", lines[0].text)
        assertEquals(13_345L, lines[1].timeMs)
        assertEquals(14_000L, lines[2].timeMs)
        // [mm:ss:xx] 里最后一段当小数：01:02 分钟 + 0.03 秒
        assertEquals(62_030L, lines[3].timeMs)
    }

    @Test
    fun `one line with several timestamps expands and stays sorted`() {
        val lrc = "[00:30.00][00:10.00]重复副歌"

        val lines = LyricSource.parseLrc(lrc)

        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].timeMs)
        assertEquals(30_000L, lines[1].timeMs)
        assertTrue(lines.all { it.text == "重复副歌" })
    }

    @Test
    fun `skips metadata and untimed lines`() {
        val lrc = """
            [ti:歌名]
            [ar:歌手]
            这一行没有时间戳
            [00:20.00]真正的词
        """.trimIndent()

        val lines = LyricSource.parseLrc(lrc)

        assertEquals(1, lines.size)
        assertEquals("真正的词", lines[0].text)
    }

    // ───────── decodeLrc（本地 .lrc 编码） ─────────

    @Test
    fun `decodes plain utf8`() {
        val text = "[00:01.00]晴天"
        assertEquals(text, LyricSource.decodeLrc(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `strips utf8 bom`() {
        val text = "[00:01.00]晴天"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            text.toByteArray(Charsets.UTF_8)
        assertEquals(text, LyricSource.decodeLrc(bytes))
    }

    @Test
    fun `falls back to gb18030 for legacy lrc`() {
        val text = "[00:01.00]晴天 周杰伦"
        val bytes = text.toByteArray(charset("GB18030"))
        assertEquals(text, LyricSource.decodeLrc(bytes))
    }

    @Test
    fun `decodes utf16 little endian with bom`() {
        val text = "[00:01.00]晴天"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, LyricSource.decodeLrc(bytes))
    }
}