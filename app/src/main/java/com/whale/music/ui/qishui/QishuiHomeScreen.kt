package com.whale.music.ui.qishui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.whale.music.R
import com.whale.music.data.model.QsPlaylist
import com.whale.music.data.model.QsTrack
import com.whale.music.ui.components.PressableIconButton
import com.whale.music.ui.components.pressScale
import com.whale.music.ui.discover.RemoteSongCard
import kotlinx.coroutines.delay

/** 底部导航 + 迷你条占位高度 */
private val BOTTOM_RESERVE = 170.dp

/**
 * 主页 —— 汽水音乐推荐流。
 *
 * 内容（歌单 / 曲目元数据）来自汽水，音频由酷我提供，所以这里只负责“挑歌”，
 * 播放、下载、歌词、锁屏全部复用原有的播放器链路。
 */
@Composable
fun QishuiHomeScreen(
    modifier: Modifier = Modifier,
    viewModel: QishuiViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val song by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val artwork by viewModel.artwork.collectAsState()
    val context = LocalContext.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var searchMode by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        if (uiState.message != null) {
            delay(2400)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(uiState.error) {
        if (uiState.error != null) {
            delay(2400)
            viewModel.consumeMessage()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── 封面模糊背景：参数与播放页一致，保证四页材质统一 ──
        Crossfade(
            targetState = artwork,
            animationSpec = tween(1200),
            label = "qs_bg"
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

            // ── 顶栏 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Whale-汽水",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (uiState.loggedIn) {
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
                        searchMode = true
                        viewModel.clearSearch()
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

                AccountChip(
                    loggedIn = uiState.loggedIn,
                    nickname = uiState.nickname,
                    onLogin = viewModel::openLogin,
                    onLogout = viewModel::logout
                )

                Spacer(Modifier.width(6.dp))

                PressableIconButton(
                    onClick = viewModel::refresh,
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

            // ── 搜索框（点顶栏搜索按钮展开） ──
            if (searchMode) {
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
                        if (uiState.query.isEmpty()) {
                            Text(
                                "搜索歌曲、歌手",
                                fontSize = 13.5.sp,
                                color = Color.White.copy(alpha = 0.35f)
                            )
                        }
                        BasicTextField(
                            value = uiState.query,
                            onValueChange = viewModel::onQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 13.5.sp
                            ),
                            cursorBrush = SolidColor(Color(0xFF7DD3FC)),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (uiState.query.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "清空",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { viewModel.clearSearch() }
                        )
                    } else {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "取消",
                            fontSize = 12.sp,
                            color = Color(0xFF7DD3FC),
                            modifier = Modifier.clickable {
                                searchMode = false
                                viewModel.clearSearch()
                            }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            when {
                // ── 搜索结果 ──
                searchMode && uiState.query.isNotBlank() -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = BOTTOM_RESERVE),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                isPlaying = song?.title == s.title && isPlaying,
                                isFavorite = viewModel.isRemoteFavorite(s),
                                downloading = false,
                                busy = false,
                                showDownload = true,
                                onClick = { viewModel.playSearchResult(i) },
                                onFavoriteToggle = { viewModel.toggleRemoteFavorite(s) },
                                onDownload = { viewModel.downloadSearchResult(s) }
                            )
                        }
                    }
                }

                uiState.loading && uiState.playlists.isEmpty() -> Box(
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

                uiState.playlists.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = uiState.error ?: "没有拿到推荐内容",
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
                                PlaylistTile(
                                    playlist = pl,
                                    selected = pl.id == uiState.selected?.id,
                                    onClick = { viewModel.selectPlaylist(pl) }
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.height(18.dp)) }

                    item {
                        val selected = uiState.selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selected?.title ?: "歌单",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${uiState.tracks.size} 首",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.38f)
                                )
                            }
                            AutoplayToggle(
                                enabled = uiState.autoplay,
                                onToggle = viewModel::setAutoplay
                            )

                            Spacer(Modifier.width(8.dp))

                            Text(
                                text = "播放全部",
                                fontSize = 12.sp,
                                color = Color(0xFF7DD3FC),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { viewModel.playAll() }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    item { Spacer(Modifier.height(6.dp)) }

                    if (uiState.tracksLoading && uiState.tracks.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Color.White.copy(alpha = 0.45f),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    } else {
                        // key 带上序号：即便上游重复下发同一首也不会撞 key 崩溃
                        itemsIndexed(uiState.tracks, key = { i, t -> "${t.id}#$i" }) { index, track ->
                            QsTrackRow(
                                index = index,
                                track = track,
                                playing = song?.title == track.title && isPlaying,
                                resolving = uiState.resolvingIndex == index,
                                isFavorite = viewModel.isTrackFavorite(track),
                                onFavoriteToggle = { viewModel.toggleTrackFavorite(track) },
                                onClick = { viewModel.play(index) }
                            )
                        }
                    }

                    if (uiState.hasMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (uiState.tracksLoading) "加载中…" else "加载更多",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.45f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable(enabled = !uiState.tracksLoading) { viewModel.loadMore() }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 提示 ──
        val tip = uiState.message ?: uiState.error
        if (tip != null) {
            Text(
                text = tip,
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
    }

    if (uiState.showLogin) {
        QishuiQrLoginSheet(
            onClose = viewModel::closeLogin,
            onLoggedIn = viewModel::onLoggedIn
        )
    }
}

// ───────── 账号入口 ─────────

@Composable
private fun AccountChip(
    loggedIn: Boolean,
    nickname: String,
    onLogin: () -> Unit,
    onLogout: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }

    Box {
        Text(
            text = if (loggedIn) nickname.ifBlank { "已登录" } else "登录",
            fontSize = 12.sp,
            color = if (loggedIn) Color.White else Color(0xFF7DD3FC),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    if (loggedIn) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f)
                )
                .clickable { if (loggedIn) menu = true else onLogin() }
                .padding(horizontal = 12.dp, vertical = 7.dp)
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("退出登录", fontSize = 13.sp) },
                onClick = {
                    menu = false
                    onLogout()
                }
            )
        }
    }
}

