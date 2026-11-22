package com.whale.music.ui.player

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient as NativeLinearGradient
import android.graphics.Shader as NativeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 发光进度条（Capsule Bloom）—— 材质与动画对照 AuraPlayer 效果独立重写：
 *
 * - 胶囊轨道 + 填充段外扩模糊光晕（BlurMaskFilter 渐变）
 * - 头部椭圆光环（横 1.45 / 纵 0.616 压扁），拖动时更亮更大
 * - 填充芯：主色 → 白 渐变胶囊
 * - 动画：拖动时轨道弹性加粗（spring）、进度平滑追值（线性插值），
 *   拖动松手吸附后无缝续走
 */
@Composable
internal fun GlowProgressBar(
    value: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    /** 轨道粗细 */
    thickness: Dp = 6.5.dp,
    /** 拖动时轨道粗细 */
    dragThickness: Dp = 8.5.dp,
    /** 头部光环（迷你条等小场景关掉） */
    halo: Boolean = true,
    /** 头部光环尺寸（细线模式调小，免得光环比线还粗） */
    haloSize: Dp = 18.5.dp,
    /** 外扩光晕 */
    bloom: Boolean = true,
    /** 连续拖动回调（null = 不可拖） */
    onSeek: ((Float) -> Unit)? = null,
    /** 松手提交最终位置 */
    onSeekFinished: (Float) -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    // 平滑追值：播放进度约 500ms 才更新一次，线性插值成连续动画；
    // 大跳变（切歌）直接跳，拖动时实时跟手
    val target = if (dragging) dragFraction else value.coerceIn(0f, 1f)
    val animated = remember { Animatable(value.coerceIn(0f, 1f)) }
    LaunchedEffect(target, dragging) {
        when {
            dragging -> animated.snapTo(target)
            abs(animated.value - target) > 0.25f -> animated.snapTo(target)
            else -> animated.animateTo(target, tween(520, easing = LinearEasing))
        }
    }

    // 拖动加粗（回弹手感）
    val thicknessAnim by animateDpAsState(
        targetValue = if (dragging) dragThickness else thickness,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "glowProgressThickness"
    )

    Canvas(
        modifier = modifier
            .then(
                if (onSeek != null) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            dragFraction = (down.position.x / size.width.coerceAtLeast(1))
                                .coerceIn(0f, 1f)
                            onSeek(dragFraction)
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                val pointer =
                                    event.changes.firstOrNull { it.id == down.id } ?: break
                                pointer.consume()
                                dragFraction =
                                    (pointer.position.x / size.width.coerceAtLeast(1))
                                        .coerceIn(0f, 1f)
                                onSeek(dragFraction)
                                pressed = pointer.pressed
                            }
                            dragging = false
                            onSeekFinished(dragFraction)
                        }
                    }
                } else Modifier
            )
    ) {
        drawCapsuleBloomProgress(
            fraction = animated.value,
            dragging = dragging,
            thicknessPx = thicknessAnim.toPx().coerceAtMost(size.height),
            accentColor = accent,
            halo = halo,
            haloSize = haloSize,
            bloom = bloom
        )
    }
}

/** 胶囊光晕进度条绘制：轨道 → 模糊光晕 → 头部光环 → 渐变芯 */
private fun DrawScope.drawCapsuleBloomProgress(
    fraction: Float,
    dragging: Boolean,
    thicknessPx: Float,
    accentColor: Color,
    halo: Boolean,
    haloSize: Dp,
    bloom: Boolean
) {
    val cy = size.height / 2f
    val top = cy - thicknessPx / 2f
    val corner = CornerRadius(thicknessPx / 2f, thicknessPx / 2f)

    // 轨道
    drawRoundRect(
        color = Color.White.copy(alpha = 0.14f),
        topLeft = Offset(0f, top),
        size = Size(size.width, thicknessPx),
        cornerRadius = corner
    )

    val rawFill = (size.width * fraction).coerceIn(0f, size.width)
    if (rawFill <= 0.5f) return

    val fillWidth = rawFill.coerceAtLeast(thicknessPx).coerceAtMost(size.width)
    val headX = (rawFill - thicknessPx * 0.25f).coerceIn(thicknessPx * 0.5f, size.width)

    // ── 外扩模糊光晕（芯的溢出光）──
    if (bloom) {
        val expandY = (if (dragging) 1.55.dp else 1.1.dp).toPx()
        val maxBlurExtent = (cy - thicknessPx / 2f - expandY - 0.5.dp.toPx()).coerceAtLeast(1f)
        val blurPx = (if (dragging) 7.4.dp else 5.9.dp).toPx().coerceAtMost(maxBlurExtent * 0.72f)

        val auraStart = lerp(Color.White, accentColor, 0.35f).copy(alpha = 0.22f)
        val auraEnd = accentColor.copy(alpha = 0.62f)

        drawIntoCanvas { canvas ->
            val glowPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
                shader = NativeLinearGradient(
                    0f, cy, fillWidth.coerceAtLeast(1f), cy,
                    intArrayOf(auraStart.toArgb(), auraEnd.toArgb()),
                    floatArrayOf(0f, 1f),
                    NativeShader.TileMode.CLAMP
                )
            }
            canvas.nativeCanvas.drawRoundRect(
                0f,
                top - expandY,
                fillWidth,
                top + thicknessPx + expandY,
                (thicknessPx + expandY * 2f) / 2f,
                (thicknessPx + expandY * 2f) / 2f,
                glowPaint
            )
        }
    }

    // ── 头部椭圆光环 ──
    if (halo) {
        val haloRadius = (if (dragging) haloSize * 1.19f else haloSize).toPx()
        val maxVerticalScale =
            ((cy - 0.5.dp.toPx()).coerceAtLeast(1f) / haloRadius).coerceIn(0.2f, 1f)
        val haloScaleY = minOf(0.616f, maxVerticalScale)
        withTransform({
            translate(headX, cy)
            scale(1.45f, haloScaleY, pivot = Offset.Zero)
        }) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (dragging) 0.72f else 0.58f),
                        lerp(Color.White, accentColor, 0.45f).copy(alpha = 0.20f),
                        Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = haloRadius
                ),
                radius = haloRadius,
                center = Offset.Zero
            )
        }
    }

    // ── 渐变芯：主色 → 白 ──
    val coreStart = lerp(accentColor, Color.White, 0.18f)
    val coreEnd = lerp(accentColor, Color.White, 0.92f)
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(coreStart, coreEnd),
            startX = 0f,
            endX = fillWidth.coerceAtLeast(1f)
        ),
        topLeft = Offset(0f, top),
        size = Size(fillWidth, thicknessPx),
        cornerRadius = corner
    )
}
