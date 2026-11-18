package com.whale.music.ui.discover

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.Dialog
import com.whale.music.ncm.NcmLoginState
import com.whale.music.ui.ncm.NcmViewModel
import com.whale.music.ui.ncm.QrLoginDialog
import com.whale.music.ui.ncm.WebLoginDialog
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.whale.music.R
import androidx.compose.ui.res.painterResource
import com.whale.music.data.model.RemoteSong
import com.whale.music.data.sync.SongKey
import com.whale.music.ui.components.PressableIconButton
import com.whale.music.ui.components.pressScale

/** 底部导航 + 迷你条占位高度（与第二页一致） */
private val BOTTOM_RESERVE = 170.dp

/**
 * 爱心是否点亮：本地在线收藏按 songId 判定；网易云红心按歌名+歌手归一化 key 判定。
 * favoriteIds 里同时混有这两类字符串，任一命中即点亮（只读，不写回网易云）。
 */
private fun isFavoriteSong(song: RemoteSong, favoriteIds: Set<String>): Boolean =
    song.songId in favoriteIds ||
        SongKey.variants(song.title, song.artist).any { it in favoriteIds }

/**
 * 发现页 — 歌单推荐 + 搜索 + 在线收藏 + 下载
 * 视觉：与音乐库一致的深色玻璃卡片风格
 */
