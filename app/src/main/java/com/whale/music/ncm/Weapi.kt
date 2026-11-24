package com.whale.music.ncm

import java.math.BigInteger
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云网页端同款 `weapi` 加密（红心写入必须走它）。
 *
 * 网页点「红心」发的就是 `POST /weapi/song/like`：
 * - 正文 `JSON` → AES-128-CBC(预设 key) → AES-128-CBC(随机 16 位 key) → base64 得 `params`
 * - 随机 key 反转后用 RSA(公钥 e=010001) 加密 → 256 位十六进制 `encSecKey`
 *
 * 之前写回用的是 `/api/like`，实测那个路径根本不存在（返回 `{"code":404,"接口未找到！"}`），
 * 所以点了爱心只会改本地，网易云那边一点动静都没有。
 */
internal object Weapi {

    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val PUB_KEY_HEX = "010001"

    private const val MODULUS_HEX =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e41" +
            "7629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee25593257" +
            "5cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    private const val KEY_POOL = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** JSON → `params=…&encSecKey=…`（已 URL 编码，可直接当表单正文） */
    fun encrypt(json: String): String = encryptWithSecret(
        json,
        buildString(16) { repeat(16) { append(KEY_POOL.random()) } }
    )

    /** 固定随机 key 版本 —— 纯函数，单测拿它和参考实现逐字节对齐 */
    internal fun encryptWithSecret(json: String, secret: String): String {
        val params = aesBase64(aesBase64(json, PRESET_KEY), secret)
        return "params=" + URLEncoder.encode(params, "UTF-8") +
            "&encSecKey=" + rsaEncrypt(secret)
    }

    private fun aesBase64(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /** 随机 key 先反转，再按十六进制大整数做 RSA，结果补足 256 位 */
    private fun rsaEncrypt(secret: String): String {
        val reversed = secret.reversed().toByteArray(Charsets.UTF_8)
        val base = BigInteger(1, reversed)
        val result = base.modPow(
            BigInteger(PUB_KEY_HEX, 16),
            BigInteger(MODULUS_HEX, 16)
        )
        return result.toString(16).padStart(256, '0')
    }
}
