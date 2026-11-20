package com.whale.music.ui.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.whale.music.data.remote.LyricLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/*
 * 舞台歌词渲染 — 骨架移植自 Sonance（gxslcl/Sonance，MIT License，
 * Copyright (c) 2026 chenlucc），适配 Whale：行级 LRC（无逐字/翻译）、
 * 繁转简显示、封面主色、按歌偏移。
 */

/** 手动滚动后多久恢复自动跟随（毫秒）。 */
internal const val SCROLL_HOLD_MS = 5_000L

/** 松手惯性：速度换算成"再多走多少秒的距离" */
private const val FLING_SECONDS = 0.10f

/** 低于这个速度不做惯性 */
private const val FLING_MIN_VELOCITY = 120f

/** 播放位置平滑插值（500ms 采样 → 60fps 顺滑），暂停时贴住采样值。 */
@Composable
fun rememberSmoothPosition(positionMs: Long, isPlaying: Boolean): State<Long> {
    val pos = remember { mutableLongStateOf(positionMs) }
    val sample = remember { longArrayOf(positionMs, 0L) }
    LaunchedEffect(positionMs) {
        sample[0] = positionMs
        sample[1] = withFrameNanos { it }
        if (!isPlaying) pos.longValue = positionMs
    }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - sample[1]).coerceAtLeast(0L)) / 1_000_000L
                pos.longValue = sample[0] + dt
            }
        }
    }
    return pos
}

/**
 * 舞台歌词（经典 / 流体声波景深 / 黑胶环形声轨 / 3D 深空星廊）。
 *
 * @param lines 已按显示层处理（繁转简）的歌词
 * @param positionMs 播放器原始进度；偏移在这里面合成
 * @param onSeek 回传播放器应跳到的原始进度（已把偏移减回）
 */
