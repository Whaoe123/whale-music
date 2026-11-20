package com.whale.music.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.whale.music.R
import com.whale.music.data.model.Song
import com.whale.music.util.CoverResolver
import com.whale.music.util.DisplayPrefs
import com.whale.music.player.PlayMode
import com.whale.music.ui.theme.ProgressActive
import com.whale.music.ui.theme.ProgressInactive
import com.whale.music.ui.theme.TextPrimary
import com.whale.music.ui.components.HiResBadge
import com.whale.music.ui.components.PressableIconButton
import com.whale.music.ui.components.rememberHiResVisible
import com.whale.music.util.InteractiveHighlight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 播放页 — Spotify/Apple Music 同款方案
 * 架构（同 cool-digital 的 DiscoverScreen）：
 * - Column 内容：layerBackdrop source（封面、歌曲信息、进度条）
 * - PlaybackGlassControls：sibling overlay，用真正的 drawBackdrop 折射封面内容
 */
@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    /** 底部播放控件距屏幕底部的距离；全屏覆盖模式传小值 */
    controlsBottomPadding: Dp = 128.dp,
    /** 右上角齿轮 → 跳到设置页（音质 / 更新 / 自动播放都在那） */
    onOpenSettings: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val backdrop = rememberLayerBackdrop()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playMode by viewModel.playMode.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()
    val playModeLabel = stringResource(
        when (playMode) {
            PlayMode.SEQUENCE -> R.string.play_mode_sequence
            PlayMode.REPEAT_ONE -> R.string.play_mode_repeat_one
            PlayMode.SHUFFLE -> R.string.play_mode_shuffle
        }
    )

    // 首帧完成后再连接播放服务，避免 ExoPlayer 初始化竞争冷启动关键路径。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        viewModel.connect()
    }

    val playerArtwork by viewModel.artwork.collectAsState()
    val artUri: Any? = playerArtwork ?: currentSong?.let { CoverResolver.resolveSong(it.id, it.albumId) }

    // 歌词：点封面切换
    var showLyrics by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    val playlistVm: com.whale.music.ui.playlist.PlaylistViewModel = hiltViewModel()
    var lyrics by remember { mutableStateOf<List<com.whale.music.data.remote.LyricLine>>(emptyList()) }
    var lyricsLoading by remember { mutableStateOf(false) }
    LaunchedEffect(currentSong?.id, showLyrics) {
        val song = currentSong ?: return@LaunchedEffect
        if (!showLyrics || lyrics.isNotEmpty()) return@LaunchedEffect
        lyricsLoading = true
        lyrics = com.whale.music.data.remote.LyricSource.load(song.title, song.artist)
        lyricsLoading = false
    }
    LaunchedEffect(currentSong?.id) {
        // 切歌后清空旧歌词
        lyrics = emptyList()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── backdrop 采样源：模糊背景+内容（不含播放控件）──
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            // ── 封面模糊背景（Crossfade 过渡，切歌不硬切）──
            Crossfade(
                targetState = artUri,
                animationSpec = tween(1200),
                label = "bg"
            ) { uri ->
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(uri)
                        .size(512, 512)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(64.dp),
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(2.0f) }),
                    alpha = 0.9f,
                    placeholder = painterResource(R.drawable.default_album_art),
                    error = painterResource(R.drawable.default_album_art),
                    fallback = painterResource(R.drawable.default_album_art)
                )
            }
            // 暗色遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )

            // ── 内容 Column ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 4.dp, bottom = controlsBottomPadding + 104.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // 顶栏：「正在播放」+ 更多（加入歌单）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "正在播放",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
                Spacer(Modifier.weight(1f))
                // 设置 → 跳设置页（版本 / 检测更新 / 音质 / 自动播放）
                PressableIconButton(
                    onClick = onOpenSettings,
                    buttonSize = 44.dp,
                    baseAlpha = 0f
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(19.dp)
                    )
                }
                Box {
                    PressableIconButton(
                        onClick = { moreMenu = true },
                        buttonSize = 44.dp,
                        baseAlpha = 0f
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = moreMenu,
                        onDismissRequest = { moreMenu = false },
                        containerColor = Color(0xFF242426)
                    ) {
                        DropdownMenuItem(
                            text = { Text("加入歌单", fontSize = 13.sp, color = Color.White) },
                            onClick = { moreMenu = false; showAddSheet = true }
                        )
                        DropdownMenuItem(
                            text = { Text("切到歌词", fontSize = 13.sp, color = Color.White) },
                            onClick = { moreMenu = false; showLyrics = true }
                        )
                    }
                }
            }

            // 封面 / 歌词（点击切换；上下滑切歌）
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .aspectRatio(1f)
            ) {
                SwipeToSwitch(
                    onSwipeUp = viewModel::next,
                    onSwipeDown = viewModel::previous
                ) {
                    if (showLyrics) {
                        LyricsPanel(
                            lyrics = lyrics,
                            loading = lyricsLoading,
                            viewModel = viewModel,
                            onTap = { showLyrics = false }
                        )
                    } else {
                        AlbumArtCard(
                            song = currentSong,
                            artwork = artUri,
                            isPlaying = isPlaying,
                            modifier = Modifier.fillMaxSize(),
                            onTap = { showLyrics = true }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Hi-Res 金标：封面下方、右对齐（参考样式）
            // 在线歌看 EAPI 实际下发档位，本地歌异步读真实码率（不卡 UI）
            // 开关与大小在设置页调（DisplayPrefs），这里实时生效
            val hiresBadgeOn by DisplayPrefs.hiresEnabled.collectAsState()
            val hiresBadgeScale by DisplayPrefs.hiresScale.collectAsState()
            if (hiresBadgeOn && rememberHiResVisible(currentSong)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    HiResBadge(
                        modifier = Modifier.graphicsLayer {
                            scaleX = hiresBadgeScale
                            scaleY = hiresBadgeScale
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 歌曲信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(
                        text = currentSong?.title ?: stringResource(R.string.status_not_playing),
                        fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentSong?.artist ?: "左右滑到「Whale-汽水」挑一首",
                        fontSize = 15.sp, color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                PressableIconButton(
                    onClick = viewModel::togglePlayMode,
                    buttonSize = 52.dp
                ) {
                    Icon(
                        imageVector = when (playMode) {
                            PlayMode.SEQUENCE -> Icons.Default.Repeat
                            PlayMode.REPEAT_ONE -> Icons.Default.RepeatOne
                            PlayMode.SHUFFLE -> Icons.Default.Shuffle
                        },
                        contentDescription = stringResource(
                            R.string.action_change_play_mode,
                            playModeLabel
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                PressableIconButton(
                    onClick = viewModel::toggleFavorite,
                    buttonSize = 52.dp
                ) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(
                            if (isFavorite) R.string.action_unfavorite else R.string.action_favorite
                        ),
                        tint = if (isFavorite) Color.White else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 进度条（独立组合，避免每 500ms 整页重组）
            ProgressArea(viewModel = viewModel)

            Spacer(modifier = Modifier.weight(1f))
        }
        } // backdrop source wrapper

        // ── 播放控件 — sibling overlay，居中定位 ──
        PlaybackGlassControls(
            backdrop = backdrop,
            isPlaying = isPlaying,
            onPrevious = viewModel::previous,
            onTogglePlay = viewModel::togglePlay,
            onNext = viewModel::next,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = controlsBottomPadding)
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
        )

        // ── 加入歌单 ──
        if (showAddSheet) {
            val playing = currentSong
            if (playing != null) {
                com.whale.music.ui.playlist.AddToPlaylistSheet(
                    localSong = if (playing.albumId >= 0L) playing else null,
                    remoteSong = if (playing.albumId < 0L) {
                        val src = when (playing.albumId) {
                            com.whale.music.ui.player.PlayerViewModel.REMOTE_WY_ALBUM_ID -> "wy"
                            com.whale.music.ui.player.PlayerViewModel.REMOTE_LX_ALBUM_ID -> "lx"
                            else -> "kw"
                        }
                        com.whale.music.data.model.RemoteSong(
                            songId = "${src}_${playing.id}",
                            source = src,
                            rid = playing.id,
                            title = playing.title,
                            artist = playing.artist,
                            album = playing.album,
                            coverUrl = "",
                            duration = playing.duration
                        )
                    } else null,
                    onDismiss = { showAddSheet = false },
                    viewModel = playlistVm
                )
            }
        }
    }
}

/** 专辑封面卡片 — 外发光 */
@Composable
private fun AlbumArtCard(
    song: Song?,
    artwork: Any?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val cornerRadius = 20.dp
    val context = LocalContext.current

    Box(
        modifier = modifier
            .graphicsLayer {
                // 外发光：shadowElevation + shape
                shadowElevation = if (isPlaying) 24f else 12f
                shape = RoundedCornerShape(cornerRadius)
                clip = false
                ambientShadowColor = Color.White
                spotShadowColor = Color.White
            }
    ) {
        // 封面卡片
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerRadius))
                .then(
                    if (onTap != null) Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = onTap
                    ) else Modifier
                )
                .drawBehind {
                    val strokeW = 2.5f.dp.toPx()
                    val r = cornerRadius.toPx()
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.25f),
                                Color.White.copy(alpha = 0.08f),
                                Color.White.copy(alpha = 0.3f)
                            )
                        ),
                        cornerRadius = CornerRadius(r),
                        style = Stroke(width = strokeW)
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                // 固定按 ~1000px 解码：封面源已是 800×800，避免按小图拉伸发糊
                model = ImageRequest.Builder(context)
                    .data(artwork)
                    .size(1024, 1024)
                    .build(),
                contentDescription = song?.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(R.drawable.default_album_art),
                error = painterResource(R.drawable.default_album_art),
                fallback = painterResource(R.drawable.default_album_art)
            )
        }
    }
}

