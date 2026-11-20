package com.whale.music.ui.lyrics

import kotlin.math.abs
import kotlin.math.pow

/*
 * 舞台几何 — 移植自 Sonance（gxslcl/Sonance，MIT License，Copyright (c) 2026 chenlucc），
 * 按 Whale 的样式枚举做了适配。纯 Kotlin，无 Android 依赖，可单测。
 */

/** 一行歌词在舞台上的位姿。距离单位是像素；unit 是视口比例尺。 */
data class LyricStagePose(
    val x: Float,
    val y: Float,
    val scale: Float,
    val opacity: Float,
    val blur: Float,
    val rotationZ: Float = 0f,
    val rotationX: Float = 0f,
    val emphasis: Float = 0f
)

fun lyricStagePose(
    style: LyricStyle,
    diff: Float,
    distance: Float,
    unit: Float,
    index: Int
): LyricStagePose {
    val a = abs(diff)
    val t = (1f - a).coerceIn(0f, 1f)
    val emphasis = t * t * (3f - 2f * t)
    // 过渡期保留第四行（透明）避免边缘跳变
    val edge = (4f - a).coerceIn(0f, 1f)
    return when (style) {
        LyricStyle.FLUID -> LyricStagePose(
            x = 0f, y = distance,
            scale = (1f - a * 0.08f).coerceAtLeast(0.76f) * (0.77f + 0.23f * emphasis),
            opacity = (0.7f - a * 0.2f).coerceAtLeast(0.14f) * edge + 0.3f * emphasis,
            blur = (a * 1.5f).coerceAtMost(5.5f) * unit,
            emphasis = emphasis
        )
        LyricStyle.VINYL -> LyricStagePose(
            x = -a.pow(1.45f) * 9f * unit, y = distance,
            scale = 0.775f + 0.225f * emphasis,
            opacity = ((0.75f - a * 0.22f).coerceAtLeast(0.16f) + 0.25f * emphasis) * edge,
            blur = 0f, rotationZ = diff * 10.5f, emphasis = emphasis
        )
        LyricStyle.SPATIAL -> {
            val future = diff > 0f
            val z = -a * (if (future) 130f else 120f) + 35f * emphasis
            val perspective = 700f / (700f - z)
            val side = if (index % 2 == 0) 1f else -1f
            LyricStagePose(
                x = side * (if (future) 12f else -10f) * a * unit * perspective,
                y = -distance * perspective,
                scale = (1f - a * 0.07f).coerceAtLeast(0.7f) * perspective,
                opacity = ((if (future) 0.78f - a * 0.18f else 0.55f - a * 0.15f)
                    .coerceAtLeast(0.1f) * (1f - emphasis) + emphasis) * edge,
                blur = (a * 1.1f).coerceAtMost(4.5f) * unit,
                rotationX = if (future) -a * 4f else 12f * (1f - emphasis),
                emphasis = emphasis
            )
        }
        LyricStyle.CLASSIC, LyricStyle.VERTICAL ->
            LyricStagePose(0f, distance, 1f, 1f, 0f, emphasis = emphasis)
    }
}

/** 按累计行高插值出每行中心，保证折行长句与短句间距一致。 */
fun lyricStageCenters(heights: List<Float>, minimumSlot: Float, gap: Float): List<Float> {
    if (heights.isEmpty()) return emptyList()
    val centers = ArrayList<Float>(heights.size)
    centers.add(0f)
    for (i in 1 until heights.size) {
        centers.add(centers.last() + maxOf(minimumSlot, (heights[i - 1] + heights[i]) / 2f + gap))
    }
    return centers
}

fun lyricStageAnchor(centers: List<Float>, anchor: Float): Float {
    if (centers.isEmpty()) return 0f
    val bounded = anchor.coerceIn(0f, centers.lastIndex.toFloat())
    val i = bounded.toInt()
    val fraction = bounded - i
    return centers[i] + ((centers.getOrNull(i + 1) ?: centers[i]) - centers[i]) * fraction
}

/** [lyricStageAnchor] 的反函数：纵向位置 → 小数行号。手动滚动严格跟手的关键。 */
fun lyricIndexAtCenter(centers: List<Float>, y: Float): Float {
    if (centers.isEmpty()) return 0f
    if (centers.size == 1) return 0f
    if (y <= centers.first()) return 0f
    if (y >= centers.last()) return centers.lastIndex.toFloat()

    var lo = 0
    var hi = centers.lastIndex
    while (lo + 1 < hi) {
        val mid = (lo + hi) ushr 1
        if (centers[mid] <= y) lo = mid else hi = mid
    }
    val span = centers[hi] - centers[lo]
    if (span <= 0f) return lo.toFloat()
    return lo + ((y - centers[lo]) / span).coerceIn(0f, 1f)
}

/** 当前句进度 0..1；最后一句用真实曲长结尾，不编造进度。 */
fun lyricLineProgress(startMs: Long, nextMs: Long?, durationMs: Long, positionMs: Long): Float {
    val end = nextMs?.takeIf { it > startMs } ?: durationMs
    if (end <= startMs) return 0f
    return ((positionMs - startMs).toDouble() / (end - startMs)).toFloat().coerceIn(0f, 1f)
}

/** 每行「下一句时间」表，兼容同时间戳（合唱）行。 */
fun lyricNextTimes(times: List<Long>): List<Long?> {
    val out = MutableList<Long?>(times.size) { null }
    var next: Long? = null
    for (i in times.lastIndex downTo 0) {
        if (i < times.lastIndex && times[i + 1] > times[i]) next = times[i + 1]
        out[i] = next
    }
    return out
}

/** 时间戳 → mm:ss（舞台标签用） */
fun stageTime(ms: Long): String {
    val sec = ms.coerceAtLeast(0L) / 1000
    return "${(sec / 60).toString().padStart(2, '0')}:${(sec % 60).toString().padStart(2, '0')}"
}
