package com.whale.music.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Equalizer
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.foundation.layout.wrapContentWidth
import com.whale.music.R
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.whale.music.ui.lyrics.LyricsPage
import com.whale.music.data.model.Song
import com.whale.music.util.CoverPalette
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * 播放页 — 三页横滑（封面｜歌词｜更多）：
 * - 背景层：模糊封面 + 氛围光，独立 bgBackdrop 供歌词玻璃底板折射
 * - 内容层：顶栏 + HorizontalPager（黑胶封面 / 全屏歌词 / 播放队列）+ 标题 + 进度
 * - 播放控件：5 键玻璃控制栏（sibling overlay，折射内容层）
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
    val bgBackdrop = rememberLayerBackdrop()
    val backdrop = rememberLayerBackdrop()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playMode by viewModel.playMode.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
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

    // 封面主色（氛围光/进度条渐变/唱片反光）+ 背景剖面（歌词亮度自适应）
    val accent by produceState(initialValue = ProgressActive, key1 = artUri) {
        value = CoverPalette.dominant(artUri?.toString()) ?: ProgressActive
    }
    val bgProfile by produceState<com.whale.music.ui.lyrics.BgProfile?>(
        initialValue = null,
        key1 = artUri
    ) {
        value = CoverPalette.bandColors(artUri?.toString())
            ?.let { com.whale.music.ui.lyrics.buildBgProfile(it) }
    }

    var moreMenu by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showLyricSettings by remember { mutableStateOf(false) }
    var showFxSheet by remember { mutableStateOf(false) }
    val playlistVm: com.whale.music.ui.playlist.PlaylistViewModel = hiltViewModel()
    var lyrics by remember { mutableStateOf<List<com.whale.music.data.remote.LyricLine>>(emptyList()) }
    LaunchedEffect(currentSong?.id) {
        // 切歌：清旧歌词再取新（LyricSource 自带缓存/负缓存，直接预取）
        lyrics = emptyList()
        val song = currentSong
        if (song != null) {
            lyrics = com.whale.music.data.remote.LyricSource.load(song.title, song.artist)
        }
    }

    // 三页横滑：0 封面 / 1 歌词 / 2 更多（队列）
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val hideStatusBar by com.whale.music.ui.lyrics.LyricPrefs.hideStatusBar.collectAsState()
    val songNow = currentSong
    // 横屏：Coverflow 封面墙 + 左封面右歌词 + 横排底部
    val landscape = LocalConfiguration.current.let {
        it.screenWidthDp > it.screenHeightDp
    }
    // 底部导航栏开关（设置页「界面」）：关掉后各页底部净空收缩
    val navBarOn by com.whale.music.util.DisplayPrefs.navBarEnabled.collectAsState()
    val songKey = com.whale.music.ui.lyrics.LyricPrefs.songKey(
        songNow?.albumId ?: 0L,
        songNow?.id ?: 0L
    )

    // 隐藏状态栏：只在歌词页 + 开关打开时生效，离开自动恢复
    val activity = context as? Activity
    LaunchedEffect(hideStatusBar, pagerState.currentPage) {
        val win = activity?.window ?: return@LaunchedEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(win, win.decorView)
        if (hideStatusBar && pagerState.currentPage == 1) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
    }

    // 返回键分层：先退出歌词/队列页，退完才走页面级返回
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }
    // 歌词设置面板：开着时返回键先关面板
    BackHandler(enabled = showLyricSettings) {
        showLyricSettings = false
    }
    // 音效面板：开着时返回键先关面板
    BackHandler(enabled = showFxSheet) {
        showFxSheet = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── 背景层：模糊封面 + 暗纱 + 氛围光 + 底部渐变（玻璃折射源）──
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(bgBackdrop)
        ) {
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
            // 暗色遮罩（比旧版浅，氛围光才能透出来）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.26f))
            )
            // 动态氛围光：两团随封面主色慢移的色斑
            AuroraLayer(accent = accent)
            // 底部渐变压暗，保证标题 / 控件可读
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.42f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                        )
                    )
            )
        }

        // ── 内容层 ──
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            // 歌词页（竖屏）：液态玻璃铺满整屏，顶栏 / 歌词 / 进度都浮在玻璃上
            if (!landscape && pagerState.currentPage == 1) {
                com.whale.music.ui.lyrics.LyricsGlassPanel(
                    bgBackdrop = bgBackdrop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 歌词页收窄水平留白，让沉浸歌词更接近满宽
                    .padding(horizontal = if (pagerState.currentPage == 1 && !landscape) 20.dp else 32.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(
                        top = 4.dp,
                        // 横屏：进度/标题/控件收进各页底部簇，只给底部导航胶囊留净空
                        bottom = if (landscape) {
                            if (navBarOn) 60.dp else 16.dp
                        } else controlsBottomPadding +
                            (if (pagerState.currentPage == 1) 74.dp else 104.dp)
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // 顶栏（竖屏）：「正在播放」+ 歌词设置 + 更多；横屏改为右上角浮层（省 44dp 给封面墙）
            if (!landscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pagerState.currentPage == 1 && songNow != null) {
                        // 歌词页：顶栏放歌名 + 歌手（沉浸歌词占满屏，歌名信息收进顶栏）
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = songNow.title,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.92f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee()
                            )
                            Text(
                                text = songNow.artist.ifBlank { songNow.album },
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.5f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(
                            text = "正在播放",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.45f)
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    PlayerTopActions(
                        onOpenLyricSettings = { showLyricSettings = true },
                        onOpenFx = { showFxSheet = true },
                        moreMenu = moreMenu,
                        onMoreMenuChange = { moreMenu = it },
                        onAddToPlaylist = { showAddSheet = true },
                        onGoLyrics = { scope.launch { pagerState.animateScrollToPage(1) } },
                        onGoQueue = { scope.launch { pagerState.animateScrollToPage(2) } }
                    )
                }
            }

            // ── 三页横滑：封面｜歌词｜更多 ──
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when {
                    // 横屏封面页：3D Coverflow 封面墙 + 底部簇（进度/歌名/控件，对照 AuraPlayer）
                    landscape && page == 0 -> Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            CoverflowPage(viewModel = viewModel, accent = accent)
                        }
                        LandscapeBottomCluster(
                            viewModel = viewModel,
                            accent = accent,
                            backdrop = bgBackdrop,
                            songNow = songNow
                        )
                    }

                    // 横屏歌词页：左面板（封面+歌名+进度+控件）+ 右大字歌词
                    landscape && page == 1 -> LandscapeLyricsLayout(
                        viewModel = viewModel,
                        accent = accent,
                        backdrop = bgBackdrop,
                        songNow = songNow,
                        lyricsPage = {
                            LyricsPage(
                                lines = lyrics,
                                positionMs = uiState.currentPosition,
                                durationMs = uiState.duration,
                                isPlaying = isPlaying,
                                songKey = songKey,
                                accent = accent,
                                bgProfile = bgProfile,
                                bgBackdrop = bgBackdrop,
                                onSeek = viewModel::seekToMs
                            )
                        }
                    )

                    // 横屏队列页：队列列表 + 同款底部簇
                    landscape && page == 2 -> Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            QueuePage(viewModel = viewModel, accent = accent)
                        }
                        LandscapeBottomCluster(
                            viewModel = viewModel,
                            accent = accent,
                            backdrop = bgBackdrop,
                            songNow = songNow
                        )
                    }

                    // 封面页：黑胶唱片，上下滑切歌，点封面进歌词；底部实时频谱柱
                    page == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.92f)
                                .aspectRatio(1f)
                        ) {
                            SwipeToSwitch(
                                onSwipeUp = viewModel::next,
                                onSwipeDown = viewModel::previous
                            ) {
                                VinylCard(
                                    song = currentSong,
                                    artwork = artUri,
                                    isPlaying = isPlaying,
                                    accent = accent,
                                    modifier = Modifier.fillMaxSize(),
                                    onTap = { scope.launch { pagerState.animateScrollToPage(1) } }
                                )
                            }
                        }
                        // 频谱柱：贴在封面页底部（开关在音效面板里；暂停时自然回落）
                        SpectrumBars(
                            active = isPlaying,
                            accent = accent,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(0.86f)
                                .height(46.dp)
                                .padding(bottom = 6.dp)
                        )
                    }

                    // 歌词页：全屏主题歌词（玻璃已由外层铺满整屏 + 5 种样式 + 设置面板）
                    page == 1 -> LyricsPage(
                        lines = lyrics,
                        positionMs = uiState.currentPosition,
                        durationMs = uiState.duration,
                        isPlaying = isPlaying,
                        songKey = songKey,
                        accent = accent,
                        bgProfile = bgProfile,
                        bgBackdrop = bgBackdrop,
                        showGlass = false,
                        onSeek = viewModel::seekToMs
                    )

                    // 更多页：播放队列
                    else -> QueuePage(viewModel = viewModel, accent = accent)
                }
            }

            // Hi-Res 金标开关（设置页调）
            val hiresBadgeOn by DisplayPrefs.hiresEnabled.collectAsState()
            val hiresBadgeScale by DisplayPrefs.hiresScale.collectAsState()

            // 竖屏：金标 + 歌曲信息 + 进度条（横屏已收进各页底部簇）
            if (!landscape) {
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

                // 歌词页隐藏大歌名块（歌名已收进顶栏），把整块高度让给沉浸歌词
                if (pagerState.currentPage != 1) {
                    // 歌曲信息：标题跑马灯 + 「歌手 · 专辑 · 来源」副行
                    SongTextBlock(songNow, Modifier.fillMaxWidth())

                    Spacer(modifier = Modifier.height(18.dp))
                }

                // 进度条（独立组合，避免每 500ms 整页重组；激活段随封面主色渐变）
                // 歌词页用紧凑型并下移到控件正上方
                ProgressArea(
                    viewModel = viewModel,
                    accent = accent,
                    compact = pagerState.currentPage == 1
                )
            }
        }

            // 横屏：歌词设置/更多浮到右上角（省掉顶栏 44dp，封面墙更大）
            if (landscape) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(top = 4.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerTopActions(
                        onOpenLyricSettings = { showLyricSettings = true },
                        onOpenFx = { showFxSheet = true },
                        moreMenu = moreMenu,
                        onMoreMenuChange = { moreMenu = it },
                        onAddToPlaylist = { showAddSheet = true },
                        onGoLyrics = { scope.launch { pagerState.animateScrollToPage(1) } },
                        onGoQueue = { scope.launch { pagerState.animateScrollToPage(2) } }
                    )
                }
            }
        } // content wrapper

        // ── 播放控件 — 竖屏：sibling overlay，5 键标准控制栏（横屏已收进各页底部簇）──
        if (!landscape) {
            PlaybackGlassControls(
                backdrop = backdrop,
                isPlaying = isPlaying,
                playMode = playMode,
                modeLabel = playModeLabel,
                isFavorite = isFavorite,
                onPrevious = viewModel::previous,
                onTogglePlay = viewModel::togglePlay,
                onNext = viewModel::next,
                onToggleMode = viewModel::togglePlayMode,
                onToggleFavorite = viewModel::toggleFavorite,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = controlsBottomPadding)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                // 歌词页：控件整体微缩，给沉浸歌词让出更多空间
                scale = if (pagerState.currentPage == 1) 0.86f else 1f
            )
        }

        // ── 歌词设置面板（玻璃材质）──
        if (showLyricSettings) {
            com.whale.music.ui.lyrics.LyricSettingsSheet(
                songKey = songKey,
                bgBackdrop = bgBackdrop,
                onDismiss = { showLyricSettings = false }
            )
        }

        // ── 音效均衡面板（玻璃材质：EQ 预设/滑杆 + 频谱开关）──
        if (showFxSheet) {
            EqualizerSheet(
                bgBackdrop = bgBackdrop,
                onDismiss = { showFxSheet = false }
            )
        }

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
                            com.whale.music.ui.player.PlayerViewModel.REMOTE_BILI_ALBUM_ID -> "bili"
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