/**
 * 上下滑切歌容器 — 封面/歌词随手势上下移动，滑出阈值后切歌，
 * 新歌内容从对侧滑入（带弹簧回弹，动画连贯）。
 */
@Composable
private fun SwipeToSwitch(
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offsetY = remember { androidx.compose.animation.core.Animatable(0f) }
    var switching by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .graphicsLayer { translationY = offsetY.value }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (!switching) {
                            scope.launch { offsetY.snapTo(offsetY.value + dragAmount) }
                        }
                    },
                    onDragEnd = {
                        val dy = offsetY.value
                        val threshold = size.height * 0.18f
                        if (!switching && kotlin.math.abs(dy) > threshold) {
                            switching = true
                            val up = dy < 0
                            scope.launch {
                                // 顺着手指方向滑出
                                offsetY.animateTo(
                                    if (up) -size.height.toFloat() else size.height.toFloat(),
                                    tween(170)
                                )
                                if (up) onSwipeUp() else onSwipeDown()
                                // 新内容从对侧滑入
                                offsetY.snapTo(if (up) size.height.toFloat() else -size.height.toFloat())
                                offsetY.animateTo(
                                    0f,
                                    spring(dampingRatio = 0.82f, stiffness = 380f)
                                )
                                switching = false
                            }
                        } else {
                            scope.launch {
                                offsetY.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            offsetY.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f))
                        }
                    }
                )
            }
    ) {
        content()
    }
}

