package com.whale.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import com.kyant.backdrop.Backdrop
import com.whale.music.data.model.Song
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.whale.music.R
import com.whale.music.util.CoverResolver
import kotlinx.coroutines.launch

/**
 * 横屏封面页 — 3D Coverflow 封面墙（效果对照 AuraPlayer）：
 * 中央封面大、两侧透视内倾 + 倒影，左右滑浏览队列，松手自动切到该曲，
 * 左上角「N / M」位置胶囊。
 */
/**
 * 与 AuraPlayer 同一套封面墙变换：越往两侧倾角越大、等比缩小、轻微外移，
 * 远处的卡片加模糊并压暗，只有正中那张全亮。
 */
private data class CoverflowTransform(
    val rotationY: Float,
    val scale: Float,
    val translationXFactor: Float,
    val blurRadiusDp: Float,
    val dimAlpha: Float,
    val contentAlpha: Float,
    val zIndex: Float
)

private fun coverflowTransform(pageOffset: Float): CoverflowTransform {
    val absOffset = kotlin.math.abs(pageOffset).coerceIn(0f, 4f)
    val sign = if (pageOffset >= 0f) 1f else -1f
    val angle = (kotlin.math.atan(absOffset * 2.1f) / (Math.PI.toFloat() / 2f)) * 64f
    val rotationY = if (absOffset < 0.001f) 0f else -sign * angle
    val scale = (1f - 0.16f * minOf(absOffset, 1f) - 0.045f * (absOffset - 1f).coerceAtLeast(0f))
        .coerceIn(0.66f, 1f)
    val translationXFactor = -sign * ((absOffset - 0.35f).coerceAtLeast(0f) * 0.055f).coerceAtMost(0.18f)
    val rawBlur = (absOffset - 0.14f).coerceAtLeast(0f) * 3.8f
    val blurRadiusDp = if (rawBlur < 0.35f) 0f else rawBlur.coerceAtMost(14f)
    val dimAlpha = ((absOffset - 0.12f).coerceAtLeast(0f) * 0.20f).coerceIn(0f, 0.44f)
    val contentAlpha = (1f - (absOffset - 1.6f).coerceAtLeast(0f) * 0.26f).coerceIn(0.32f, 1f)
    return CoverflowTransform(
        rotationY = rotationY,
        scale = scale,
        translationXFactor = translationXFactor,
        blurRadiusDp = blurRadiusDp,
        dimAlpha = dimAlpha,
        contentAlpha = contentAlpha,
        // 正中那张永远压住两侧（负间距下两侧会压过来半个边）
        zIndex = 100f - absOffset * 10f
    )
}

