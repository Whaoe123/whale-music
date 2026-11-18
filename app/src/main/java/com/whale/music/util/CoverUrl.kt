package com.whale.music.util

/**
 * 封面地址归一化。
 *
 * 各数据源返回的封面常常是缩略图：
 * - 酷我 `web_albumpic_short` 形如 `120/s4s0/93/xxx.jpg`，120 就是 120×120 缩略图；
 * - 网易 `picUrl` 有时带 `?param=130y130` 之类的小尺寸参数。
 *
 * 播放页封面接近 1000px 宽，用缩略图放大必然发糊，所以统一在入库前升级成高清地址。
 */
object CoverUrl {

    /** 酷我封面尺寸段：实测 800 可用（1000 亦可，体积翻倍，800 足够） */
    private const val KUWO_SIZE = "800"

    /** 网易封面统一取 800×800 */
    private const val WY_PARAM = "800y800"

    private val KUWO_SIZE_SEGMENT = Regex("albumcover/\\d+/")

    fun hd(raw: String?): String {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return ""
        return when {
            url.contains("kuwo.cn/star/albumcover/") ->
                url.replace(KUWO_SIZE_SEGMENT) { "albumcover/$KUWO_SIZE/" }

            url.contains("music.126.net") -> {
                val base = url.substringBefore('?')
                "$base?param=$WY_PARAM"
            }

            else -> url
        }
    }
}
