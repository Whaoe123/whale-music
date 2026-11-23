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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.whale.music.ui.player.PlayerMorphLayer
import com.whale.music.ui.player.PlayerScreen
import com.whale.music.ui.player.rememberPlayerMorphController
import com.whale.music.ui.player.smoothstep
import com.whale.music.ui.player.PlayerViewModel
import com.whale.music.ui.playlist.PlaylistScreen
import com.whale.music.ui.settings.SettingsScreen
import com.whale.music.ui.theme.OHMusicTheme
import com.whale.music.ui.update.UpdateDialog
import com.whale.music.update.UpdateViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
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
                // 播放页开关（设置页「界面」）：关掉后 pager 从 4 页变 3 页
                val playerPageOn by com.whale.music.util.DisplayPrefs.playerPageEnabled.collectAsState()
                // 页面 id 映射：0 播放 / 1 发现 / 2 音乐库 / 3 设置
                val pageIds = if (playerPageOn) listOf(0, 1, 2, 3) else listOf(1, 2, 3)
                // 四页 pager：播放器 / 发现 / 音乐库 / 设置
                val pagerState = rememberPagerState(initialPage = selectedTab.coerceAtMost(pageIds.size - 1)) { pageIds.size }
                val tabScope = rememberCoroutineScope()
                val canReadAudio by hasAudioPermission.collectAsState()

                // 迷你条要读播放器状态；和播放页共用同一个 ViewModel 实例
                val playerVm: PlayerViewModel = hiltViewModel()
                val miniSong by playerVm.currentSong.collectAsState()
                val miniPlaying by playerVm.isPlaying.collectAsState()
                val miniArtwork by playerVm.artwork.collectAsState()

                // 冷启动自动播放（未登录 → 免登录榜单；已登录 → 网易云日推）
                val startup: StartupViewModel = hiltViewModel()

                // 应用内更新：启动后延迟检查，有新版本才弹窗
                val updateVm: UpdateViewModel = hiltViewModel()
                val updateState by updateVm.state.collectAsState()
                val updateNote by updateVm.note.collectAsState()
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    delay(3_500)
                    // 自动检查：静默，只有真有新版本才弹窗
                    updateVm.check(silent = true)
                }
                // 检查结果（失败原因 / 版本对照）用 Toast 说一声，避免"看着像没在更新"
                val updateToastContext = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(updateNote) {
                    updateNote?.let { msg ->
                        android.widget.Toast.makeText(
                            updateToastContext,
                            msg,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        updateVm.consumeNote()
                    }
                }

                // 迷你条是否收起成 CD 按钮（下滑收起 / 点击 CD 展开）
                var miniCollapsed by rememberSaveable { mutableStateOf(false) }
                // 悬浮卡片 → 播放页形变：点卡片展开成播放页，返回键沿弹簧收回到卡片
                var playerMorphOpen by rememberSaveable { mutableStateOf(false) }
                // 卡片当前矩形（窗口坐标），作为形变展开的起点（打开瞬间锁定）
                var morphSourceBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
                // 形变控制器：卡片上拖跟手展开 / 播放页下滑跟手收起，与形变层共享进度
                val morphController = rememberPlayerMorphController()

                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }.collect { selectedTab = it }
                }

                // 播放页开关切换后停留在设置页（开关在设置页里操作）；打开开关时收掉浮层
                var prevPlayerPageOn by remember { mutableStateOf(playerPageOn) }
                LaunchedEffect(playerPageOn) {
                    if (playerPageOn != prevPlayerPageOn) {
                        prevPlayerPageOn = playerPageOn
                        if (playerPageOn) playerMorphOpen = false
                        val idx = pageIds.indexOf(3)
                        selectedTab = idx
                        pagerState.animateScrollToPage(idx)
                    }
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

                val onPlayerPage = playerPageOn && selectedTab == 0
                // 底部导航栏开关（设置页「界面」，默认开启）
                val navBarOn by com.whale.music.util.DisplayPrefs.navBarEnabled.collectAsState()
                // 横屏：底部簇（导航+迷你条）紧凑化，少占屏幕
                val landscape = LocalConfiguration.current.let {
                    it.screenWidthDp > it.screenHeightDp
                }

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
                            key = { pageIds[it] }
                        ) { page ->
                            val pageId = pageIds[page]
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
                                saveableStateHolder.SaveableStateProvider(pageId) {
                                    when (pageId) {
                                        // 第一页就是播放器：打开 App 直接看到大封面
                                        0 -> PlayerScreen(
                                            modifier = Modifier.fillMaxSize(),
                                            controlsBottomPadding = if (navBarOn) 92.dp else 24.dp,
                                            onOpenSettings = {
                                                val idx = pageIds.indexOf(3)
                                                selectedTab = idx
                                                tabScope.launch { pagerState.animateScrollToPage(idx) }
                                            }
                                        )

                                        1 -> DiscoverScreen(modifier = Modifier.fillMaxSize())

                                        2 -> PlaylistScreen(
                                            hasAudioPermission = canReadAudio,
                                            onRequestPermission = ::requestAudioPermission,
                                            modifier = Modifier.fillMaxSize()
                                        )

                                        else -> SettingsScreen(modifier = Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }

                    // ── 玻璃悬浮卡片：后三页常驻，点一下从卡片形变展开进播放页 ──
                    //    第一页本身就是播放器，这里淡出并让出手势区域
                    AnimatedVisibility(
                        visible = !onPlayerPage && !playerMorphOpen,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(140)),
                        modifier = Modifier
                            // 拖拽展开时卡片随进度淡出（玻璃壳接管视觉）
                            .graphicsLayer {
                                alpha = 1f - smoothstep(0.05f, 0.38f, morphController.progress)
                            }
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
                                // 两种模式都走形变动画：卡片玻璃壳长成全屏播放页
                                playerMorphOpen = true
                            },
                            morph = morphController,
                            onCardBounds = { morphSourceBounds = it },
                            modifier = Modifier
                                .padding(horizontal = if (landscape) 12.dp else 16.dp)
                                .padding(
                                    bottom = when {
                                        !navBarOn -> if (landscape) 12.dp else 16.dp
                                        landscape -> 58.dp
                                        else -> 92.dp
                                    }
                                )
                        )
                    }

                    // 底部导航栏（设置页「界面」可隐藏；关掉后左右滑动切页）
                    AnimatedVisibility(
                        visible = navBarOn,
                        enter = fadeIn(tween(180)),
                        exit = fadeOut(tween(150)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .zIndex(3f)
                    ) {
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
                            tabLabels = pageIds.map { id ->
                                stringResource(
                                    when (id) {
                                        0 -> R.string.tab_player
                                        1 -> R.string.tab_discover
                                        2 -> R.string.tab_library
                                        else -> R.string.tab_settings
                                    }
                                )
                            },
                            tabContent = { index, isSelected ->
                                val icon = when (pageIds[index]) {
                                    0 -> Icons.Default.MusicNote
                                    1 -> Icons.Default.Explore
                                    2 -> Icons.AutoMirrored.Filled.QueueMusic
                                    else -> Icons.Default.Settings
                                }
                                val iconSize by animateDpAsState(
                                    targetValue = (if (isSelected) 25.dp else 21.dp) *
                                        (if (landscape) 0.82f else 1f),
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
                                .width(if (landscape) 196.dp else 232.dp)
                                .padding(bottom = if (landscape) 12.dp else 28.dp)
                        )
                    }

                    // ── 悬浮卡片 → 播放页形变层（点卡片展开 / 返回键收回，材质同卡片玻璃）──
                    PlayerMorphLayer(
                        backdrop = backdrop,
                        controller = morphController,
                        open = playerMorphOpen,
                        onOpenChange = { playerMorphOpen = it },
                        sourceBounds = { morphSourceBounds },
                        modifier = Modifier.zIndex(4f)
                    ) {
                        PlayerScreen(
                            modifier = Modifier.fillMaxSize(),
                            controlsBottomPadding = if (navBarOn) 92.dp else 24.dp,
                            onOpenSettings = {
                                playerMorphOpen = false
                                val idx = pageIds.indexOf(3)
                                selectedTab = idx
                                tabScope.launch { pagerState.animateScrollToPage(idx) }
                            }
                        )
                    }

                    // ── 应用内更新弹窗：盖在所有页面之上 ──
                    when (updateState) {
                        is UpdateViewModel.State.Available,
                        is UpdateViewModel.State.Downloading,
                        is UpdateViewModel.State.Ready,
                        is UpdateViewModel.State.Failed -> UpdateDialog(
                            state = updateState,
                            onDismiss = updateVm::dismiss,
                            onUpdate = updateVm::download,
                            onInstall = updateVm::install
                        )

                        else -> Unit
                    }
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