@Composable
fun CoverflowPage(
    viewModel: PlayerViewModel,
    accent: Color,
    modifier: Modifier = Modifier,
    /** 底部要给停靠栏让出的高度（封面墙整体上移） */
    bottomInset: Dp = 0.dp,
    onOpenLyrics: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val queue = remember(uiState.currentSong?.id) { viewModel.queue() }
    val currentId = uiState.currentSong?.id
    val playingIndex = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

    if (queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("队列是空的", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp)
        }
        return
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        // ── 度量完全照搬 AuraPlayer 横屏封面墙 ──
        val dockCompact = maxHeight < 430.dp
        val reflectionRatio = if (dockCompact) 0.18f else 0.24f
        // 封面墙只占「底栏以上的那块」，整块在中间居中 —— 卡片中心大约落在屏幕 1/3 处
        val carouselArea = (maxHeight - bottomInset).coerceAtLeast(150.dp)
        val maxCardFromHeight =
            (carouselArea - 16.dp).coerceAtLeast(110.dp) / (1f + reflectionRatio)
        val widthRatio = if (maxWidth > 920.dp) 0.34f else 0.38f
        val cardSize = minOf(maxCardFromHeight, maxWidth * widthRatio).coerceIn(116.dp, 340.dp)
        val reflectionHeight = (cardSize * reflectionRatio).coerceIn(20.dp, 78.dp)
        val stageHeight = cardSize + 8.dp + reflectionHeight
        val pageSpacing = -(cardSize * 0.22f)
        val sidePadding = ((maxWidth - cardSize) / 2).coerceAtLeast(0.dp)
        val cornerRadius = 18.dp
        val cardShape = RoundedCornerShape(cornerRadius)

        val pagerState = rememberPagerState(initialPage = playingIndex) { queue.size }

        // 外部切歌（上一首/下一首/自动播完）时把封面墙滑过去，不打断用户正在进行的拖动
        LaunchedEffect(playingIndex) {
            if (!pagerState.isScrollInProgress && pagerState.currentPage != playingIndex) {
                pagerState.animateScrollToPage(playingIndex)
            }
        }

        // 松手吸附后切歌（滑动途中不切）
        LaunchedEffect(pagerState, playingIndex) {
            var wasScrolling = false
            snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
                if (wasScrolling && !scrolling) {
                    val idx = pagerState.currentPage
                    if (idx != playingIndex && idx in queue.indices) {
                        viewModel.playAt(idx)
                    }
                }
                wasScrolling = scrolling
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset),
            // 预组合左右各三张：快速滑动时两侧封面已经画好，动画不掉帧
            beyondViewportPageCount = 3,
            pageSize = PageSize.Fixed(cardSize),
            pageSpacing = pageSpacing,
            contentPadding = PaddingValues(horizontal = sidePadding)
        ) { page ->
            val pageOffset = (page - pagerState.currentPage) - pagerState.currentPageOffsetFraction
            val transform = coverflowTransform(pageOffset)
            val absOffset = kotlin.math.abs(pageOffset)
            val isCentered = absOffset < 0.28f
            val song = queue[page]
            // 每张封面都是那首歌自己的（远程歌用播放器记下的封面地址）
            val cover = viewModel.coverOf(song)
            val request = remember(cover, context) {
                ImageRequest.Builder(context)
                    .data(cover)
                    .size(512, 512)
                    .build()
            }
            val blurDp = transform.blurRadiusDp.dp

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .width(cardSize)
                    .height(stageHeight)
                    .zIndex(transform.zIndex)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin.Center
                        cameraDistance = 14f * density.density
                        rotationY = transform.rotationY
                        translationX = cardSize.toPx() * transform.translationXFactor
                        scaleX = transform.scale
                        scaleY = transform.scale
                        alpha = transform.contentAlpha
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (isCentered) onOpenLyrics()
                        else scope.launch { pagerState.animateScrollToPage(page) }
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── 封面 ──
                Box(
                    Modifier
                        .size(cardSize)
                        .graphicsLayer {
                            shadowElevation =
                                (if (isCentered) 26.dp else 10.dp * transform.scale).toPx()
                            shape = cardShape
                            ambientShadowColor = accent.copy(alpha = 0.45f)
                            spotShadowColor = accent.copy(alpha = 0.45f)
                        }
                        .clip(cardShape)
                        .background(Color(0xFF1A1B1F))
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = song.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (blurDp > 0.dp) Modifier.blur(blurDp, BlurredEdgeTreatment.Rectangle)
                                else Modifier
                            ),
                        contentScale = ContentScale.Crop,
                        placeholder = androidx.compose.ui.res.painterResource(R.drawable.default_album_art),
                        error = androidx.compose.ui.res.painterResource(R.drawable.default_album_art),
                        fallback = androidx.compose.ui.res.painterResource(R.drawable.default_album_art)
                    )
                    if (transform.dimAlpha > 0.005f) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(Color.Black.copy(alpha = transform.dimAlpha))
                        )
                    }
                    // 边缘高光，正中那张更亮
                    Box(
                        Modifier
                            .matchParentSize()
                            .border(
                                width = if (isCentered) 0.75.dp else 0.6.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = if (isCentered) 0.24f else 0.14f),
                                        Color.White.copy(alpha = 0.04f)
                                    )
                                ),
                                shape = cardShape
                            )
                    )
                }

                Spacer(Modifier.height(8.dp))

                // ── 倒影：整块随卡片一起转，向下渐隐并模糊 ──
                Box(
                    modifier = Modifier
                        .size(cardSize, reflectionHeight)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .clip(
                            RoundedCornerShape(
                                topStart = cornerRadius,
                                topEnd = cornerRadius
                            )
                        )
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.28f),
                                    0.52f to Color.Black.copy(alpha = 0.09f),
                                    1f to Color.Transparent
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.BottomCenter,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(cardSize)
                            .align(Alignment.TopCenter)
                            .graphicsLayer { scaleY = -1f }
                            .blur(
                                (6f + transform.blurRadiusDp * 0.55f).dp,
                                BlurredEdgeTreatment.Rectangle
                            ),
                        placeholder = androidx.compose.ui.res.painterResource(R.drawable.default_album_art),
                        error = androidx.compose.ui.res.painterResource(R.drawable.default_album_art),
                        fallback = androidx.compose.ui.res.painterResource(R.drawable.default_album_art)
                    )
                }
            }
            }
        }

    }
}