/** 顶栏右侧动作：歌词设置 + 音效 + 更多菜单（竖屏顶栏 / 横屏右上浮层共用） */
@Composable
private fun PlayerTopActions(
    onOpenLyricSettings: () -> Unit,
    onOpenFx: () -> Unit,
    moreMenu: Boolean,
    onMoreMenuChange: (Boolean) -> Unit,
    onAddToPlaylist: () -> Unit,
    onGoLyrics: () -> Unit,
    onGoQueue: () -> Unit
) {
    // 歌词设置（原歌词页右上角按钮挪到顶栏）
    PressableIconButton(
        onClick = onOpenLyricSettings,
        buttonSize = 44.dp,
        baseAlpha = 0f
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = "歌词设置",
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(19.dp)
        )
    }
    // 音效均衡（EQ + 频谱开关）
    PressableIconButton(
        onClick = onOpenFx,
        buttonSize = 44.dp,
        baseAlpha = 0f
    ) {
        Icon(
            Icons.Default.Equalizer,
            contentDescription = "音效均衡",
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(19.dp)
        )
    }
    Box {
        PressableIconButton(
            onClick = { onMoreMenuChange(true) },
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
            onDismissRequest = { onMoreMenuChange(false) },
            containerColor = Color(0xFF242426)
        ) {
            DropdownMenuItem(
                text = { Text("加入歌单", fontSize = 13.sp, color = Color.White) },
                onClick = { onMoreMenuChange(false); onAddToPlaylist() }
            )
            DropdownMenuItem(
                text = { Text("切到歌词", fontSize = 13.sp, color = Color.White) },
                onClick = { onMoreMenuChange(false); onGoLyrics() }
            )
            DropdownMenuItem(
                text = { Text("播放队列", fontSize = 13.sp, color = Color.White) },
                onClick = { onMoreMenuChange(false); onGoQueue() }
            )
            DropdownMenuItem(
                text = { Text("音效均衡", fontSize = 13.sp, color = Color.White) },
                onClick = { onMoreMenuChange(false); onOpenFx() }
            )
        }
    }
}