@Composable
fun DiscoverScreen(
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val favorites by viewModel.remoteFavorites.collectAsState()
    val likedKeys by viewModel.likedKeys.collectAsState()
    val artwork by viewModel.artwork.collectAsState()
    val context = LocalContext.current

    // 网易云账号：登录态只用于「我的日推 / 红心」，不影响下方搜索与播放
    val ncmVm: NcmViewModel = hiltViewModel()
    val ncmLogin by ncmVm.loginState.collectAsState()
    val ncmProfile by ncmVm.profile.collectAsState()
    val ncmError by ncmVm.error.collectAsState()
    val ncmSyncMessage by ncmVm.syncMessage.collectAsState()
    // 0=关闭 1=登录方式菜单 2=扫码 3=网页 4=粘贴 Cookie
    var loginStage by remember { mutableStateOf(0) }
    var pasteCookie by remember { mutableStateOf("") }
    // 搜索框是否展开（与第二页同一套交互）
    var searchMode by remember { mutableStateOf(false) }
    // 长按待加入歌单的在线歌
    var pendingAdd by remember { mutableStateOf<RemoteSong?>(null) }
    val statusBarTop = androidx.compose.foundation.layout.WindowInsets.statusBars
        .asPaddingValues().calculateTopPadding()

    // 消息提示自动消失
    LaunchedEffect(uiState.message) {
        if (uiState.message != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(uiState.error) {
        if (uiState.error != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.consumeMessage()
        }
    }
    // 登录成功 → 立即把「我喜欢的音乐」全量同步进曲库
    LaunchedEffect(ncmLogin) {
        if (ncmLogin == com.whale.music.ncm.NcmLoginState.LOGGED_IN) {
            ncmVm.syncLikedToLibrary()
            viewModel.loadHome(force = true) // 切到「我的每日推荐」
        }
    }
    LaunchedEffect(ncmSyncMessage) {
        if (ncmSyncMessage != null) {
            android.widget.Toast.makeText(context, ncmSyncMessage, android.widget.Toast.LENGTH_SHORT).show()
            ncmVm.consumeSyncMessage()
        }
    }

    // 本地在线收藏 + 网易云红心（只读点亮，按歌名+歌手归一化匹配）
    val favoriteIds = favorites.map { it.songId }.toSet() + likedKeys

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── 与第二页完全一致的封面模糊背景（尺寸 / 模糊半径 / 交叉淡入同参数）──
        androidx.compose.animation.Crossfade(
            targetState = artwork,
            animationSpec = androidx.compose.animation.core.tween(1200),
            label = "wy_bg"
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
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(statusBarTop + 8.dp))

            if (uiState.openedPlaylist != null) {
                // ── 歌单详情视图 ──
                PlaylistDetailView(
                    playlist = uiState.openedPlaylist!!,
                    songs = uiState.playlistSongs,
                    loading = uiState.playlistLoading,
                    favoriteIds = favoriteIds,
                    downloadingId = uiState.downloadingId,
                    resolving = uiState.resolving,
                    currentTitle = null,
                    onBack = viewModel::closePlaylist,
                    onPlay = viewModel::play,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onDownload = viewModel::download,
                    onLongPress = { pendingAdd = it }
                )
            } else {
                // ── 顶栏：尺寸与第二页完全一致（22sp 标题 / 11sp 副标题 / 44dp 图标按钮）──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Whale-网易云",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (ncmLogin == NcmLoginState.LOGGED_IN) {
                                "已登录 · 为你挑歌"
                            } else {
                                "登录后推荐更准，点歌名即播"
                            },
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.42f)
                        )
                    }

                    PressableIconButton(
                        onClick = {
                            searchMode = !searchMode
                            if (!searchMode) viewModel.clearSearch()
                        },
                        buttonSize = 44.dp,
                        baseAlpha = 0.10f
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    NcmAccountChip(
                        loggedIn = ncmLogin == NcmLoginState.LOGGED_IN,
                        nickname = ncmProfile?.nickname.orEmpty(),
                        onClick = { loginStage = 1 }
                    )

                    Spacer(Modifier.width(6.dp))

                    PressableIconButton(
                        onClick = { viewModel.loadHome(force = true) },
                        buttonSize = 44.dp,
                        baseAlpha = 0.10f
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新推荐",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 搜索框（点顶栏搜索按钮展开，样式与第二页同一套）──
                if (searchMode) {
                    DiscoverSearchBar(
                        query = uiState.query,
                        onQueryChange = viewModel::onQueryChange,
                        onClear = viewModel::clearSearch,
                        onCancel = {
                            searchMode = false
                            viewModel.clearSearch()
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                }

                when {
                    // ── 搜索结果：行距 / 底部留白与第二页一致 ──
                    searchMode && uiState.query.isNotBlank() -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = BOTTOM_RESERVE),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (uiState.searching) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = Color.White.copy(alpha = 0.5f),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        } else {
                            val results = uiState.searchResults.orEmpty()
                            if (results.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(100.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "没搜到相关歌曲",
                                            fontSize = 13.sp,
                                            color = Color.White.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            }
                            itemsIndexed(results, key = { i, s -> "${s.songId}#$i" }) { i, s ->
                                RemoteSongCard(
                                    song = s,
                                    index = i,
                                    isPlaying = false,
                                    isFavorite = isFavoriteSong(s, favoriteIds),
                                    downloading = uiState.downloadingId == s.songId,
                                    busy = uiState.resolving,
                                    showDownload = true,
                                    onClick = { viewModel.play(s, results) },
                                    onFavoriteToggle = { viewModel.toggleFavorite(s) },
                                    onDownload = { viewModel.download(s) },
                                    onLongPress = { pendingAdd = s }
                                )
                            }
                        }
                    }

                    uiState.loading && uiState.recommend.isEmpty() && uiState.playlists.isEmpty() -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White.copy(alpha = 0.55f),
                            strokeWidth = 2.dp
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = BOTTOM_RESERVE),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp)
                            ) {
                                items(uiState.playlists, key = { it.id }) { pl ->
                                    PlaylistCard(
                                        playlist = pl,
                                        onClick = { viewModel.openPlaylist(pl) }
                                    )
                                }
                            }
                        }

                        item { Spacer(Modifier.height(18.dp)) }

                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 22.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (uiState.personalized) "我的每日推荐" else "推荐歌曲",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (uiState.personalized) {
                                            "${uiState.recommend.size} 首 · 按你的口味"
                                        } else {
                                            "${uiState.recommend.size} 首 · 登录后更懂你"
                                        },
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.38f)
                                    )
                                }
                                Text(
                                    text = "播放全部",
                                    fontSize = 12.sp,
                                    color = Color(0xFF7DD3FC),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable {
                                            uiState.recommend.firstOrNull()?.let {
                                                viewModel.play(it, uiState.recommend)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        item { Spacer(Modifier.height(6.dp)) }

                        if (uiState.recommend.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "没有拿到推荐内容",
                                        fontSize = 14.sp,
                                        color = Color.White.copy(alpha = 0.45f)
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = "点右上角刷新重试",
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.28f)
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(
                                uiState.recommend,
                                key = { i, s -> "${s.songId}#$i" }
                            ) { index, song ->
                                RemoteSongCard(
                                    song = song,
                                    index = index,
                                    isPlaying = false,
                                    isFavorite = isFavoriteSong(song, favoriteIds),
                                    downloading = uiState.downloadingId == song.songId,
                                    busy = uiState.resolving,
                                    onClick = { viewModel.play(song, uiState.recommend) },
                                    onFavoriteToggle = { viewModel.toggleFavorite(song) },
                                    onDownload = { viewModel.download(song) },
                                    onLongPress = { pendingAdd = song }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 底部消息条（样式与第二页一致）──
        uiState.message?.let { msg ->
            Text(
                text = msg,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 190.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        // ── 网易云登录 ──
        when (loginStage) {
            1 -> NcmLoginMenu(
                loggedIn = ncmLogin == NcmLoginState.LOGGED_IN,
                nickname = ncmProfile?.nickname.orEmpty(),
                error = ncmError,
                onQr = { ncmVm.consumeError(); loginStage = 2 },
                onWeb = { ncmVm.consumeError(); loginStage = 3 },
                onPaste = { ncmVm.consumeError(); loginStage = 4 },
                onLogout = {
                    ncmVm.logout()
                    loginStage = 0
                },
                onDismiss = { ncmVm.consumeError(); loginStage = 0 }
            )

            2 -> QrLoginDialog(
                viewModel = ncmVm,
                onDismiss = { loginStage = 0 },
                onSwitchToWeb = { loginStage = 3 }
            )

            3 -> WebLoginDialog(
                onCookie = { raw ->
                    ncmVm.loginWithCookie(raw)
                    loginStage = 0
                },
                onSwitchToPaste = { loginStage = 4 },
                onDismiss = { loginStage = 0 }
            )

            4 -> NcmPasteDialog(
                value = pasteCookie,
                onChange = { pasteCookie = it },
                error = ncmError,
                onConfirm = {
                    ncmVm.loginWithCookie(pasteCookie)
                    loginStage = 0
                },
                onDismiss = { ncmVm.consumeError(); loginStage = 0 }
            )
        }

        // ── 长按歌曲 → 加入自建歌单 ──
        pendingAdd?.let { song ->
            com.whale.music.ui.playlist.AddToPlaylistSheet(
                remoteSong = song,
                onDismiss = { pendingAdd = null }
            )
        }
    }
}

/** 网易云登录方式菜单：扫码 / 网页 / 粘贴 Cookie */
@Composable
private fun NcmLoginMenu(
    loggedIn: Boolean,
    nickname: String,
    error: String?,
    onQr: () -> Unit,
    onWeb: () -> Unit,
    onPaste: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF21C1C1E))
                .padding(20.dp)
        ) {
            Text("网易云账号", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                if (loggedIn) "已登录：$nickname\n登录后可用「我的每日推荐 / 红心歌单」" else
                    "登录后可使用「我的每日推荐 / 红心歌单」，听歌仍走酷我出声",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(16.dp))

            if (loggedIn) {
                LoginMenuRow("退出登录", onLogout, danger = true)
            } else {
                LoginMenuRow("扫码登录（推荐）", onQr)
                LoginMenuRow("网页登录", onWeb)
                LoginMenuRow("粘贴 Cookie（最稳）", onPaste)
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 12.sp, color = Color(0xFFFF8A80))
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) {
                Text("关闭", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun LoginMenuRow(text: String, onClick: () -> Unit, danger: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Text(
            text,
            fontSize = 14.sp,
            color = if (danger) Color(0xFFFF8A80) else Color.White
        )
    }
    Spacer(Modifier.height(8.dp))
}

/** 粘贴 Cookie 登录 */
@Composable
private fun NcmPasteDialog(
    value: String,
    onChange: (String) -> Unit,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF21C1C1E))
                .padding(20.dp)
        ) {
            Text("粘贴 Cookie", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                "浏览器打开 music.163.com 并登录 → 开发者工具 / 任意 Cookie 查看插件 → 复制整段 Cookie（必须包含 MUSIC_U）",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(12.dp)
            ) {
                if (value.isEmpty()) {
                    Text("MUSIC_U=...; __csrf=...", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.fillMaxSize()
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = Color(0xFFFF8A80))
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
                TextButton(onClick = onConfirm) {
                    Text("登录", color = Color(0xFF64D2FF), fontSize = 13.sp)
                }
            }
        }
    }
}

// ───────── 搜索框（与第二页同一套：胶囊 / 13.5sp / 17dp 图标 / 取消字） ─────────

@Composable
private fun DiscoverSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
            if (query.isEmpty()) {
                Text(
                    "搜索歌曲、歌手",
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 13.5.sp
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 13.5.sp),
                cursorBrush = SolidColor(Color(0xFF7DD3FC)),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Close,
                contentDescription = "清空",
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onClear() }
            )
        } else {
            Spacer(Modifier.width(8.dp))
            Text(
                "取消",
                fontSize = 12.sp,
                color = Color(0xFF7DD3FC),
                modifier = Modifier.clickable { onCancel() }
            )
        }
    }
}

