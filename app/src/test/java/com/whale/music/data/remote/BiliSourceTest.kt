package com.whale.music.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B 站搜索的 WBI 签名与时长解析 —— 纯函数单测。
 *
 * 签名算错不会抛异常，只会得到 `code=-352`，表现为「B 站音源全部没结果」，
 * 所以这里用一组固定密钥/时间戳锁死输出（期望值由 B 站前端算法独立算出）。
 */
class BiliSourceTest {

    private val img = "7cd08494abcdef0123456789abcdef01"
    private val sub = "4932caffabcdef0123456789abcdef02"

    @Test
    fun `mixin key 是密钥重排后的前 32 位`() {
        assertEquals("014d7a9414c1c0f2dd439bc5fe0fefbf", BiliSource.wbiMixinKey(img, sub))
        assertEquals(32, BiliSource.wbiMixinKey(img, sub).length)
    }

    @Test
    fun `签名与浏览器算法逐字节一致`() {
        val signed = BiliSource.signWbi(
            mapOf("search_type" to "video", "keyword" to "瞬 郑润泽", "page" to "1"),
            img, sub, 1_700_000_000L
        )
        assertEquals(
            "keyword=%E7%9E%AC%20%E9%83%91%E6%B6%A6%E6%B3%BD&page=1&search_type=video" +
                "&wts=1700000000&w_rid=7873a32af39d21b3d50d7051c484bf37",
            signed
        )
    }

    @Test
    fun `编码按 encodeURIComponent - 空格是百分号 20 不是加号`() {
        assertEquals("%E7%9E%AC", BiliSource.wbiEncode("瞬"))
        assertEquals("a%20b", BiliSource.wbiEncode("a b"))
        assertEquals("a-b_c.d~e", BiliSource.wbiEncode("a-b_c.d~e"))
    }

    @Test
    fun `时长支持 mm ss 与 hh mm ss`() {
        assertEquals(285_000L, BiliSource.parseDurationMs("4:45"))
        assertEquals(3_723_000L, BiliSource.parseDurationMs("1:02:03"))
        assertEquals(285_000L, BiliSource.parseDurationMs(285))
        assertEquals(285_000L, BiliSource.parseDurationMs("285"))
        assertEquals(0L, BiliSource.parseDurationMs(null))
    }
}