@Composable
fun StageLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    style: LyricStyle,
    fontSizeSp: Int,
    adaptMode: LyricAdaptMode,
    bgProfile: BgProfile?,
    accent: Color,
    offsetMs: Long,
    scrollHaptic: Boolean,
    onSeek: (Long) -> Unit
) {
    if (lines.isEmpty()) return
    // 换歌/换样式重置舞台与帧钟，不动已存设置
    key(lines, style) {
        val smoothPos = rememberSmoothPosition(positionMs, isPlaying)
        val nextTimes = remember(lines) { lyricNextTimes(lines.map { it.timeMs }) }
        val current = remember(smoothPos.value, offsetMs) {
            val pos = smoothPos.value + offsetMs
            lines.indexOfLast { it.timeMs <= pos + 200 }.coerceAtLeast(0)
        }

        // 舞台中心（小数行号）：自动跟随走播放进度，拖动走手指，同一个值不打架
        val stage = remember { mutableFloatStateOf(current.coerceAtLeast(0).toFloat()) }
        val maxStage = remember(lines) { lines.lastIndex.toFloat() }
        val lastTouch = remember { longArrayOf(0L) }
        var dragging by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current
        val latestHaptic by rememberUpdatedState(scrollHaptic)
        val settleJob = remember { arrayOfNulls<Job>(1) }
        val returnJob = remember { arrayOfNulls<Job>(1) }

        suspend fun animateStage(target: Float, spec: AnimationSpec<Float>) {
            val anim = Animatable(stage.floatValue)
            anim.animateTo(target.coerceIn(0f, maxStage), spec) { stage.floatValue = value }
        }

        /** 松手/换句 5 秒后回到当前播放句 */
        fun scheduleReturn() {
            returnJob[0]?.cancel()
            returnJob[0] = scope.launch {
                delay(SCROLL_HOLD_MS)
                if (dragging) return@launch
                val target = current.coerceAtLeast(0).toFloat()
                if (abs(target - stage.floatValue) < 0.001f) return@launch
                settleJob[0]?.cancel()
                animateStage(target, tween(320, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
            }
        }

        /** 点击某句 / 惯性收尾：平滑停到目标行 */
        fun settleTo(target: Float) {
            lastTouch[0] = android.os.SystemClock.uptimeMillis()
            returnJob[0]?.cancel()
            settleJob[0]?.cancel()
            settleJob[0] = scope.launch {
                animateStage(target, tween(320, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
            }
        }

        LaunchedEffect(current, dragging) {
            if (dragging) return@LaunchedEffect
            if (android.os.SystemClock.uptimeMillis() - lastTouch[0] < SCROLL_HOLD_MS) return@LaunchedEffect
            val target = current.coerceAtLeast(0).toFloat()
            if (abs(target - stage.floatValue) < 0.001f) return@LaunchedEffect
            if (abs(target - stage.floatValue) > 3f) {
                stage.floatValue = target // 拖进度条不飞扫几百行
            } else if (style == LyricStyle.SPATIAL) {
                animateStage(target, tween(900, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
            } else {
                animateStage(target, spring(dampingRatio = 0.85f, stiffness = 140f))
            }
        }

        val motion = rememberStageMotion(isPlaying && style != LyricStyle.FLUID)
        val measurer = rememberTextMeasurer()
        BoxWithConstraints(
            Modifier.fillMaxSize().clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent, 0.1f to Color.Black,
                            0.9f to Color.Black, 1f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            val localDensity = androidx.compose.ui.platform.LocalDensity.current
            val w = with(localDensity) { maxWidth.toPx() }
            val h = with(localDensity) { maxHeight.toPx() }.coerceAtLeast(1f)
            val unit = w / 336f
            val left = when (style) {
                LyricStyle.VINYL -> w * 0.35f
                LyricStyle.FLUID -> with(localDensity) { 24.dp.toPx() }
                else -> w * 0.04f
            }
            val textWidth = (w - left - if (style == LyricStyle.SPATIAL) w * 0.04f else 16f * unit)
                .coerceAtLeast(1f)
            val textWidthDp = with(localDensity) { textWidth.toDp() }
            val font = fontSizeSp * when (style) {
                LyricStyle.FLUID -> 1.3f
                LyricStyle.SPATIAL -> 1.15f
                else -> 1f
            }
            val mainStyle = TextStyle(
                fontSize = font.sp,
                lineHeight = (font * 1.38f).sp,
                fontWeight = FontWeight.ExtraBold
            )
            // 时间戳样式必须与测量一致，黑胶的缩进靠它
            val timeStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            val reflectionStyle = TextStyle(
                fontSize = (font * 0.92f).sp,
                lineHeight = (font * 1.27f).sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            val reflectionHeights = remember(lines, textWidth, fontSizeSp, localDensity, style) {
                if (style == LyricStyle.SPATIAL) lines.map {
                    measurer.measure(
                        AnnotatedString(it.text), reflectionStyle,
                        constraints = Constraints(maxWidth = textWidth.toInt().coerceAtLeast(1))
                    ).size.height.toFloat()
                } else emptyList()
            }
            // 黑胶时间戳内联左侧：实测最宽时间戳定宽，所有句子左边缘对齐
            val vinylGutterPx = remember(style, lines, localDensity) {
                if (style != LyricStyle.VINYL) {
                    0f
                } else {
                    val gap = with(localDensity) { 8.dp.toPx() }
                    val widest = lines.asSequence()
                        .map { stageTime(it.timeMs) }
                        .distinct()
                        .maxOfOrNull {
                            measurer.measure(AnnotatedString(it), timeStyle).size.width
                        } ?: 0
                    widest + gap
                }
            }
            val lyricTextWidth = (textWidth - vinylGutterPx).coerceAtLeast(1f)
            val vinylTimeWidthDp = with(localDensity) {
                (vinylGutterPx - 8.dp.toPx()).coerceAtLeast(0f).toDp()
            }
            val heights = remember(lines, textWidth, lyricTextWidth, fontSizeSp, localDensity, style) {
                lines.mapIndexed { index, line ->
                    val main = measurer.measure(
                        AnnotatedString(line.text), mainStyle,
                        constraints = Constraints(maxWidth = lyricTextWidth.toInt().coerceAtLeast(1))
                    ).size.height
                    with(localDensity) {
                        main + when (style) {
                            LyricStyle.FLUID -> 34.dp.toPx()
                            LyricStyle.SPATIAL -> 32.dp.toPx() +
                                (reflectionHeights.getOrNull(index) ?: 0f) * 0.55f
                            else -> 18.dp.toPx()
                        }
                    }
                }
            }
            val centers = remember(heights, unit, style) {
                val minSlot = if (style == LyricStyle.FLUID) 76f * unit else 56f * unit
                val gap = 18f * unit
                lyricStageCenters(heights, minSlot, gap)
            }
            val a = stage.floatValue
            val anchorCenter = lyricStageAnchor(centers, a)
            val from = maxOf(0, floor(a).toInt() - 4)
            val to = minOf(lines.lastIndex, ceil(a).toInt() + 4)

            when (style) {
                LyricStyle.VINYL -> VinylStageDecoration(accent, motion, unit)
                LyricStyle.SPATIAL -> SpatialStageDecoration(accent, motion, unit, Color.White)
                LyricStyle.FLUID -> FluidWaveDecoration(accent, motion, unit)
                else -> Unit
            }
            Box(
                Modifier.fillMaxSize().pointerInput(centers, style) {
                    if (centers.size < 2) return@pointerInput
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = {
                            settleJob[0]?.cancel()
                            returnJob[0]?.cancel()
                            tracker.resetTracking()
                            dragging = true
                            lastTouch[0] = android.os.SystemClock.uptimeMillis()
                        },
                        onDragCancel = {
                            dragging = false
                            scheduleReturn()
                        },
                        onDragEnd = {
                            dragging = false
                            lastTouch[0] = android.os.SystemClock.uptimeMillis()
                            val velocity = tracker.calculateVelocity().y
                            if (abs(velocity) > FLING_MIN_VELOCITY) {
                                // SPATIAL 的未来句在上方，方向相反
                                val travel = if (style == LyricStyle.SPATIAL) {
                                    velocity * FLING_SECONDS
                                } else {
                                    -velocity * FLING_SECONDS
                                }
                                val base = lyricStageAnchor(centers, stage.floatValue)
                                settleTo(lyricIndexAtCenter(centers, base + travel))
                            }
                            scheduleReturn()
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        val base = lyricStageAnchor(centers, stage.floatValue)
                        val delta = if (style == LyricStyle.SPATIAL) dragAmount else -dragAmount
                        val next = (base + delta).coerceIn(centers.first(), centers.last())
                        val snapped = lyricIndexAtCenter(centers, next)
                        val lineBefore = round(stage.floatValue).toInt()
                        stage.floatValue = snapped
                        // 每跨一行轻震：拖动像走过一格格齿槽（自动跟随不震）
                        if (latestHaptic && round(snapped).toInt() != lineBefore) {
                            haptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                            )
                        }
                        tracker.addPosition(change.uptimeMillis, change.position)
                        lastTouch[0] = android.os.SystemClock.uptimeMillis()
                    }
                }
            ) {
                for (index in from..to) {
                    key(index) {
                        val line = lines[index]
                        val pose = lyricStagePose(style, index - a, centers[index] - anchorCenter, unit, index)
                        val isCurrent = index == current
                        val emphasis = if (current >= 0) pose.emphasis else 0f
                        val base = lerpColor(Color.White.copy(alpha = 0.55f), accent, emphasis)
                        val y = h * (if (style == LyricStyle.FLUID) 0.44f else 0.5f) + pose.y
                        val color = if (bgProfile != null && shouldAdapt(isCurrent, adaptMode)) {
                            lyricColor(base, y / h, isCurrent, bgProfile)
                        } else base
                        val blurDp = with(localDensity) { pose.blur.toDp() }
                        val centered = style == LyricStyle.SPATIAL
                        Column(
                            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(textWidthDp)
                                .graphicsLayer {
                                    transformOrigin =
                                        androidx.compose.ui.graphics.TransformOrigin(if (centered) 0.5f else 0f, 0.5f)
                                    translationX = left + pose.x
                                    translationY = y - h / 2f
                                    scaleX = pose.scale
                                    scaleY = pose.scale
                                    rotationZ = pose.rotationZ
                                    rotationX = pose.rotationX
                                    cameraDistance = 700f * unit
                                    alpha = pose.opacity
                                }
                                .then(if (pose.blur > 0.5f) Modifier.blur(blurDp) else Modifier)
                                .zIndex(emphasis + 2f)
                                .then(
                                    if (pose.opacity > 0.05f) Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        onSeek((line.timeMs - offsetMs).coerceAtLeast(0L))
                                        settleTo(index.toFloat())
                                    } else Modifier
                                )
                                .padding(vertical = 4.dp)
                        ) {
                            if (style != LyricStyle.VINYL) {
                                Text(
                                    "${stageTime(line.timeMs)} · ${style.stageLabel}",
                                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                    color = accent.copy(alpha = emphasis),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            if (style == LyricStyle.VINYL) {
                                Row(
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        stageTime(line.timeMs), style = timeStyle,
                                        color = accent, textAlign = TextAlign.End,
                                        modifier = Modifier.padding(top = 5.dp).then(
                                            if (vinylTimeWidthDp > 0.dp) Modifier.width(vinylTimeWidthDp) else Modifier
                                        )
                                    )
                                    Text(
                                        line.text, color = color,
                                        style = TextStyle(
                                            fontSize = font.sp,
                                            lineHeight = (font * 1.38f).sp,
                                            fontWeight = if (emphasis > 0.5f) FontWeight.ExtraBold else FontWeight.Medium,
                                            shadow = Shadow(color.copy(alpha = emphasis * 0.5f), Offset.Zero, 18f * emphasis)
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                Text(
                                    line.text, color = color,
                                    style = TextStyle(
                                        fontSize = font.sp,
                                        lineHeight = (font * 1.38f).sp,
                                        fontWeight = if (emphasis > 0.5f) FontWeight.ExtraBold else FontWeight.Medium,
                                        shadow = Shadow(color.copy(alpha = emphasis * 0.5f), Offset.Zero, 18f * emphasis),
                                        textAlign = if (centered) TextAlign.Center else TextAlign.Start
                                    )
                                )
                            }
                            when (style) {
                                LyricStyle.FLUID -> {
                                    // 行进度只画在当前句且恒定透明度：旧行进/出不再闪
                                    if (isCurrent) {
                                        Spacer(Modifier.height(8.dp))
                                        LineProgressBeam(
                                            line, nextTimes[index], durationMs,
                                            smoothPos.value + offsetMs, 1f, accent
                                        )
                                    }
                                }
                                LyricStyle.SPATIAL -> {
                                    Spacer(Modifier.height(8.dp))
                                    val reflectionHeight = with(localDensity) {
                                        (reflectionHeights.getOrNull(index) ?: 0f).toDp()
                                    }
                                    Box(
                                        Modifier.fillMaxWidth().height(reflectionHeight * 0.55f)
                                    ) {
                                        Text(
                                            line.text, style = reflectionStyle, color = accent,
                                            modifier = Modifier.fillMaxWidth()
                                                .wrapContentHeight(Alignment.Top, unbounded = true)
                                                .requiredHeight(reflectionHeight)
                                                .graphicsLayer {
                                                    transformOrigin =
                                                        androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                                                    scaleY = -0.55f
                                                    translationY = size.height * 0.55f
                                                    alpha = emphasis * 0.3f
                                                    compositingStrategy = CompositingStrategy.Offscreen
                                                }
                                                .drawWithContent {
                                                    drawContent()
                                                    drawRect(
                                                        Brush.verticalGradient(
                                                            listOf(Color.Transparent, Color.Black)
                                                        ),
                                                        blendMode = BlendMode.DstIn
                                                    )
                                                }
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineProgressBeam(
    line: LyricLine,
    nextMs: Long?,
    durationMs: Long,
    positionMs: Long,
    emphasis: Float,
    accent: Color
) {
    Canvas(Modifier.fillMaxWidth(0.72f).height(3.dp)) {
        if (emphasis <= 0f) return@Canvas
        val progress = lyricLineProgress(line.timeMs, nextMs, durationMs, positionMs)
        val end = size.width * progress
        val brush = Brush.horizontalGradient(
            listOf(accent, lerpColor(accent, Color.White, 0.45f))
        )
        drawRoundRect(
            brush, size = Size(end, size.height), alpha = emphasis,
            cornerRadius = CornerRadius(size.height)
        )
    }
}

/** 装饰动效相位：播放推进、暂停定格（只被 Canvas 读取）。 */
@Composable
private fun rememberStageMotion(isPlaying: Boolean): FloatArray {
    val time = remember { floatArrayOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            time[0] = (time[0] + ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.08f)) % 420f
            previous = now
        }
    }
    return time
}

/** 黑胶盘面（盘随播放转动、唱针贴在中央行高度） */
@Composable
private fun VinylStageDecoration(accent: Color, motion: FloatArray, unit: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(-45f * unit, size.height / 2f)
        val radius = 140f * unit
        drawCircle(Color.White.copy(alpha = 0.02f), radius + 72f * unit, center)
        drawCircle(Color.White.copy(alpha = 0.03f), radius + 32f * unit, center)
        drawCircle(Color(0xFF121318), radius, center)
        for (r in 64..138 step 6) {
            drawCircle(Color(0xFF30323C), r * unit, center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(unit))
        }
        drawCircle(Color.White.copy(alpha = 0.14f), radius, center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(unit))
        rotate(motion[0] * (360f / 14f), center) {
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFF4EFE1), Color(0xFFDCD0B8)), center, 59f * unit),
                59f * unit, center
            )
            drawLine(
                accent.copy(alpha = 0.5f), center + Offset(20f * unit, -20f * unit),
                center + Offset(42f * unit, -42f * unit), 12f * unit
            )
            drawCircle(Color(0xFF141418), 9f * unit, center)
        }
        val needle = Offset(82f * unit, center.y)
        drawLine(accent.copy(alpha = 0.18f), needle, needle + Offset(24f * unit, 0f), 10f * unit)
        drawLine(accent, needle, needle + Offset(24f * unit, 0f), 2f * unit)
        drawCircle(accent.copy(alpha = 0.18f), 10f * unit, needle + Offset(24f * unit, 0f))
        drawCircle(lerpColor(accent, Color.White, 0.7f), 4f * unit, needle + Offset(24f * unit, 0f))
    }
}

/** 3D 网格地面向纵深延伸 + 中央光晕 */
@Composable
private fun SpatialStageDecoration(accent: Color, motion: FloatArray, unit: Float, textColor: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val horizon = Offset(size.width / 2f, size.height * 0.63f)
        val gridColor = textColor.copy(alpha = 0.055f)
        for (i in -7..7) {
            drawLine(
                gridColor, horizon + Offset(i * 8f * unit, 0f),
                Offset(size.width / 2f + i * 65f * unit, size.height), unit
            )
        }
        val phase = (motion[0] / 6f) % 1f
        for (i in 0..9) {
            val depth = (i + phase) / 10f
            val y = horizon.y + depth * depth * (size.height - horizon.y)
            drawLine(gridColor.copy(alpha = depth * 0.055f), Offset(0f, y), Offset(size.width, y), unit)
        }
        val radius = 130f * unit
        drawOval(
            Brush.radialGradient(
                listOf(accent.copy(alpha = 0.18f), Color.Transparent),
                Offset(size.width / 2f, size.height / 2f + 30f * unit), radius
            ),
            topLeft = Offset(size.width / 2f - radius, size.height / 2f + 5f * unit),
            size = Size(radius * 2f, 50f * unit)
        )
    }
}

/** 流体声波：底部几道缓慢起伏的波纹 */
@Composable
private fun FluidWaveDecoration(accent: Color, motion: FloatArray, unit: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val baseY = size.height * 0.88f
        for (wave in 0..2) {
            val amp = (16f + wave * 8f) * unit
            val speed = 0.6f + wave * 0.25f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, baseY + wave * 12f * unit)
                var x = 0f
                while (x <= size.width) {
                    val y = baseY + wave * 12f * unit +
                        amp * kotlin.math.sin((x / size.width * 3.14f * 2f) + motion[0] * speed)
                    lineTo(x, y)
                    x += 12f
                }
            }
            drawPath(
                path,
                accent.copy(alpha = 0.06f + wave * 0.02f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f * unit)
            )
        }
    }
}