// ───────── 歌单卡片（横向流，尺寸同第二页 108dp） ─────────

@Composable
private fun PlaylistCard(
    playlist: WyPlaylist,
    onClick: () -> Unit
) {
    val pressSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(108.dp)
            .pressScale(pressSource, pressedScale = 0.94f)
            .clickable(
                interactionSource = pressSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(playlist.picUrl)
                    .size(300, 300)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(R.drawable.default_album_art),
                error = painterResource(R.drawable.default_album_art),
                fallback = painterResource(R.drawable.default_album_art)
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = playlist.name,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ───────── 歌单详情 ─────────

@Composable
private fun PlaylistDetailView(
    playlist: WyPlaylist,
    songs: List<RemoteSong>,
    loading: Boolean,
    favoriteIds: Set<String>,
    downloadingId: String?,
    resolving: Boolean,
    currentTitle: String?,
    onBack: () -> Unit,
    onPlay: (RemoteSong, List<RemoteSong>) -> Unit,
    onToggleFavorite: (RemoteSong) -> Unit,
    onDownload: (RemoteSong) -> Unit,
    onLongPress: ((RemoteSong) -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PressableIconButton(
                onClick = onBack,
                buttonSize = 44.dp,
                baseAlpha = 0.10f
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (loading) "正在加载歌曲…" else "共 ${songs.size} 首",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.38f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.6f),
                    strokeWidth = 2.dp
                )
            }
            songs.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "歌单加载失败或为空",
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
            else -> RemoteSongList(
                songs = songs,
                favoriteIds = favoriteIds,
                downloadingId = downloadingId,
                resolving = resolving,
                playingSongId = null,
                onPlay = onPlay,
                onToggleFavorite = onToggleFavorite,
                onDownload = onDownload,
                onLongPress = onLongPress
            )
        }
    }
}

