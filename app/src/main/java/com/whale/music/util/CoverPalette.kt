package com.whale.music.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 封面取色 — 从封面 URL 抽取主色调，用于播放页动态氛围背景。
 *
 * 自包含实现（不引 androidx.palette 依赖）：
 * 1. HttpURLConnection 拉封面字节（8s 超时，跟随重定向）
 * 2. BitmapFactory 降采样解码到 ~48px
 * 3. 32 级色桶投票取最高频桶，桶内平均得到平滑主色
 * 4. 提亮 + 饱和度微调，保证在深色氛围背景上可见
 *
 * 结果按 URL 小缓存（24 条），切歌不重复拉图。
 */
object CoverPalette {
    private const val TARGET_PX = 48
    private const val BUCKET_BITS = 3 // 32 级/通道
    private const val CACHE_CAP = 24

    private val cache = object : LinkedHashMap<String, Color>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?): Boolean =
            size > CACHE_CAP
    }

    /** 取封面主色调；失败/无封面返回 null（调用方用主题色兜底）。 */
    suspend fun dominant(url: String?): Color? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        synchronized(cache) { cache[url] }?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { extract(url) }.getOrNull()?.also { color ->
                synchronized(cache) { cache[url] = color }
            }
        }
    }

    private const val BAND_COUNT = 8

    private val bandCache = object : LinkedHashMap<String, List<Color>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Color>>?): Boolean =
            size > 12
    }

    /** 封面自上而下 8 段均色（歌词亮度自适应的背景剖面原料）。 */
    suspend fun bandColors(url: String?): List<Color>? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        synchronized(bandCache) { bandCache[url] }?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = fetchBytes(url) ?: return@runCatching null
                val bitmap = decodeSmall(bytes) ?: return@runCatching null
                val bands = extractBands(bitmap)
                if (bands != null) {
                    synchronized(bandCache) { bandCache[url] = bands }
                }
                bands
            }.getOrNull()
        }
    }

    private fun extractBands(bitmap: Bitmap): List<Color>? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        val out = ArrayList<Color>(BAND_COUNT)
        for (band in 0 until BAND_COUNT) {
            val y0 = h * band / BAND_COUNT
            val y1 = h * (band + 1) / BAND_COUNT
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0L
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    val px = pixels[y * w + x]
                    r += android.graphics.Color.red(px)
                    g += android.graphics.Color.green(px)
                    b += android.graphics.Color.blue(px)
                    n++
                }
            }
            if (n == 0L) return null
            out.add(Color((r / n).toInt() / 255f, (g / n).toInt() / 255f, (b / n).toInt() / 255f))
        }
        return out
    }

    private fun extract(url: String): Color? {
        val bytes = fetchBytes(url) ?: return null
        val bitmap = decodeSmall(bytes) ?: return null
        return pickDominant(bitmap)
    }

    private fun fetchBytes(url: String): ByteArray? {
        var conn = URL(url).openConnection() as? HttpURLConnection ?: return null
        repeat(3) {
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)")
                if (conn.responseCode in 200..299) {
                    return conn.inputStream.use { it.readBytes() }
                }
                // 3xx 且没被 HttpURLConnection 自动跟随时手动跟一次
                val loc = conn.getHeaderField("Location")
                if (conn.responseCode in 300..399 && !loc.isNullOrBlank()) {
                    val base = conn.url
                    conn.disconnect()
                    conn = URL(base, loc).openConnection() as? HttpURLConnection ?: return null
                    return@repeat
                }
                conn.disconnect()
                return null
            } catch (_: Exception) {
                conn.disconnect()
                return null
            }
        }
        return null
    }

    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= TARGET_PX) sample *= 2
        val decode = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565 // 取色够用，省内存
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode)
    }

    private fun pickDominant(bitmap: Bitmap): Color? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()

        val shift = 8 - BUCKET_BITS
        val counts = HashMap<Int, Int>(64)
        val sums = HashMap<Int, LongArray>(64)
        for (px in pixels) {
            if (AndroidColor.alpha(px) < 128) continue
            val r = AndroidColor.red(px)
            val g = AndroidColor.green(px)
            val b = AndroidColor.blue(px)
            val key = ((r shr shift) shl (BUCKET_BITS * 2)) or
                ((g shr shift) shl BUCKET_BITS) or
                (b shr shift)
            counts[key] = (counts[key] ?: 0) + 1
            val sum = sums.getOrPut(key) { LongArray(3) }
            sum[0] += r.toLong()
            sum[1] += g.toLong()
            sum[2] += b.toLong()
        }
        val best = counts.entries.maxByOrNull { it.value } ?: return null
        val n = best.value.toLong()
        val sum = sums[best.key] ?: return null
        val r = (sum[0] / n).toInt()
        val g = (sum[1] / n).toInt()
        val b = (sum[2] / n).toInt()
        return beautify(Color(r / 255f, g / 255f, b / 255f))
    }

    /** 提亮并轻微加饱和，避免深色封面取出来的主色在暗背景上发黑。 */
    private fun beautify(c: Color): Color {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        val sat = if (max > 0f) (max - min) / max else 0f
        // 太灰的主色不做鲜艳化（灰绿背景本就是氛围），只保证亮度
        val boost = if (sat < 0.12f) 0f else 0.15f
        val lifted = Color(
            red = c.red + (1f - c.red) * (0.30f + boost),
            green = c.green + (1f - c.green) * (0.30f + boost),
            blue = c.blue + (1f - c.blue) * (0.30f + boost),
            alpha = 1f
        )
        return lifted
    }
}
