package com.whale.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.whale.music.R
import com.whale.music.data.model.Song
import com.whale.music.ui.components.PressableIconButton
import com.whale.music.ui.components.liquidGlass
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 迷你播放条 —— 两种形态，共用一套液态玻璃材质。
 *
 * 展开：58dp 玻璃胶囊（封面 / 歌名 / 上一首·播放·下一首 / 底部细进度线）
 * 收起：56dp 圆形 CD 按钮（封面旋转 + 外圈环形进度 + 中心轴孔）
 *
 * 手势分工（互不干扰）：
 * - 水平拖拽 → 切歌：左滑下一首、右滑上一首（过阈值才触发，松手弹簧回位）
 * - 垂直向下 → 收起成 CD 按钮（跟手下沉 + 缩小 + 淡出，过阈值即切换）
 * - 单击条身 → 弹出全屏播放页
 *
 * CD 按钮：单击放大回长条；长按直接进播放页。
 */
@Composable
fun MiniPlayerBar(
    backdrop: Backdrop,
    song: Song?,
    isPlaying: Boolean,
    artwork: Any?,
    progress: StateFlow<Float>,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** 形变控制器：长条上拖时跟手展开播放页（AuraPlayer MiniPlayerDragAxis.EXPAND） */
    morph: PlayerMorphController? = null,
    /** 当前可见卡片形态（长条 / CD 按钮）在窗口里的矩形，供展开形变锁定起点 */
    onCardBounds: (androidx.compose.ui.geometry.Rect?) -> Unit = {}
) {
    val current = song ?: return

    AnimatedContent(
        targetState = collapsed,
        transitionSpec = {
            ContentTransform(
                targetContentEnter = fadeIn(tween(200)) + scaleIn(initialScale = 0.88f),
                initialContentExit = fadeOut(tween(130)) + scaleOut(targetScale = 0.88f),
                sizeTransform = SizeTransform(clip = false) { _, _ ->
                    spring(dampingRatio = 0.74f, stiffness = 420f)
                }
            )
        },
        contentAlignment = Alignment.CenterEnd,
        label = "miniMorph",
        modifier = modifier
    ) { isCollapsed ->
        if (isCollapsed) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomEnd
            ) {
                CdButton(
                    backdrop = backdrop,
                    song = current,
                    isPlaying = isPlaying,
                    artwork = artwork,
                    progress = progress,
                    onExpand = { onCollapsedChange(false) },
                    onOpen = onOpen,
                    onCardBounds = onCardBounds,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
        } else {
            ExpandedBar(
                backdrop = backdrop,
                song = current,
                isPlaying = isPlaying,
                artwork = artwork,
                progress = progress,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onPrevious = onPrevious,
                onOpen = onOpen,
                onCollapse = { onCollapsedChange(true) },
                morph = morph,
                onCardBounds = onCardBounds
            )
        }
    }
}

// ────────────────────────────────────────────────────────────
// 展开态：玻璃胶囊
// ────────────────────────────────────────────────────────────

