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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
@Composable
fun CoverflowPage(
    viewModel: PlayerViewModel,
    accent: Color
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pagerState = rememberPagerState(initialPage = playingIndex) { queue.size }
        val coverSize = ((maxHeight - 3.dp) / 1.5f).coerceAtMost(maxWidth * 0.30f)

        // 松手吸附后切歌（滑动途中不切）
        LaunchedEffect(pagerState) {
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
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 18.dp,
            contentPadding = PaddingValues(horizontal = maxWidth * 0.31f)
        ) { page ->
            val offset = page - pagerState.currentPage - pagerState.currentPageOffsetFraction
            val song = queue[page]
            val cover = CoverResolver.resolveSong(song.id, song.albumId)
            val a = offset.coerceIn(-3f, 3f)
            val dist = kotlin.math.abs(offset)

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .graphicsLayer {
                            // 3D 透视：两侧封面沿 Y 轴内倾
                            cameraDistance = 18f * density
                            rotationY = a * -24f
                            val s = 1f - dist.coerceAtMost(1.4f) * 0.17f
                            scaleX = s
                            scaleY = s
                            alpha = 1f - dist.coerceAtMost(2.2f) * 0.30f
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            scope.launch { pagerState.animateScrollToPage(page) }
                        }
                ) {
                    // 封面
                    Box(
                        Modifier
                            .size(coverSize)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF1A1B1F))
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(cover)
                                .size(512, 512)
                                .build(),
                            contentDescription = song.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            placeholder = androidx.compose.ui.res.painterResource(R.drawable.default_album_art),
                            error = androidx.compose.ui.res.painterResource(R.drawable.default_album_art),
                            fallback = androidx.compose.ui.res.painterResource(R.drawable.default_album_art)
                        )
                    }
                    // 倒影（半高盒内原地翻转，贴在封面下方，向下渐隐）
                    Box(
                        Modifier
                            .padding(top = 3.dp)
                            .size(width = coverSize, height = coverSize * 0.5f)
                            .graphicsLayer {
                                scaleY = -0.85f
                                alpha = 0.24f
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .clip(RoundedCornerShape(18.dp))
                            .drawWithContent {
                                drawContent()
                                // DstIn：局部上方（翻转后贴封面）保留，下方抹掉 → 向下渐隐
                                drawRect(
                                    Brush.verticalGradient(
                                        listOf(Color.Black, Color.Black.copy(alpha = 0f))
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(cover)
                                .size(512, 512)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().blur(1.5f.dp),
                            contentScale = ContentScale.Crop,
                            placeholder = androidx.compose.ui.res.painterResource(R.drawable.default_album_art),
                            error = androidx.compose.ui.res.painterResource(R.drawable.default_album_art),
                            fallback = androidx.compose.ui.res.painterResource(R.drawable.default_album_art)
                        )
                    }
                }
            }
        }

        // 「N / M」位置胶囊
        val pos = pagerState.currentPage + 1
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.32f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                "$pos / ${queue.size}",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
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

    Row(Modifier.fillMaxSize()) {
        // 左：面板（居中），AuraPlayer 式「封面 + 歌名」横排
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    Modifier
                        .size(116.dp)
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
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        songNow?.title ?: "",
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        songNow?.artist ?: "",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            // 进度条 + 时间（内联，只重组自身）
            ProgressArea(viewModel = viewModel, accent = accent)
            Spacer(Modifier.height(14.dp))
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
                modifier = Modifier.fillMaxWidth()
            )
        }
        // 右：大字歌词
        Box(Modifier.weight(0.58f).fillMaxSize()) {
            lyricsPage()
        }
    }
}