/**
 * 横屏底部簇（对照 AuraPlayer）：进度通栏 + 时间，
 * 下面歌名/歌手靠左、5 键玻璃控件靠右。放在各页内容之下、导航胶囊之上。
 */
@Composable
private fun LandscapeBottomCluster(
    viewModel: PlayerViewModel,
    accent: Color,
    backdrop: Backdrop,
    songNow: Song?
) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playMode by viewModel.playMode.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()
    val modeLabel = stringResource(
        when (playMode) {
            PlayMode.SEQUENCE -> R.string.play_mode_sequence
            PlayMode.REPEAT_ONE -> R.string.play_mode_repeat_one
            PlayMode.SHUFFLE -> R.string.play_mode_shuffle
        }
    )

    Column(Modifier.fillMaxWidth()) {
        ProgressArea(viewModel = viewModel, accent = accent)
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            SongTextBlock(songNow, Modifier.weight(1f), compact = true)
            PlaybackGlassControls(
                backdrop = backdrop,
                isPlaying = isPlaying,
                playMode = playMode,
                modeLabel = modeLabel,
                isFavorite = isFavorite,
                onPrevious = viewModel::previous,
                onTogglePlay = viewModel::togglePlay,
                onNext = viewModel::next,
                onToggleMode = viewModel::togglePlayMode,
                onToggleFavorite = viewModel::toggleFavorite,
                modifier = Modifier.width(320.dp)
            )
        }
    }
}