@Composable
private fun ExpandedBar(
    backdrop: Backdrop,
    song: Song,
    isPlaying: Boolean,
    artwork: Any?,
    progress: StateFlow<Float>,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpen: () -> Unit,
    onCollapse: () -> Unit,
    morph: PlayerMorphController?,
    onCardBounds: (androidx.compose.ui.geometry.Rect?) -> Unit
) {
    val density = LocalDensity.current
    val landscape = LocalConfiguration.current.let {
        it.screenWidthDp > it.screenHeightDp
    }
    val switchThreshold = with(density) { 64.dp.toPx() }
    val collapseThreshold = with(density) { 52.dp.toPx() }

    val scope = rememberCoroutineScope()
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var pressed by remember { mutableStateOf(false) }
    var backJob by remember { mutableStateOf<Job?>(null) }
    // 上拖展开的行程 = 卡片顶到屏幕顶的距离（AuraPlayer swipeRange 同款）
    var barTopPx by remember { mutableFloatStateOf(1f) }

    // 松手回位：两轴并行弹簧，视觉上像被弹回原位
    fun springBack() {
        backJob?.cancel()
        backJob = scope.launch {
            launch {
                animate(
                    initialValue = dragX,
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.52f, stiffness = 520f)
                ) { v, _ -> dragX = v }
            }
            launch {
                animate(
                    initialValue = dragY,
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = 560f)
                ) { v, _ -> dragY = v }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (landscape) 44.dp else 58.dp)
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                onCardBounds(bounds)
                barTopPx = bounds.top.coerceAtLeast(1f)
            }
            .graphicsLayer {
                // 位移/缩放只在绘制阶段读取，拖拽过程中不触发重组
                val fx = (dragX / switchThreshold).coerceIn(-1f, 1f)
                val fy = (dragY / collapseThreshold).coerceIn(0f, 1f)
                translationX = dragX
                translationY = dragY
                val base = 1f - 0.18f * fy - 0.035f * abs(fx)
                val press = if (pressed) 0.982f else 1f
                scaleX = base * press
                scaleY = base * press
                alpha = (1f - 0.5f * fy).coerceIn(0f, 1f)
            }
    ) {
        // 手势层（位于最下层，触摸未被按钮消费时接管）
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        backJob?.cancel()
                        pressed = true
                        var accX = 0f
                        var accY = 0f
                        var axis = 0 // 0 未定 / 1 水平 / 2 垂直
                        var expandDrag = false // 向上拖 → 跟手展开播放页
                        var expandOrigin = 0f
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                accX += change.positionChange().x
                                accY += change.positionChange().y
                                if (axis == 0 &&
                                    (abs(accX) > viewConfiguration.touchSlop ||
                                        abs(accY) > viewConfiguration.touchSlop)
                                ) {
                                    axis = if (abs(accX) >= abs(accY)) 1 else 2
                                    if (axis == 2 && accY < 0f && morph != null) {
                                        // AuraPlayer MiniPlayerDragAxis.EXPAND：上拖跟手展开
                                        expandDrag = true
                                        expandOrigin = morph.progress
                                        morph.gesturing = true
                                    }
                                }
                                if (axis == 1) {
                                    change.consume()
                                    dragX = accX
                                    dragY = accY * 0.18f
                                } else if (expandDrag && morph != null) {
                                    change.consume()
                                    dragX = accX * 0.18f
                                    dragY = accY * 0.22f
                                    // 受限作用域（AwaitPointerEventScope）里用 scope 跟手
                                    scope.launch { morph.dragTo(expandOrigin - accY / barTopPx) }
                                } else if (axis == 2) {
                                    change.consume()
                                    // 只响应向下拖；向上给一点阻尼
                                    dragY = if (accY > 0f) accY else accY * 0.22f
                                    dragX = accX * 0.18f
                                }
                            }

                            when {
                                // 单击 → 打开播放页
                                axis == 0 -> {
                                    dragX = 0f
                                    dragY = 0f
                                    onOpen()
                                }

                                // 水平 → 切歌（左滑下一首 / 右滑上一首）
                                axis == 1 -> {
                                    val dir = when {
                                        accX <= -switchThreshold -> -1
                                        accX >= switchThreshold -> 1
                                        else -> 0
                                    }
                                    if (dir < 0) onNext() else if (dir > 0) onPrevious()
                                    springBack()
                                }

                                // 上拖展开：过 12% 进度进播放页；不足弹回卡片（形变层收尾）
                                expandDrag && morph != null -> {
                                    if (morph.progress > 0.12f) onOpen()
                                    springBack()
                                }

                                // 垂直向下 → 收起成 CD / 回弹
                                else -> {
                                    if (accY >= collapseThreshold) onCollapse() else springBack()
                                }
                            }
                        } finally {
                            // 手势正常结束或被中断（指针取消/被移出组合），
                            // 都把控制权还给形变层：按 open 弹开或弹回卡片
                            pressed = false
                            if (expandDrag && morph != null) morph.gesturing = false
                        }
                    }
                }
        )

        // 玻璃胶囊 + 内容（材质与播放页形变层同源：liquidGlass 全局液态玻璃）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlass(backdrop, Capsule())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 7.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverThumb(artwork = artwork, size = if (landscape) 32.dp else 44.dp)

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        fontSize = if (landscape) 12.sp else 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = song.artist,
                        fontSize = 10.5.sp,
                        color = Color.White.copy(alpha = 0.50f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    PressableIconButton(
                        onClick = onPrevious,
                        buttonSize = if (landscape) 30.dp else 38.dp,
                        backdrop = backdrop, baseAlpha = 0.04f
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "上一首",
                            tint = Color.White.copy(alpha = 0.88f),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    PressableIconButton(
                        onClick = onTogglePlay,
                        buttonSize = if (landscape) 30.dp else 38.dp,
                        backdrop = backdrop, baseAlpha = 0.04f
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = "播放/暂停",
                            tint = Color.White,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    PressableIconButton(
                        onClick = onNext,
                        buttonSize = if (landscape) 30.dp else 38.dp,
                        backdrop = backdrop, baseAlpha = 0.04f
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "下一首",
                            tint = Color.White.copy(alpha = 0.88f),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            // 底部细进度线（独立订阅，避免每 500ms 让整条重组）— 发光胶囊同款材质
            MiniProgress(
                progress = progress,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 26.dp, end = 26.dp)
                    .padding(bottom = 1.dp)
                    .height(7.dp)
            )
        }
    }
}

