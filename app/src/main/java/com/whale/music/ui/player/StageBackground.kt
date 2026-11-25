package com.whale.music.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.random.Random

/**
 * 播放页背景（舞台追光）。
 *
 * ## 为什么不用「单色渐变 + 纯黑边」
 * Compose 大面积平缓渐变只有 8bit，暗部色阶之间很容易出现一条条色带；
 * 端点直接落到 `Color.Black` 又会让四周「死黑」，和封面主色断开。
 *
 * 这里按三件事重做：
 * 1. **多光源**：主追光（左上，最亮）+ 副光（右下，中间调）+ 竖向色阶
 *    （中段微亮、底部带主色压深），三色都从封面主色派生，不是单色直铺；
 * 2. **不死黑**：最深只到「主色 ↔ 0xFF121212」混合，四周再加一层带色半透明暗角；
 * 3. **噪点抖动**：最上层铺 2%~3% 的白色噪点（Dithering），把色带打散。
 */
@Composable
fun StageGradient(accent: Color, modifier: Modifier = Modifier) {
    // 三色阶：高光 / 中间调 / 暗角（都带主色，绝不纯黑）
    val highlight = lerp(accent, Color.White, 0.62f)
    val mid = accent
    // 深色封面的主色可能本身就接近黑：给暗部一个「抬底」，最低也是带蓝调的深灰
    val edge = lerp(lerp(accent, Color(0xFF121212), 0.78f), Color(0xFF1B1E26), 0.30f)
    val vignette = lerp(edge, Color(0xFF101116), 0.40f)

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val long = max(w, h)

        // 底色：带主色的深灰（保留一点封面纹理，不压成死黑）
        drawRect(edge.copy(alpha = 0.58f))

        // 主追光：左上，最亮的一团
        drawRect(
            Brush.radialGradient(
                colors = listOf(
                    highlight.copy(alpha = 0.34f),
                    mid.copy(alpha = 0.16f),
                    Color.Transparent
                ),
                center = Offset(w * 0.26f, h * 0.18f),
                radius = long * 0.82f
            )
        )

        // 副光：右下，中间调，拉开纵深感
        drawRect(
            Brush.radialGradient(
                colors = listOf(mid.copy(alpha = 0.26f), mid.copy(alpha = 0.06f), Color.Transparent),
                center = Offset(w * 0.76f, h * 0.66f),
                radius = long * 0.60f
            )
        )

        // 竖向多色阶：上清、中段提一点亮、底部带主色压深（有颜色，不是黑）
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.55f to mid.copy(alpha = 0.05f),
                1f to edge.copy(alpha = 0.88f),
                startY = 0f,
                endY = h
            )
        )

        // 四角带色暗角：把边缘收住，但保留色调
        drawRect(
            Brush.radialGradient(
                0f to Color.Transparent,
                0.58f to Color.Transparent,
                1f to vignette.copy(alpha = 0.66f),
                center = Offset(w / 2f, h / 2f),
                radius = long * 0.74f
            )
        )
    }
}

/** 2%~3% 白色噪点层：叠在渐变最上面，专门用来打散色带（Dithering） */
@Composable
fun NoiseOverlay(modifier: Modifier = Modifier) {
    val brush = rememberNoiseBrush()
    Box(
        modifier
            .fillMaxSize()
            .drawWithContent { drawRect(brush) }
    )
}

/**
 * 白色噪点的平铺画刷：64×64 的随机 alpha 图，Repeat 平铺。
 * 只看 alpha 不看颜色 —— 纯白噪点不会把画面染灰，只会把色阶“揉开”。
 */
@Composable
private fun rememberNoiseBrush(): ShaderBrush = remember {
    val size = 64
    val random = Random(20261125)
    val pixels = IntArray(size * size) {
        // alpha 0..10（平均约 2%），少量像素更亮一点，颗粒才不会太均匀
        val a = if (random.nextFloat() < 0.15f) 9 + random.nextInt(4) else random.nextInt(8)
        (a shl 24) or 0x00FFFFFF
    }
    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    ShaderBrush(shader)
}

/**
 * 卡片边缘高光（Rim light）：1px 白渐变描边，上亮下暗，模拟顶部受光。
 * 暗色背景里靠它把卡片从背景中“浮”出来。
 */
fun Modifier.rimLight(corner: Dp = 18.dp, strength: Float = 1f): Modifier =
    drawWithContent {
        drawContent()
        val r = corner.toPx()
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.18f * strength),
                    Color.White.copy(alpha = 0.04f * strength)
                )
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = Stroke(width = 1.dp.toPx())
        )
    }