/** 歌曲信息块：标题跑马灯 + 「歌手 · 专辑 · 来源」副行（竖屏/横屏共用） */
@Composable
private fun SongTextBlock(
    songNow: Song?,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = songNow?.title ?: stringResource(R.string.status_not_playing),
            fontSize = if (compact) 20.sp else 29.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.basicMarquee()
        )
        Spacer(modifier = Modifier.height(if (compact) 3.dp else 5.dp))
        val srcLabel = when {
            songNow == null -> null
            songNow.albumId == PlayerViewModel.REMOTE_WY_ALBUM_ID -> "网易云"
            songNow.albumId == PlayerViewModel.REMOTE_LX_ALBUM_ID -> "自定义"
            songNow.albumId == PlayerViewModel.REMOTE_BILI_ALBUM_ID -> "B站"
            songNow.albumId < 0L -> "酷我"
            else -> "本地"
        }
        val subtitle = listOfNotNull(
            songNow?.artist?.takeIf { it.isNotBlank() },
            songNow?.album?.takeIf { it.isNotBlank() },
            srcLabel
        ).joinToString("  ·  ")
        Text(
            text = subtitle.ifBlank { "左右滑到「Whale-汽水」挑一首" },
            fontSize = 14.sp, color = Color.White.copy(alpha = 0.62f),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 黑胶唱片封面（模块 A）：
 * - 播放时匀速旋转、暂停即停（Animatable 保留角度，续播接着转）
 * - 外圈氛围光晕随播放呼吸
 * - 盘面沟槽 + 主色反光环 + 中心圆封面 + 轴孔
 * - 点按照旧切歌词；上下滑切歌由外层 SwipeToSwitch 处理
 */
@Composable
private fun VinylCard(
    song: Song?,
    artwork: Any?,
    isPlaying: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val context = LocalContext.current

    // 旋转角：播放转、暂停停
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                val from = rotation.value
                rotation.animateTo(from + 360f, tween(14_000, easing = LinearEasing))
            }
        } else {
            rotation.stop()
        }
    }

    // 呼吸：光晕亮度 + 唱片轻微缩放
    val pulseT = rememberInfiniteTransition(label = "vinylPulse")
    val breathe by pulseT.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2_200, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 外圈氛围光晕（呼吸）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (isPlaying) 0.30f + breathe * 0.40f else 0.22f
                }
                .background(
                    Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = 0.60f),
                            accent.copy(alpha = 0.16f),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )

        // 唱片本体
        Box(
            modifier = Modifier
                .fillMaxSize(0.84f)
                .graphicsLayer {
                    rotationZ = rotation.value
                    val s = 1f + if (isPlaying) breathe * 0.012f else 0f
                    scaleX = s
                    scaleY = s
                    shadowElevation = 18f
                    shape = CircleShape
                    ambientShadowColor = accent.copy(alpha = 0.5f)
                    spotShadowColor = accent.copy(alpha = 0.5f)
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF2C2C2F), Color(0xFF141416), Color(0xFF0C0C0E))
                    )
                )
                .then(
                    if (onTap != null) Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = onTap
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            // 沟槽 + 外缘高光 + 主色反光环
            Canvas(Modifier.fillMaxSize()) {
                val d = size.minDimension
                val stroke = 1.1f.dp.toPx()
                for (i in 0..7) {
                    drawCircle(
                        Color.White.copy(alpha = if (i % 2 == 0) 0.055f else 0.03f),
                        radius = d * (0.305f + i * 0.021f),
                        style = Stroke(width = stroke)
                    )
                }
                drawCircle(
                    Color.White.copy(alpha = 0.12f),
                    radius = d / 2f - 1.5f.dp.toPx(),
                    style = Stroke(width = 1.4f.dp.toPx())
                )
                drawCircle(
                    accent.copy(alpha = 0.35f),
                    radius = d * 0.462f,
                    style = Stroke(width = 1.6f.dp.toPx())
                )
            }

            // 中心封面标签（圆）
            Box(
                modifier = Modifier
                    .fillMaxSize(0.56f)
                    .clip(CircleShape)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(artwork)
                        .size(512, 512)
                        .build(),
                    contentDescription = song?.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.default_album_art),
                    error = painterResource(R.drawable.default_album_art),
                    fallback = painterResource(R.drawable.default_album_art)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize(0.56f)
                    .border(1.6.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            )
            // 唱片轴孔
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF08080A))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
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
 * 播放控件 — 5 键标准控制栏（模块 B）：
 * 模式 | 上一首 | 播放 | 下一首 | 喜欢
 * 玻璃按钮规范不变：drawBackdrop + CircleShape + vibrancy/blur/lens
 * + InteractiveHighlight 触摸高光 + layerBlock 按压缩放
 */