@Composable
private fun RemoteSongList(
    songs: List<RemoteSong>,
    favoriteIds: Set<String>,
    downloadingId: String?,
    resolving: Boolean,
    playingSongId: String?,
    onPlay: (RemoteSong, List<RemoteSong>) -> Unit,
    onToggleFavorite: (RemoteSong) -> Unit,
    onDownload: (RemoteSong) -> Unit,
    /** 只有搜索结果带下载按钮 */
    showDownload: Boolean = false,
    onLongPress: ((RemoteSong) -> Unit)? = null
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = BOTTOM_RESERVE)
    ) {
        itemsIndexed(songs, key = { i, s -> "${s.songId}#$i" }) { index, song ->
            RemoteSongCard(
                song = song,
                index = index,
                isPlaying = playingSongId == song.songId,
                isFavorite = isFavoriteSong(song, favoriteIds),
                downloading = downloadingId == song.songId,
                busy = resolving,
                showDownload = showDownload,
                onClick = { onPlay(song, songs) },
                onFavoriteToggle = { onToggleFavorite(song) },
                onDownload = { onDownload(song) },
                onLongPress = onLongPress?.let { { it(song) } }
            )
        }
    }
}

// ───────── 在线歌曲卡片（与汽水页同款排版：序号 / 封面 / 歌名歌手 / 爱心 / 时长）─────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RemoteSongCard(
    song: RemoteSong,
    isPlaying: Boolean,
    isFavorite: Boolean,
    downloading: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onDownload: () -> Unit,
    /** 列表序号，-1 表示不显示（如「我喜欢的」这种无固定顺序的列表） */
    index: Int = -1,
    /** 是否显示下载按钮：只有搜索结果需要，歌单/推荐里不显示 */
    showDownload: Boolean = false,
    /** 长按：加入自建歌单 */
    onLongPress: (() -> Unit)? = null
) {
    val pressSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    val containerAlpha = if (isPlaying) 0.16f else 0.05f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .pressScale(pressSource, pressedScale = 0.98f)
            .clip(shape)
            .background(Color.White.copy(alpha = containerAlpha))
            .combinedClickable(
                interactionSource = pressSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 序号（正在播放时高亮）
        if (index >= 0) {
            Box(
                modifier = Modifier.size(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    fontSize = 11.sp,
                    color = if (isPlaying) Color(0xFF7DD3FC) else Color.White.copy(alpha = 0.3f)
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        // 封面
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(com.whale.music.util.CoverUrl.hd(song.coverUrl).ifBlank { null })
                    .size(140, 140)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(R.drawable.default_album_art),
                error = painterResource(R.drawable.default_album_art),
                fallback = painterResource(R.drawable.default_album_art)
            )
        }

        Spacer(Modifier.width(11.dp))

        // 歌名 + 歌手
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontSize = 14.sp,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                color = if (isPlaying) Color(0xFF7DD3FC) else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.artist,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.42f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 搜索结果的下载按钮（其他地方不显示，省得到处都是下载）
        if (showDownload) {
            PressableIconButton(
                onClick = onDownload,
                buttonSize = 38.dp,
                enabled = !busy && !downloading,
                baseAlpha = 0.06f
            ) {
                Icon(
                    imageVector = if (downloading) Icons.Default.DownloadDone else Icons.Default.Download,
                    contentDescription = "下载",
                    tint = if (downloading) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(2.dp))
        }

        // 爱心
        Icon(
            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (isFavorite) "取消喜欢" else "加入喜欢",
            tint = if (isFavorite) Color(0xFFF472B6) else Color.White.copy(alpha = 0.30f),
            modifier = Modifier
                .size(17.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onFavoriteToggle() }
        )

        Spacer(Modifier.width(9.dp))

        Text(
            text = formatRemoteDuration(song.duration),
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.28f)
        )
    }
}

// ───────── 账号入口（外观与第二页 AccountChip 一致）─────────

@Composable
private fun NcmAccountChip(
    loggedIn: Boolean,
    nickname: String,
    onClick: () -> Unit
) {
    Text(
        text = if (loggedIn) (nickname.ifBlank { "已登录" }).take(8) else "登录",
        fontSize = 12.sp,
        color = if (loggedIn) Color.White else Color(0xFF7DD3FC),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (loggedIn) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

private fun formatRemoteDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
