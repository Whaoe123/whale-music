package com.whale.music.ui.player

/**
 * 悬浮卡片 → 播放页的形变动画与玻璃材质。
 *
 * 移植自 AuraPlayer（https://github.com/ColdP/AuraPlayer，AGPL-3.0-or-later）：
 * - 材质：ui/player/MiniPlayerMaterial.kt 玻璃配方（见 ui/components/LiquidGlass.kt）
 * - 形变：ui/player/PlayerHost.kt 的 offset/size/圆角 三重插值 + spring(0.88, 300)
 * - 手势：ui/player/MiniPlayerGesture.kt（卡片上拖跟手展开）
 *          + ui/player/PlayerDismissGesture.kt（播放页下滑跟手收起）
 * - 内容渐显：ui/SharedViewTransitions.kt SeamlessPageFrame 的等比放大 + 模糊渐显
 *
 * 与原版差异：原版把迷你条和播放页放进同一个容器做布局级插值；
 * 这里保留 MiniPlayerBar 自身布局，容器只负责玻璃壳与播放页的缩放渐显，
 * 卡片在展开中淡出、收起时淡回，效果等价、改动面小。
 */
import android.os.Build
import android.view.RoundedCorner
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.whale.music.ui.components.liquidGlass
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 0-1 平滑阶跃（AuraPlayer seamlessSmoothStep 同款） */
internal fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
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
 * 形变进度控制器 —— 卡片拖拽与形变层共享同一个 Animatable。
 *
 * 拖拽中跟手（snapTo），松手后弹簧收尾（spring 0.88/300，AuraPlayer 同款）；
 * [gesturing] 期间形变层不插手动画，避免和手势抢进度。
 */
@Stable
class PlayerMorphController {
    val expansion = Animatable(0f)
    var gesturing by mutableStateOf(false)

    /** 当前形变进度：0 = 卡片，1 = 全屏播放页 */
    val progress: Float get() = expansion.value.coerceIn(0f, 1f)

    /** 拖拽跟手（可从手势协程直接调用） */
    suspend fun dragTo(progress: Float) {
        expansion.snapTo(progress.coerceIn(0f, 1f))
    }

    /** 松手/开合收尾 */
    suspend fun settle(open: Boolean) {
        expansion.animateTo(
            if (open) 1f else 0f,
            spring(dampingRatio = 0.88f, stiffness = 300f, visibilityThreshold = 0.0005f)
        )
    }

    /** 立即停在当前进度（手势接管前） */
    suspend fun stop() {
        expansion.stop()
    }
}

@Composable
fun rememberPlayerMorphController(): PlayerMorphController = remember { PlayerMorphController() }

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

/** 收起判定（AuraPlayer PlayerDismissGesture 同款，距离/速度都用 dp 记） */
private fun shouldDismiss(distanceDp: Float, velocityDp: Float, heightDp: Float): Boolean =
    distanceDp >= min(112f, heightDp * .16f) || (distanceDp >= 16f && velocityDp >= 1000f)

/**
 * 播放页下滑收起手势（AuraPlayer playerDismissGesture 移植）。
 *
 * 挂在形变容器上（内容的父级）：子级（歌词滚动 / 队列 / 横滑 pager）先消费事件，
 * 没被消费的垂直拖拽（顶栏、留白、背景）才归它 —— 下滑跟手缩回卡片。
 */
@Composable
private fun Modifier.playerMorphDismissGesture(
    enabled: Boolean,
    controller: PlayerMorphController,
    rangePx: Float,
    heightDp: Dp,
    onDismiss: () -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentOnDismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    return pointerInput(enabled, rangePx) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var trackedDp = 0f
            val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                // 只响应下滑（上滑交还给内容：歌词翻页等）
                if (overSlop > 0f) {
                    trackedDp = overSlop / density.density
                    change.consume()
                }
            } ?: return@awaitEachGesture
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, Offset.Zero)
            velocity.addPosition(start.uptimeMillis, Offset(0f, trackedDp * density.density))
            controller.gesturing = true
            try {
                // 受限作用域（AwaitPointerEventScope）里用 scope 跟手
                scope.launch { controller.dragTo(controller.progress - trackedDp / heightDp.value) }
                val released = verticalDrag(start.id) { change ->
                    val deltaDp = (change.position.y - change.previousPosition.y) / density.density
                    trackedDp += deltaDp
                    velocity.addPosition(
                        change.uptimeMillis,
                        Offset(0f, trackedDp * density.density)
                    )
                    // 下滑 → 形变进度按屏高等比缩小（AuraPlayer swipeDistance/range）
                    scope.launch { controller.dragTo(controller.progress - deltaDp / heightDp.value) }
                    change.consume()
                }
                if (released) {
                    val velocityDp = velocity.calculateVelocity().y / density.density
                    // 没过收起阈值就不收；松手收尾交给 LaunchedEffect(open, gesturing)
                    if (shouldDismiss(trackedDp, velocityDp, heightDp.value)) currentOnDismiss()
                }
            } finally {
                // 正常结束 / 中断都交还控制权：由形变层按 open 状态弹回或收尾
                controller.gesturing = false
            }
        }
    }
}

/**
 * 点开悬浮卡片进入播放页的形变层。
 *
 * - [open] 为 true 时容器从卡片矩形长到全屏：offset / size / 圆角 三重插值；
 * - 手势期间由 [controller] 跟手（卡片上拖展开 / 播放页下滑收起），松手弹簧收尾；
 * - [content]（播放页）始终按全屏尺寸布局，用 graphicsLayer 等比缩放到当前矩形，
 *   前 50% 进度带轻微模糊渐显 —— 卡片"溶"成播放页；
 * - 返回键沿同一弹簧反向收回卡片；
 * - 完全展开后玻璃材质退场（避免全屏隐藏的 backdrop 采样开销）。
 */
@Composable
fun PlayerMorphLayer(
    backdrop: Backdrop,
    controller: PlayerMorphController,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    sourceBounds: () -> Rect?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var captured by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(open, controller.gesturing) {
        // 打开瞬间锁定卡片起点（卡片随即将淡出，形变从它出发）
        if (open) captured = sourceBounds()
        // 手势期间不插手；手势一结束（gesturing 翻回 false）由这里按 open 状态收尾
        if (!controller.gesturing) controller.settle(open)
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
        val scope = rememberCoroutineScope()
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
        val e = controller.progress
        val rect = mix(source, screenRect, e)
        // 完全展开且弹簧停下后：玻璃材质退场，只留不透明背景兜底
        val settled = open && !controller.expansion.isRunning && !controller.gesturing && e >= 0.999f
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
                        else Modifier.liquidGlass(backdrop, shape, expansion = e)
                    )
                    .clip(shape)
                    .playerMorphDismissGesture(
                        enabled = open && !controller.gesturing && e >= 0.995f,
                        controller = controller,
                        rangePx = screenHeightPx,
                        heightDp = screenHeight,
                        onDismiss = { onOpenChange(false) }
                    )
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