@Composable
internal fun PlaybackGlassControls(
    backdrop: Backdrop,
    isPlaying: Boolean,
    playMode: PlayMode,
    modeLabel: String,
    isFavorite: Boolean,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onToggleMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f
) {
    val animationScope = rememberCoroutineScope()

    Row(
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            // 锚在底边缩小：按钮贴着 bottom padding 往里收，不往上跳
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
        },
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 播放模式（顺序 / 单曲 / 随机）
        GlassButton(40.dp, onToggleMode, animationScope) {
            Icon(
                imageVector = when (playMode) {
                    PlayMode.SEQUENCE -> Icons.Default.Repeat
                    PlayMode.REPEAT_ONE -> Icons.Default.RepeatOne
                    PlayMode.SHUFFLE -> Icons.Default.Shuffle
                },
                contentDescription = modeLabel,
                tint = if (playMode == PlayMode.SEQUENCE) {
                    Color.White.copy(alpha = 0.55f)
                } else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        GlassButton(50.dp, onPrevious, animationScope) {
            Icon(
                Icons.Default.SkipPrevious,
                stringResource(R.string.action_previous),
                tint = TextPrimary,
                modifier = Modifier.size(26.dp)
            )
        }
        GlassButton(78.dp, onTogglePlay, animationScope) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
                tint = TextPrimary, modifier = Modifier.size(38.dp)
            )
        }
        GlassButton(50.dp, onNext, animationScope) {
            Icon(
                Icons.Default.SkipNext,
                stringResource(R.string.action_next),
                tint = TextPrimary,
                modifier = Modifier.size(26.dp)
            )
        }
        // 喜欢
        GlassButton(40.dp, onToggleFavorite, animationScope) {
            Icon(
                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = stringResource(
                    if (isFavorite) R.string.action_unfavorite else R.string.action_favorite
                ),
                tint = if (isFavorite) Color(0xFFFF5F7E) else Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 单个播放控制按钮（平面样式 —— 按用户要求去掉玻璃效果，不再折射背景）:
 * 1. drawBehind: 半透明白色圆底，按压时提亮
 * 2. graphicsLayer: 按压缩放 + 轻微跟随按压点位移
 * 3. clickable + InteractiveHighlight.modifier + gestureModifier
 */
@Composable
private fun GlassButton(
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
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.06f + 0.10f * p))
            }
            .graphicsLayer {
                scaleX = 1f - 0.06f * p
                scaleY = 1f - 0.06f * p
                val off = highlight.offset
                translationX = btnSize.toPx() * 0.015f * (off.x / btnSize.toPx() - 0.5f)
                translationY = btnSize.toPx() * 0.015f * (off.y / btnSize.toPx() - 0.5f)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .then(highlight.modifier)
            .then(highlight.gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * 动态氛围光（模块 C）— 两团随封面主色慢移、呼吸的径向色斑。
 * 状态只在 graphicsLayer 块里读，不引起整页重组。
 */
@Composable
private fun AuroraLayer(accent: Color) {
    val t = rememberInfiniteTransition(label = "aurora")
    val phase1 by t.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "p1"
    )
    val phase2 by t.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(34_000, easing = LinearEasing)), label = "p2"
    )
    val pulse1 by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(3_600, easing = LinearEasing), RepeatMode.Reverse),
        label = "pu1"
    )
    val pulse2 by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(4_400, easing = LinearEasing), RepeatMode.Reverse),
        label = "pu2"
    )

    // 画布尺寸：供色斑按比例漂移（普通 var，随动画帧重读）
    var canvasW = remember { 1f }
    var canvasH = remember { 1f }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged {
                canvasW = it.width.toFloat().coerceAtLeast(1f)
                canvasH = it.height.toFloat().coerceAtLeast(1f)
            }
    ) {
        // 主光：左上区慢移
        Box(
            Modifier
                .fillMaxSize(0.95f)
                .align(Alignment.TopStart)
                .graphicsLayer {
                    translationX = sin(phase1) * canvasW * 0.10f
                    translationY = cos(phase1) * canvasH * 0.05f
                    alpha = 0.45f + pulse1 * 0.55f
                }
                .background(
                    Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = 0.50f),
                            accent.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )
        // 副光：右下区慢移（相位错开）
        Box(
            Modifier
                .fillMaxSize(0.8f)
                .align(Alignment.BottomEnd)
                .graphicsLayer {
                    translationX = cos(phase2) * canvasW * 0.08f
                    translationY = sin(phase2) * canvasH * 0.04f
                    alpha = 0.40f + pulse2 * 0.50f
                }
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.38f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 进度条 + 时间 — 发光胶囊进度条（Capsule Bloom），独立组合避免整页重组（模块 B）
 *  @param compact 歌词页紧凑型：细条 + 小号时间，给沉浸歌词让位 */
@Composable
internal fun ProgressArea(viewModel: PlayerViewModel, accent: Color, compact: Boolean = false) {
    val uiState by viewModel.uiState.collectAsState()
    val progress = uiState.progress
    val currentPosition = uiState.currentPosition
    val duration = uiState.duration

    // 歌词页：细线模式（3.5dp 轨 + 小光环），其余页面维持 Capsule Bloom 粗条
    GlowProgressBar(
        value = progress,
        accent = accent,
        modifier = Modifier.fillMaxWidth().height(if (compact) 14.dp else 28.dp),
        thickness = if (compact) 3.5.dp else 6.5.dp,
        dragThickness = if (compact) 5.5.dp else 8.5.dp,
        haloSize = if (compact) 11.dp else 18.5.dp,
        onSeek = { viewModel.seekTo(it) },
        onSeekFinished = { viewModel.seekTo(it) }
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(currentPosition), fontSize = if (compact) 10.sp else 12.sp, color = Color.White.copy(alpha = 0.6f))
        Text(formatTime(duration), fontSize = if (compact) 10.sp else 12.sp, color = Color.White.copy(alpha = 0.6f))
    }
}