/**
 * 播放控件 — 严格按 AndroidLiquidGlass 库规范
 * drawBackdrop(backdrop) + CircleShape + vibrancy/blur/lens
 * + InteractiveHighlight 触摸高光 + layerBlock 按压缩放
 */
@Composable
private fun PlaybackGlassControls(
    backdrop: Backdrop,
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animationScope = rememberCoroutineScope()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassButton(backdrop, 60.dp, onPrevious, animationScope) {
            Icon(
                Icons.Default.SkipPrevious,
                stringResource(R.string.action_previous),
                tint = TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        GlassButton(backdrop, 86.dp, onTogglePlay, animationScope) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
                tint = TextPrimary, modifier = Modifier.size(42.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        GlassButton(backdrop, 60.dp, onNext, animationScope) {
            Icon(
                Icons.Default.SkipNext,
                stringResource(R.string.action_next),
                tint = TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * 单个玻璃按钮 — 严格按库规范:
 * 1. drawBackdrop(backdrop, shape={CircleShape}, effects={vibrancy+blur+lens})
 * 2. highlight/shadow/innerShadow 随 pressProgress 变化
 * 3. layerBlock: scaleX/scaleY 按压缩放
 * 4. onDrawSurface: 半透明表面
 * 5. clickable + InteractiveHighlight.modifier + gestureModifier
 */
@Composable
private fun GlassButton(
    backdrop: Backdrop,
    btnSize: Dp,
    onClick: () -> Unit,
    animationScope: CoroutineScope,
    content: @Composable () -> Unit
) {
    val highlight = remember(animationScope) { InteractiveHighlight(animationScope = animationScope) }
    val p = highlight.pressProgress

    Box(
        modifier = Modifier
            .size(btnSize)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    vibrancy()
                    blur(4f.dp.toPx())
                    lens(12f.dp.toPx(), 18f.dp.toPx(), chromaticAberration = true)
                },
                highlight = {
                    Highlight.Default.copy(alpha = 0.5f + 0.4f * p)
                },
                shadow = {
                    Shadow(alpha = 0.35f + 0.3f * p)
                },
                innerShadow = {
                    InnerShadow(radius = 4f.dp + 3f.dp * p, alpha = 0.25f + 0.35f * p)
                },
                layerBlock = {
                    scaleX = 1f - 0.06f * p
                    scaleY = 1f - 0.06f * p
                    val off = highlight.offset
                    translationX = btnSize.toPx() * 0.015f * (off.x / btnSize.toPx() - 0.5f)
                    translationY = btnSize.toPx() * 0.015f * (off.y / btnSize.toPx() - 0.5f)
                },
                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.15f)) }
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .then(highlight.modifier)
            .then(highlight.gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 歌词面板 — 点按切回封面，当前行高亮并自动居中滚动 */
@Composable
private fun LyricsPanel(
    lyrics: List<com.whale.music.data.remote.LyricLine>,
    loading: Boolean,
    viewModel: PlayerViewModel,
    onTap: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val positionMs = uiState.currentPosition
    val listState = rememberLazyListState()
    val currentIndex = remember(lyrics, positionMs) {
        lyrics.indexOfLast { it.timeMs <= positionMs + 200 }.coerceAtLeast(0)
    }

    LaunchedEffect(currentIndex) {
        if (lyrics.isNotEmpty()) {
            // 当前唱到的行放在歌词区偏上的位置（距离顶部约两行）
            listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onTap
            )
    ) {
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.5f),
                    strokeWidth = 2.dp
                )
            }

            lyrics.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无歌词",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 14.sp
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(lyrics) { index, line ->
                    val active = index == currentIndex
                    Text(
                        text = line.text,
                        fontSize = if (active) 17.sp else 15.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) Color.White else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** 进度条 + 时间 — 自带状态订阅，只重组自身 */
@Composable
private fun ProgressArea(viewModel: PlayerViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val progress = uiState.progress
    val currentPosition = uiState.currentPosition
    val duration = uiState.duration

    Canvas(
        modifier = Modifier.fillMaxWidth().height(24.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                viewModel.seekTo((down.position.x / size.width).coerceIn(0f, 1f))
                down.consume()
                drag(down.id) { change ->
                    viewModel.seekTo((change.position.x / size.width).coerceIn(0f, 1f))
                    change.consume()
                }
            }
        }
    ) {
        val trackHeight = 3.dp.toPx()
        val dotRadius = 5.5f.dp.toPx()
        val y = size.height / 2f
        val w = size.width
        val cornerRadius = 1.5.dp.toPx()

        drawRoundRect(
            ProgressInactive, Offset(0f, y - trackHeight / 2f),
            Size(w, trackHeight), CornerRadius(cornerRadius)
        )
        val activeWidth = w * progress
        drawRoundRect(
            ProgressActive, Offset(0f, y - trackHeight / 2f),
            Size(activeWidth, trackHeight), CornerRadius(cornerRadius)
        )
        drawCircle(ProgressActive, dotRadius, Offset(activeWidth, y))
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(currentPosition), fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
        Text(formatTime(duration), fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
    }
}
