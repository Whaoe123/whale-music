package com.whale.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.whale.music.ui.StartupViewModel
import com.whale.music.ui.components.SimpleBottomTabs
import com.whale.music.ui.discover.DiscoverScreen
import com.whale.music.ui.player.MiniPlayerBar
import com.whale.music.ui.player.PlayerScreen
import com.whale.music.ui.player.PlayerViewModel
import com.whale.music.ui.playlist.PlaylistScreen
import com.whale.music.ui.qishui.QishuiHomeScreen
import com.whale.music.ui.theme.OHMusicTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val hasAudioPermission = MutableStateFlow(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasAudioPermission.value = checkAudioPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        hasAudioPermission.value = checkAudioPermission()

        setContent {
            OHMusicTheme {
                val backdrop = rememberLayerBackdrop()
                val saveableStateHolder = rememberSaveableStateHolder()

                var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                // 四页 pager：播放器 / Whale-汽水 / Whale-网易云 / 曲库
                val pagerState = rememberPagerState(initialPage = selectedTab) { 4 }
                val tabScope = rememberCoroutineScope()
                val canReadAudio by hasAudioPermission.collectAsState()

                // 迷你条要读播放器状态；和播放页共用同一个 ViewModel 实例
                val playerVm: PlayerViewModel = hiltViewModel()
                val miniSong by playerVm.currentSong.collectAsState()
                val miniPlaying by playerVm.isPlaying.collectAsState()
                val miniArtwork by playerVm.artwork.collectAsState()

                // 冷启动自动播放（未登录 → 免登录榜单；已登录 → 网易云日推）
                val startup: StartupViewModel = hiltViewModel()

                // 迷你条是否收起成 CD 按钮（下滑收起 / 点击 CD 展开）
                var miniCollapsed by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }.collect { selectedTab = it }
                }

                // 不在播放页时，返回键先回播放页（相当于「收起」）
                BackHandler(enabled = selectedTab != 0) {
                    selectedTab = 0
                    tabScope.launch { pagerState.animateScrollToPage(0) }
                }

                // 先提交首帧，再请求运行时权限，避免权限窗口阻塞冷启动绘制。
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    requestAudioPermission()
                    startup.maybeAutoPlay()
                }

                val onPlayerPage = selectedTab == 0

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .layerBackdrop(backdrop)
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1,
                            flingBehavior = PagerDefaults.flingBehavior(
                                state = pagerState,
                                pagerSnapDistance = PagerSnapDistance.atMost(1),
                                snapAnimationSpec = spring(
                                    dampingRatio = 0.62f,
                                    stiffness = 380f
                                ),
                                snapPositionalThreshold = 0.28f
                            ),
                            key = { it }
                        ) { page ->
                            // 页面随滑动进度做缩放 / 圆角 / 透明度过渡，像玻璃卡片层叠
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val offset = (pagerState.currentPage - page) +
                                            pagerState.currentPageOffsetFraction
                                        val f = abs(offset).coerceIn(0f, 1f)
                                        val q = f * f

                                        val scale = 1f - 0.10f * q
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = 1f - 0.35f * q
                                        translationY = 18.dp.toPx() * q
                                        translationX = 14.dp.toPx() * q * if (offset > 0f) 1f else -1f
                                        shape = RoundedCornerShape(24.dp * q)
                                        clip = q > 0.002f
                                    }
                            ) {
                                saveableStateHolder.SaveableStateProvider(page) {
                                    when (page) {
                                        // 第一页就是播放器：打开 App 直接看到大封面
                                        0 -> PlayerScreen(
                                            modifier = Modifier.fillMaxSize(),
                                            controlsBottomPadding = 92.dp
                                        )

                                        1 -> QishuiHomeScreen(modifier = Modifier.fillMaxSize())

                                        2 -> DiscoverScreen(modifier = Modifier.fillMaxSize())

                                        else -> PlaylistScreen(
                                            hasAudioPermission = canReadAudio,
                                            onRequestPermission = ::requestAudioPermission,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── 玻璃迷你播放条：后三页常驻，点一下回播放页 ──
                    //    第一页本身就是播放器，这里淡出并让出手势区域
                    AnimatedVisibility(
                        visible = !onPlayerPage,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(140)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .zIndex(2f)
                    ) {
                        MiniPlayerBar(
                            backdrop = backdrop,
                            song = miniSong,
                            isPlaying = miniPlaying,
                            artwork = miniArtwork,
                            progress = playerVm.progress,
                            collapsed = miniCollapsed,
                            onCollapsedChange = { miniCollapsed = it },
                            onTogglePlay = playerVm::togglePlay,
                            onNext = playerVm::next,
                            onPrevious = playerVm::previous,
                            onOpen = {
                                selectedTab = 0
                                tabScope.launch { pagerState.animateScrollToPage(0) }
                            },
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 92.dp)
                        )
                    }

                    // 底部导航栏
                    SimpleBottomTabs(
                        backdrop = backdrop,
                        selectedIndex = selectedTab,
                        liveFraction = {
                            pagerState.currentPage + pagerState.currentPageOffsetFraction
                        },
                        onTabSelected = { index ->
                            selectedTab = index
                            tabScope.launch { pagerState.animateScrollToPage(index) }
                        },
                        tabLabels = listOf(
                            stringResource(R.string.tab_player),
                            stringResource(R.string.tab_qishui),
                            stringResource(R.string.tab_discover),
                            stringResource(R.string.tab_library)
                        ),
                        tabContent = { index, isSelected ->
                            val icon = when (index) {
                                0 -> Icons.Default.MusicNote
                                1 -> Icons.Default.Star
                                2 -> Icons.Default.Explore
                                else -> Icons.AutoMirrored.Filled.QueueMusic
                            }
                            val iconSize by animateDpAsState(
                                targetValue = if (isSelected) 25.dp else 21.dp,
                                animationSpec = spring(dampingRatio = 0.45f, stiffness = 620f),
                                label = "tabIconSize"
                            )
                            val iconAlpha by animateFloatAsState(
                                targetValue = if (isSelected) 1f else 0.42f,
                                animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
                                label = "tabIconAlpha"
                            )
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = iconAlpha),
                                modifier = Modifier.size(iconSize)
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .width(232.dp)
                            .padding(bottom = 28.dp)
                            .zIndex(3f)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasAudioPermission.value = checkAudioPermission()
    }

    private fun requestAudioPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needsRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needsRequest) {
            permissionLauncher.launch(permissions)
        }
    }

    private fun checkAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