// ───────── 自动播放开关 ─────────

/**
 * 启动自动播放的开关（默认开）。放在歌单标题行右侧，
 * 只影响「打开 App 自动接着播」，不改变手动点播行为。
 */
@Composable
private fun AutoplayToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .pressScale(press, pressedScale = 0.94f)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = if (enabled) 0.14f else 0.06f))
            .clickable(interactionSource = press, indication = null) { onToggle(!enabled) }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (enabled) Color(0xFF7DD3FC) else Color.White.copy(alpha = 0.28f)
                )
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "自动播放",
            fontSize = 10.5.sp,
            color = if (enabled) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.42f)
        )
    }
}

// ───────── 歌单卡片 ─────────

@Composable
private fun PlaylistTile(
    playlist: QsPlaylist,
    selected: Boolean,
    onClick: () -> Unit
) {
    val pressSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(108.dp)
            .pressScale(pressSource, pressedScale = 0.94f)
            .clickable(interactionSource = pressSource, indication = null, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                    } else {
                        Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                    }
                )
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(playlist.cover.ifBlank { null })
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
            text = playlist.title,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ───────── 曲目行 ─────────

@Composable
private fun QsTrackRow(
    index: Int,
    track: QsTrack,
    playing: Boolean,
    resolving: Boolean,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit,
    onClick: () -> Unit
) {
    val pressSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    val container = if (playing) 0.16f else 0.05f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .pressScale(pressSource, pressedScale = 0.98f)
            .clip(shape)
            .background(Color.White.copy(alpha = container))
            .clickable(interactionSource = pressSource, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp),
            contentAlignment = Alignment.Center
        ) {
            if (resolving) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.8f),
                    strokeWidth = 1.6.dp,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    text = "${index + 1}",
                    fontSize = 11.sp,
                    color = if (playing) Color(0xFF7DD3FC) else Color.White.copy(alpha = 0.3f)
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(track.cover.ifBlank { null })
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

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                fontSize = 14.sp,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                color = if (playing) Color(0xFF7DD3FC) else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track.artist,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.42f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 爱心：收藏后混进音乐库列表
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
            text = formatDuration(track.duration),
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.28f)
        )
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
