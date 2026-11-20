package com.whale.music.ui.player

/**
 * 悬浮卡片 → 播放页的形变动画与玻璃材质。
 *
 * 移植自 AuraPlayer（https://github.com/ColdP/AuraPlayer，AGPL-3.0-or-later）：
 * - 材质：ui/player/MiniPlayerMaterial.kt 的玻璃配方
 *   （vibrancy + blur + lens 折射 + Highlight/Shadow 随 expansion 淡出）
 * - 形变：ui/player/PlayerHost.kt 的 offset/size/圆角 三重插值 + spring(0.88, 300)
 * - 内容渐显：ui/SharedViewTransitions.kt SeamlessPageFrame 的等比放大 + 模糊渐显
 *
 * 与原版差异：原版把迷你条和播放页放进同一个容器做布局级插值；
 * 这里保留 MiniPlayerBar 自身布局，容器只负责玻璃壳与播放页的缩放渐显，
 * 卡片在展开瞬间淡出、收起时淡回，效果等价、改动面小。
 */
import android.os.Build
import android.view.RoundedCorner
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.max
import kotlin.math.roundToInt

/** 0-1 平滑阶跃（AuraPlayer seamlessSmoothStep 同款） */
private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun mix(start: Float, end: Float, progress: Float) = start + (end - start) * progress

private fun mix(start: Rect, end: Rect, progress: Float): Rect =
    Rect(
        mix(start.left, end.left, progress),
        mix(start.top, end.top, progress),
        mix(start.right, end.right, progress),
        mix(start.bottom, end.bottom, progress)
    )

/**
 * 悬浮卡片玻璃材质（AuraPlayer MiniPlayerMaterial 玻璃分支的默认配方）。
 *
 * 深色主题下：轻模糊 + 强折射（lens 带 depthEffect）+ 高光描边；
 * [expansion] 从 0 涨到 1 时高光/投影按 (1-expansion) 淡出 ——
 * 卡片长成播放页的过程中玻璃壳"退场"，最终交给不透明的播放页。
 */
fun Modifier.playerCardMaterial(
    backdrop: Backdrop,
    shape: Shape,
    expansion: Float = 0f
): Modifier {
    val fade = (1f - expansion).coerceIn(0f, 1f)
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            backdropBlur(4.3f.dp.toPx())
            lens(
                16f.dp.toPx(),
                32f.dp.toPx(),
                depthEffect = true,
                chromaticAberration = false
            )
        },
        highlight = { Highlight.Default.copy(alpha = 0.78f * fade) },
        shadow = { Shadow.Default.copy(radius = 6.dp, alpha = 0.35f * fade) },
        onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.30f)) }
    )
}

/** 屏幕圆角：API 31+ 读真实圆角，其余按 28dp 估（AuraPlayer rememberScreenCorners 简化版） */
@Composable
private fun rememberScreenCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view, density) {
        val fallback = with(density) { 28.dp }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@remember fallback
        val radius = view.rootWindowInsets
            ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius
        if (radius != null && radius > 0) with(density) { radius.toFloat().toDp() } else fallback
    }
}

/**
 * 点开悬浮卡片进入播放页的形变层。
 *
 * - [open] 为 true 时容器从卡片矩形长到全屏：offset / size / 圆角 三重插值，
 *   spring(0.88, 300) 弹簧（AuraPlayer PlayerHost 同款参数）；
 * - [content]（播放页）始终按全屏尺寸布局，用 graphicsLayer 等比缩放到当前矩形，
 *   前 50% 进度带轻微模糊渐显 —— 卡片"溶"成播放页；
 * - 返回键沿同一弹簧反向收回卡片；
 * - 完全展开后玻璃材质退场（避免全屏隐藏的 backdrop 采样开销）。
 *
 * [sourceBounds] 返回卡片在窗口坐标系里的矩形；打开展开瞬间锁定，
 * 兜底为底部胶囊位置。
 */
@Composable
fun PlayerMorphLayer(
    backdrop: Backdrop,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    sourceBounds: () -> Rect?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val expansion = remember { Animatable(0f) }
    var captured by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(open) {
        if (open) captured = sourceBounds()
        expansion.animateTo(
            if (open) 1f else 0f,
            spring(dampingRatio = 0.88f, stiffness = 300f, visibilityThreshold = 0.0005f)
        )
    }
    // 返回键：播放页内部的 BackHandler（歌词/队列页）注册更晚、优先级更高，
    // 先退它们，退完才轮到这里收起播放页。
    BackHandler(enabled = open) { onOpenChange(false) }

    var hostBounds by remember { mutableStateOf<Rect?>(null) }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { hostBounds = it.boundsInWindow() }
    ) {
        val density = LocalDensity.current
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val screenWidthPx = with(density) { screenWidth.toPx() }
        val screenHeightPx = with(density) { screenHeight.toPx() }
        val hostOffset = hostBounds?.topLeft ?: Offset.Zero
        val fallback = with(density) {
            Rect(
                16.dp.toPx(),
                (screenHeight - 160.dp).toPx(),
                screenWidthPx - 16.dp.toPx(),
                (screenHeight - 102.dp).toPx()
            )
        }
        val source = (captured ?: sourceBounds() ?: fallback)
            .translate(Offset(-hostOffset.x, -hostOffset.y))
        val screenRect = Rect(0f, 0f, screenWidthPx, screenHeightPx)
        val e = expansion.value.coerceIn(0f, 1f)
        val rect = mix(source, screenRect, e)
        // 完全展开且弹簧停下后：玻璃材质退场，只留不透明背景兜底
        val settled = open && !expansion.isRunning && e >= 0.999f
        val screenCorner = rememberScreenCornerRadius()
        val corner = if (settled) 0.dp else
            with(density) { mix(source.height / 2f, screenCorner.toPx(), e).toDp() }
        val shape = RoundedCornerShape(corner)

        if (e > 0f) {
            Box(
                Modifier
                    .offset {
                        IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
                    }
                    .size(
                        width = with(density) { rect.width.toDp() },
                        height = with(density) { rect.height.toDp() }
                    )
                    .then(
                        if (settled) Modifier.background(Color.Black)
                        else Modifier.playerCardMaterial(backdrop, shape, e)
                    )
                    .clip(shape)
            ) {
                // 播放页始终按全屏尺寸布局，再等比缩放进当前矩形
                //（AuraPlayer SeamlessPageFrame 的做法：锚点左上、cover 缩放、模糊渐显）
                Box(
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredSize(screenWidth, screenHeight)
                        .graphicsLayer {
                            val scale = max(
                                rect.width / screenWidthPx,
                                rect.height / screenHeightPx
                            )
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = scale
                            scaleY = scale
                            alpha = smoothstep(0f, 1f, e)
                        }
                        .blur((8f * ((1f - e) / 0.5f).coerceIn(0f, 1f)).dp)
                ) {
                    content()
                }
            }
        }
    }
}