// ────────────────────────────────────────────────────────────
// 收起态：圆形 CD 按钮
// ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CdButton(
    backdrop: Backdrop,
    song: Song,
    isPlaying: Boolean,
    artwork: Any?,
    progress: StateFlow<Float>,
    onExpand: () -> Unit,
    onOpen: () -> Unit,
    onCardBounds: (androidx.compose.ui.geometry.Rect?) -> Unit,
    modifier: Modifier = Modifier
) {
    var angle by remember { mutableFloatStateOf(0f) }
    val cdInteraction = remember { MutableInteractionSource() }
    val pressed by cdInteraction.collectIsPressedAsState()

    // 播放时匀速转（约 18 秒一圈），暂停即停在当前角度
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    angle = (angle + dt * 20f) % 360f
                }
                last = now
            }
        }
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .onGloballyPositioned { onCardBounds(it.boundsInWindow()) }
            .graphicsLayer {
                val s = if (pressed) 0.92f else 1f
                scaleX = s
                scaleY = s
            }
            .combinedClickable(
                interactionSource = cdInteraction,
                indication = null,
                onClick = onExpand,
                onLongClick = onOpen
            )
            .liquidGlass(backdrop, CircleShape, withInnerShadow = true)
    ) {
        // 唱片主体：封面随角度旋转
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
                .graphicsLayer { rotationZ = angle }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            if (artwork != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(artwork)
                        .size(240, 240)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.default_album_art),
                    error = painterResource(R.drawable.default_album_art),
                    fallback = painterResource(R.drawable.default_album_art)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // 中心轴孔：黑色小圆 + 白色高光点
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(11.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.65f))
            )
        }

        // 外圈环形进度（玻璃质感：半透明底环 + 白色进度弧）
        CdProgressRing(progress = progress, modifier = Modifier.fillMaxSize())
    }
}

/** 环形进度独立订阅：每 500ms 只重组这一小块，不动封面和动画 */
@Composable
private fun CdProgressRing(progress: StateFlow<Float>, modifier: Modifier = Modifier) {
    val p by progress.collectAsState()
    Canvas(modifier = modifier) {
        val stroke = 2.2.dp.toPx()
        val inset = stroke / 2f + 1.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(
            color = Color.White.copy(alpha = 0.14f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = Color.White.copy(alpha = 0.72f),
            startAngle = -90f,
            sweepAngle = 360f * p.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

// ────────────────────────────────────────────────────────────
// 零件
// ────────────────────────────────────────────────────────────

@Composable
private fun CoverThumb(artwork: Any?, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        if (artwork != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artwork)
                    .size(200, 200)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(R.drawable.default_album_art),
                error = painterResource(R.drawable.default_album_art),
                fallback = painterResource(R.drawable.default_album_art)
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun MiniProgress(progress: StateFlow<Float>, modifier: Modifier = Modifier) {
    val p by progress.collectAsState()
    GlowProgressBar(
        value = p,
        accent = Color.White,
        modifier = modifier,
        thickness = 2.5.dp,
        dragThickness = 2.5.dp,
        halo = false
    )
}
