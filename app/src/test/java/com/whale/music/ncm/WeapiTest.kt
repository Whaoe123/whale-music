package com.whale.music.ncm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * weapi 加密逐字节校验。
 *
 * 期望值由参考实现（Python + pycryptodome，同一套 preset key / IV / RSA 模数）
 * 对固定 JSON 与固定随机 key 独立算出：`AES(CBC, preset) → AES(CBC, secret) → base64`，
 * `encSecKey` 是 secret 反转后的 RSA。
 *
 * 之前红心写不回去，就是因为接口路径错了（/api/like 返回 404）；加密再错一次
 * 就会得到 code 400 而不是 301，这里把它钉死在单测里。
 */
class WeapiTest {

    @Test
    fun `params 与 encSecKey 和参考实现一致`() {
        val json = """{"csrf_token":"","trackId":186001,"like":"true","timestamp":1700000000000}"""
        val body = Weapi.encryptWithSecret(json, "1234567890abcdef")

        val params = body.substringAfter("params=").substringBefore("&encSecKey=")
        val encSecKey = body.substringAfter("&encSecKey=")

        assertEquals(
            "mxmKo6zvEizoahgflf3DYqBOcZpuAOTazqLPgrHVlsuT7W47+sowTe/DXrpV5ywL6zFeiy8i3aOEYkX6NBnWlYSUodGOdu5lLcBiDpSlbD5ZiTp01M6awmwbl8zV1fK0AznrbrpjQTlIZJqB6g6iNw==",
            java.net.URLDecoder.decode(params, "UTF-8")
        )
        assertEquals(
            "bc05a756cb87b1a2674efda9e90a27d641dcbbe2e6b7be3b61193e47315c298183d409578ac80502bd441d82bf1611d6d9238bcaf64794d096417b726373fdd8cf4a9e9d31d198d6d785ffa3d0a2e8c621b4d6c019fabe0ca6f3a815d9f2a4875a0bcab968f331f394566e1162603ed1dd002aa89b47fd8dcbc77d2c6976efed",
            encSecKey
        )
    }

    @Test
    fun `随机 key 版本每次不同且长度正确`() {
        val json = """{"trackId":1}"""
        val a = Weapi.encrypt(json)
        val b = Weapi.encrypt(json)
        assertTrue(a != b)
        assertEquals(256, a.substringAfter("&encSecKey=").length)
    }
}