/**
 * 横屏歌词页（对照 AuraPlayer 横屏）：
 * 左面板 = 封面卡 + 歌名/歌手 + 进度条 + 5 键玻璃控件；
 * 右侧 = 全屏主题歌词。
 */
@Composable
fun LandscapeLyricsLayout(
    viewModel: PlayerViewModel,
    accent: Color,
    backdrop: Backdrop,
    songNow: Song?,
    lyricsPage: @Composable () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val art = viewModel.artwork.collectAsState().value
        ?: songNow?.let { CoverResolver.resolveSong(it.id, it.albumId) }

    // 液态玻璃已由 PlayerScreen 根部铺满整屏（不吃 Column 的边距），这里只排内容
    Row(Modifier.fillMaxSize()) {
        // 左：面板。用 Box 居中而不是 Column 的 Arrangement —— 不受父级高度测量影响，
        // 内容永远落在左栏正中，再整体上移一点
        Box(
            modifier = Modifier
                .weight(0.44f)
                .fillMaxSize()
                .padding(start = 18.dp, end = 22.dp),
            contentAlignment = Alignment.Center
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 整块往下放一点，进度条与底部播放键挨得更近
                .offset(y = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    Modifier
                        .size(132.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1A1B1F))
                        .graphicsLayer {
                            shadowElevation = 18f
                            shape = RoundedCornerShape(16.dp)
                            ambientShadowColor = accent.copy(alpha = 0.5f)
                            spotShadowColor = accent.copy(alpha = 0.5f)
                        }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(art)
                            .size(512, 512)
                            .build(),
                        contentDescription = songNow?.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = androidx.compose.ui.res.painterResource(R.drawable.default_album_art),
                        error = androidx.compose.ui.res.painterResource(R.drawable.default_album_art),
                        fallback = androidx.compose.ui.res.painterResource(R.drawable.default_album_art)
                    )
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        songNow?.title ?: "",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        songNow?.artist ?: "",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            // 进度条 + 时间（内联，只重组自身；横屏放大一档）
            ProgressArea(viewModel = viewModel, accent = accent, sizeScale = 1.15f)
            Spacer(Modifier.height(6.dp))
            // 5 键玻璃控件（折射背景层）
            PlaybackGlassControls(
                backdrop = backdrop,
                isPlaying = uiState.isPlaying,
                playMode = uiState.playMode,
                modeLabel = when (uiState.playMode) {
                    com.whale.music.player.PlayMode.SEQUENCE -> "顺序播放"
                    com.whale.music.player.PlayMode.REPEAT_ONE -> "单曲循环"
                    com.whale.music.player.PlayMode.SHUFFLE -> "随机播放"
                },
                isFavorite = uiState.isFavorite,
                onPrevious = viewModel::previous,
                onTogglePlay = viewModel::togglePlay,
                onNext = viewModel::next,
                onToggleMode = viewModel::togglePlayMode,
                onToggleFavorite = viewModel::toggleFavorite,
                modifier = Modifier.fillMaxWidth(),
                // 横屏：按钮不要外面的圆形底，图标直接浮在玻璃上
                buttonBackdrop = false
            )
        }
        }
        // 右：大字歌词
        Box(Modifier.weight(0.56f).fillMaxSize()) {
            lyricsPage()
        }
    }
}
