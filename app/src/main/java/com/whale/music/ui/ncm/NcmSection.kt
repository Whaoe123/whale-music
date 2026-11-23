package com.whale.music.ui.ncm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.whale.music.R
import com.whale.music.data.model.Song
import com.whale.music.ncm.NcmLoginState
import com.whale.music.ui.components.GlassSongCard

/**
 * 网易云区块 — 推荐音乐 / 红心列表 / 搜索 / 红心回写。未登录可听推荐，登录后解锁红心与个人推荐。
 * 玻璃配方与 OHMusic 卡片一致（白 0.08 底 + 白 0.25 描边）。
 */
@Composable
fun NcmSection(
    currentSong: Song?,
    modifier: Modifier = Modifier,
    viewModel: NcmViewModel = hiltViewModel()
) {
    val loginState by viewModel.loginState.collectAsState()
    val liked by viewModel.liked.collectAsState()
    val likedLoading by viewModel.likedLoading.collectAsState()
    val recommended by viewModel.recommended.collectAsState()
    val recommendedLoading by viewModel.recommendedLoading.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val error by viewModel.error.collectAsState()
    val resolving by viewModel.resolving.collectAsState()

    // 已登录时的列表模式：0=推荐 1=我喜欢
    var listMode by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }

    var showLoginDialog = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showWebLogin = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showCookieLogin = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    // 首次进入自动拉一次推荐
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (recommended.isEmpty()) viewModel.loadRecommended()
    }

    // 消费错误提示
    androidx.compose.runtime.LaunchedEffect(error) {
        if (error != null) {
            // 显示 2.5 秒后清除（简单文本提示，不引入 Snackbar 依赖）
            kotlinx.coroutines.delay(2500)
            viewModel.consumeError()
        }
    }

    // 当前应显示的列表：搜索结果 > 推荐/红心
    val displayList: List<Song> = results ?: when {
        loginState != NcmLoginState.LOGGED_IN -> recommended
        listMode == 0 -> recommended
        else -> liked
    }
    val listLoading = if (results != null) false
    else if (loginState != NcmLoginState.LOGGED_IN || listMode == 0) recommendedLoading
    else likedLoading

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶部栏：登录入口 / 分段切换 ──
            when (loginState) {
                NcmLoginState.LOGGED_OUT -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "为你推荐",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(onClick = { showWebLogin.value = true }) {
                        Text("登录", color = Color(0xFF60A5FA), fontSize = 14.sp)
                    }
                    androidx.compose.material3.TextButton(onClick = { showCookieLogin.value = true }) {
                        Text("Cookie", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                    }
                }

                NcmLoginState.LOGGED_IN -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 分段切换：推荐 / 我喜欢
                    SegmentedSwitch(
                        selectedIndex = listMode,
                        onSelect = { listMode = it },
                        items = listOf("推荐", "我喜欢")
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (listMode == 0) "网易推荐" else "红心",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(onClick = { viewModel.logout() }) {
                        Text("退出", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 搜索框 ──
            SearchBar(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                onClear = viewModel::clearSearch
            )

            Spacer(Modifier.height(10.dp))

            // ── 数量 / 状态 ──
            val hint = when {
                results != null -> "搜索结果 ${results?.size ?: 0} 首"
                listLoading && displayList.isEmpty() ->
                    if (listMode == 0 || loginState != NcmLoginState.LOGGED_IN) "正在加载推荐音乐…"
                    else "正在加载红心歌曲…"
                displayList === liked -> "我喜欢的音乐 ${liked.size} 首"
                else -> "每日推荐 ${recommended.size} 首 · 点击即播"
            }
            Text(
                text = hint,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 4.dp)
            )

            Spacer(Modifier.height(10.dp))

            when {
                listLoading && displayList.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.6f),
                        strokeWidth = 2.dp
                    )
                }

                displayList.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (results != null) "没有找到相关歌曲"
                        else if (displayList === liked) "还没有红心歌曲，点一下爱心就会同步到网易云"
                        else "推荐加载失败，稍后再试",
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    itemsIndexed(
                        displayList,
                        key = { _, song -> song.id }
                    ) { index, song ->
                        GlassSongCard(
                            song = song,
                            isPlaying = currentSong?.id == song.id,
                            onClick = {
                                if (!resolving) viewModel.play(song, displayList)
                            },
                            onFavoriteToggle = {
                                if (loginState == NcmLoginState.LOGGED_IN) {
                                    viewModel.toggleFavorite(song)
                                } else {
                                    showWebLogin.value = true
                                }
                            },
                            index = index
                        )
                    }
                }
            }
        }

        // ── 错误提示浮层 ──
        error?.let { msg ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .background(
                        Color(0xCC122244),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = msg,
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
        }

        // ── 解析链接中 ──
        if (resolving) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99122244), CircleShape)
                    .padding(16.dp)
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }

    if (showLoginDialog.value) {
        QrLoginDialog(
            viewModel = viewModel,
            onDismiss = { showLoginDialog.value = false },
            onSwitchToWeb = {
                showLoginDialog.value = false
                showWebLogin.value = true
            }
        )
    }

    if (showWebLogin.value) {
        WebLoginDialog(
            onCookie = { raw ->
                viewModel.loginWithCookie(raw)
                showWebLogin.value = false
            },
            onSwitchToPaste = {
                showWebLogin.value = false
                showCookieLogin.value = true
            },
            onDismiss = { showWebLogin.value = false }
        )
    }

    if (showCookieLogin.value) {
        CookieLoginDialog(
            onLogin = { raw -> viewModel.loginWithCookie(raw) },
            onDismiss = { showCookieLogin.value = false }
        )
    }
}

/** 分段切换器（推荐 / 我喜欢） */
@Composable
private fun SegmentedSwitch(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    items: List<String>
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        if (selected) Color.White.copy(alpha = 0.22f)
                        else Color.Transparent
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

/** 搜索框（玻璃卡片配方） */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color.White,
                fontSize = 14.sp
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF60A5FA)),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ncm_search_hint),
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 14.sp
                        )
                    }
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onClear)
            )
        }
    }
}
