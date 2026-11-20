package com.whale.music.ui.lyrics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/*
 * 歌词亮度自适应 — 对比度数学部分移植自 Sonance（gxslcl/Sonance，MIT License，
 * Copyright (c) 2026 chenlucc），背景剖面按 Whale 的模糊封面+遮罩模型重建。
 * 纯 Kotlin，可单测。
 */

/** WCAG 相对亮度对比度。 */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return max(la, lb) / min(la, lb)
}

/** RGB → HSV（h: 0..360, s: 0..1, v: 0..1） */
internal fun rgbToHsv(c: Color): FloatArray {
    val r = c.red
    val g = c.green
    val b = c.blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val h = when {
        delta == 0f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    val s = if (maxC == 0f) 0f else delta / maxC
    return floatArrayOf(if (h < 0) h + 360f else h, s, maxC)
}

internal fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val hh = ((h % 360f) + 360f) % 360f
    val c = v * s
    val x = c * (1 - kotlin.math.abs((hh / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        hh < 60f -> Triple(c, x, 0f)
        hh < 120f -> Triple(x, c, 0f)
        hh < 180f -> Triple(0f, c, x)
        hh < 240f -> Triple(0f, x, c)
        hh < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

private fun walk(h: Float, s: Float, v0: Float, lighten: Boolean, t: Float): Color {
    val v = if (lighten) v0 + (1f - v0) * t else v0 * (1f - t)
    return hsvToColor(h, s * (1f - t * 0.45f), v)
}

/**
 * 保证 [fg] 在 [bg] 上可读：不足 [target] 对比度时沿 HSV 往白/黑方向修正。
 * [lighten] 由所在区域背景决定：偏暗背景提亮，偏亮背景压暗。
 */
fun ensureReadable(fg: Color, bg: Color, target: Float = 3.2f, lighten: Boolean): Color {
    if (contrastRatio(fg, bg) >= target) return fg
    val (h, s, v) = rgbToHsv(fg)
    var best = fg
    var bestRatio = contrastRatio(fg, bg)
    for (i in 1..12) {
        val cand = walk(h, s, v, lighten, i / 12f)
        val r = contrastRatio(cand, bg)
        if (r > bestRatio) {
            bestRatio = r
            best = cand
        }
        if (r >= target) return cand
    }
    return best
}

/**
 * 背景纵向剖面：一列采样色（上→下），
 * [at] 按纵向位置插值得到该处背景色。
 */
class BgProfile(private val colors: List<Color>, val lighten: Boolean) {
    fun at(fraction: Float): Color {
        if (colors.isEmpty()) return Color.Black
        if (colors.size == 1) return colors[0]
        val f = fraction.coerceIn(0f, 1f) * (colors.size - 1)
        val i = f.toInt().coerceIn(0, colors.size - 2)
        val t = f - i
        return lerpColor(colors[i], colors[i + 1], t)
    }
}

fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    a.red + (b.red - a.red) * t,
    a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t
)

/**
 * 由封面色带 + Whale 背景模型（饱和×2、0.9 叠黑、0.26 黑纱、底部 42% 渐变压暗）
 * 重建背景剖面。色带是封面图自上而下 8 段均色。
 */
fun buildBgProfile(bands: List<Color>): BgProfile? {
    if (bands.isEmpty()) return null
    val modeled = bands.mapIndexed { index, band ->
        // 饱和度 ×2（ColorMatrix.setToSaturation(2.0) 的近似）
        val grey = (band.red + band.green + band.blue) / 3f
        val sat = Color(
            (grey + (band.red - grey) * 2f).coerceIn(0f, 1f),
            (grey + (band.green - grey) * 2f).coerceIn(0f, 1f),
            (grey + (band.blue - grey) * 2f).coerceIn(0f, 1f)
        )
        // 0.9 叠黑 + 0.26 黑纱
        var c = lerpColor(Color.Black, sat, 0.9f)
        c = lerpColor(Color.Black, c, 0.74f)
        // 底部 42% 渐变压暗（映射到屏幕纵向 58% 以下区域加重）
        val frac = index / (bands.size - 1).coerceAtLeast(1).toFloat()
        if (frac > 0.55f) {
            val t = ((frac - 0.55f) / 0.45f) * 0.6f
            c = lerpColor(Color.Black, c, 1f - t)
        }
        c
    }
    // 全局方向：多数位置偏暗 → 文字默认提亮
    val avgLum = modeled.map { it.luminance() }.average().toFloat()
    return BgProfile(modeled, lighten = avgLum < 0.42f)
}

/** 行级取色：该行纵向区域的背景上保证可读。 */
fun lyricColor(
    base: Color,
    fraction: Float,
    isCurrent: Boolean,
    profile: BgProfile
): Color {
    val bg = profile.at(fraction)
    // 偏亮背景压暗、偏暗背景提亮（按该行实际所在区域）
    val lighten = bg.luminance() < 0.32f
    val target = if (isCurrent) 3.6f else 2.6f
    return ensureReadable(base, bg, target, lighten)
}

fun shouldAdapt(isCurrent: Boolean, mode: LyricAdaptMode): Boolean = when (mode) {
    LyricAdaptMode.OFF -> false
    LyricAdaptMode.CURRENT_ONLY -> isCurrent
    LyricAdaptMode.ALL -> true
}
