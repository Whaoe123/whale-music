package com.whale.music.ui.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.whale.music.R
import com.whale.music.data.model.CustomPlaylist
import com.whale.music.data.model.RemoteSong
import com.whale.music.data.model.Song
import com.whale.music.ui.components.GlassSongCard
import com.whale.music.ui.components.PressableIconButton
import com.whale.music.util.CoverResolver
import com.whale.music.data.sync.SongKey

/**
 * 曲库页 —— 1.17 收敛为「一个页面」：
 *
 * 一个纵向列表 = 我喜欢的音乐（三源合并）+ 我的歌单 + 本地音乐入口。
 * 点任意卡片进统一风格的详情（CustomPlaylistDetail），不再有 3 个 Tab 和两套详情壳。
 * 顶栏「+」只保留 3 项：新建歌单 / 导入歌单链接 / 同步网易云收藏。
 */
@Composable
fun PlaylistScreen(
    hasAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val allSongs = uiState.songs
    val currentSong = uiState.currentSong
    val isScanning = uiState.isScanning
    val scanError = uiState.errorMessage
    val context = LocalContext.current

    val customPlaylists by viewModel.customPlaylists.collectAsState()
    val favoriteRows by viewModel.favoriteRows.collectAsState()
    val openedId by viewModel.openedId.collectAsState()
    val openedSongs by viewModel.openedSongs.collectAsState()
    val opened = customPlaylists.firstOrNull { it.id == openedId }

    // 详情页：我喜欢的音乐 / 本地音乐
    var showFavorites by remember { mutableStateOf(false) }
    var showLocal by remember { mutableStateOf(false) }

    // 系统返回：先退出歌单详情 / 我喜欢 / 本地音乐，退出完了才轮到「回播放页」
    BackHandler(enabled = openedId != null || showFavorites || showLocal) {
        when {
            openedId != null -> viewModel.closePlaylist()
            showFavorites -> showFavorites = false
            showLocal -> showLocal = false
        }
    }

    // 长按待删除的本地歌曲
    var pendingDelete by remember { mutableStateOf<Song?>(null) }
    // 长按待加入歌单的在线歌曲
    var pendingAdd by remember { mutableStateOf<RemoteSong?>(null) }
    // 本地歌长按加歌单
    var pendingAddLocal by remember { mutableStateOf<Song?>(null) }
    // 本地歌长按菜单（加入歌单 / 删除）
    var menuSong by remember { mutableStateOf<Song?>(null) }
    // 弹窗
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var createText by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<CustomPlaylist?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<CustomPlaylist?>(null) }
    var pickForFavorites by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val importing by viewModel.importing.collectAsState()
    val importMessage by viewModel.importMessage.collectAsState()
    val toast by viewModel.toast.collectAsState()

    LaunchedEffect(importMessage) {
        importMessage?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeImportMessage()
            if (msg.startsWith("已导入")) {
                showImport = false
                importText = ""
            }
        }
    }
    LaunchedEffect(toast) {
        toast?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    val localBackdrop = rememberLayerBackdrop()

    LaunchedEffect(hasAudioPermission) {
        viewModel.ensureInitialScan(hasAudioPermission)
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── 封面模糊背景 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(localBackdrop)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uiState.artwork ?: currentSong?.let { CoverResolver.resolveSong(it.id, it.albumId) })
                    .size(256, 256)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(48.dp),
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(2.0f) }),
                alpha = 0.9f,
                placeholder = painterResource(R.drawable.default_album_art),
                error = painterResource(R.drawable.default_album_art),
                fallback = painterResource(R.drawable.default_album_art)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = statusBarTop + 12.dp)
        ) {
            when {
                // ── 自建 / 导入歌单详情 ──
                opened != null -> {
                    CustomPlaylistDetail(
                        playlist = opened,
                        songs = openedSongs,
                        playingTitle = currentSong?.title,
                        isPlaying = true,
                        onBack = viewModel::closePlaylist,
                        onPlayAll = { index -> viewModel.playPlaylist(openedSongs.orEmpty(), index) },
                        onRemove = { viewModel.removeSongFromPlaylist(opened.id, it) },
                        onDeletePlaylist = { deleteTarget = opened },
                        onRename = {
                            renameTarget = opened
                            renameText = opened.name
                        }
                    )
                    return@Column
                }

                // ── 我喜欢的音乐（三源合并虚拟歌单）──
                showFavorites -> {
                    CustomPlaylistDetail(
                        playlist = CustomPlaylist(id = -1L, name = "我喜欢的音乐"),
                        songs = favoriteRows,
                        playingTitle = currentSong?.title,
                        isPlaying = true,
                        onBack = { showFavorites = false },
                        onPlayAll = viewModel::playFavorites,
                        onRemove = viewModel::removeFavorite,
                        onDeletePlaylist = {},
                        onRename = {},
                        menuEnabled = false,
                        removePrompt = "取消喜欢",
                        extraAction = "全部放进歌单" to { pickForFavorites = true }
                    )
                    return@Column
                }

                // ── 本地音乐（降级入口）──
                showLocal -> {
                    LocalSongsPage(
                        songs = allSongs,
                        currentSong = currentSong,
                        isScanning = isScanning,
                        onBack = { showLocal = false },
                        onRescan = { viewModel.refresh(hasAudioPermission, onRequestPermission) },
                        onPlay = { song, list -> viewModel.play(song, list) },
                        onFavorite = viewModel::toggleFavorite,
                        onDelete = { pendingDelete = it },
                        onLongPress = { menuSong = it }
                    )
                    return@Column
                }
            }

            // ── 顶栏 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "曲库",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    PressableIconButton(
                        onClick = { menuOpen = true },
                        buttonSize = 46.dp,
                        backdrop = localBackdrop
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "新建 / 导入",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = Color(0xFF242426)
                    ) {
                        DropdownMenuItem(
                            text = { Text("新建歌单", fontSize = 13.sp, color = Color.White) },
                            onClick = { menuOpen = false; createText = ""; showCreate = true }
                        )
                        DropdownMenuItem(
                            text = { Text("导入歌单链接", fontSize = 13.sp, color = Color.White) },
                            onClick = { menuOpen = false; showImport = true }
                        )
                        DropdownMenuItem(
                            text = { Text("同步网易云收藏", fontSize = 13.sp, color = Color.White) },
                            onClick = { menuOpen = false; viewModel.syncNcmLiked() }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isScanning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.5f),
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.status_scanning),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.45f)
                    )
                }
            }

            // ── 唯一的主列表 ──
            LibraryHome(
                playlists = customPlaylists,
                favoriteCount = favoriteRows.size,
                favoriteCover = favoriteRows.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
                localCount = allSongs.size,
                onOpenFavorites = { showFavorites = true },
                onOpenLocal = { showLocal = true },
                onOpenPlaylist = viewModel::openPlaylist,
                onCreate = { createText = ""; showCreate = true },
                onImport = { showImport = true },
                onDelete = { deleteTarget = it },
                onRename = {
                    renameTarget = it
                    renameText = it.name
                }
            )
        }

        // ── 本地歌长按菜单 ──
        menuSong?.let { song ->
            AlertDialog(
                onDismissRequest = { menuSong = null },
                title = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column {
                        Text(
                            "加入歌单",
                            fontSize = 14.sp,
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    menuSong = null
                                    pendingAddLocal = song
                                }
                                .padding(vertical = 12.dp, horizontal = 6.dp)
                        )
                        Text(
                            "删除歌曲",
                            fontSize = 14.sp,
                            color = Color(0xFFFF6B6B),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    menuSong = null
                                    pendingDelete = song
                                }
                                .padding(vertical = 12.dp, horizontal = 6.dp)
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { menuSong = null }) {
                        Text("取消", color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF1C1C1E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.75f)
            )
        }

        // ── 删除本地歌确认 ──
        pendingDelete?.let { song ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.delete_song_title)) },
                text = { Text(stringResource(R.string.delete_song_message, song.title)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = null
                        viewModel.deleteSong(song) { msg ->
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.action_delete), color = Color(0xFFFF6B6B)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.action_cancel), color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF1C1C1E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.75f)
            )
        }

        // ── 在线歌长按 → 加入歌单 ──
        pendingAdd?.let { song ->
            AddToPlaylistSheet(
                remoteSong = song,
                onDismiss = { pendingAdd = null }
            )
        }

        // ── 本地歌长按 → 加入歌单 ──
        pendingAddLocal?.let { song ->
            AddToPlaylistSheet(
                localSong = song,
                onDismiss = { pendingAddLocal = null }
            )
        }

        // ── 新建歌单 ──
        if (showCreate) {
            InputDialog(
                title = "新建歌单",
                hint = "给歌单起个名字",
                value = createText,
                onChange = { createText = it },
                confirmText = "创建",
                onConfirm = {
                    viewModel.createPlaylist(createText)
                    showCreate = false
                },
                onDismiss = { showCreate = false }
            )
        }

        // ── 重命名 ──
        renameTarget?.let { target ->
            InputDialog(
                title = "重命名",
                hint = "新名字",
                value = renameText,
                onChange = { renameText = it },
                confirmText = "保存",
                onConfirm = {
                    viewModel.renamePlaylist(target, renameText)
                    renameTarget = null
                },
                onDismiss = { renameTarget = null }
            )
        }

        // ── 删除歌单 ──
        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除歌单") },
                text = { Text("「${target.name}」和里面的歌单记录都会删掉，本地音乐文件不受影响。") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deletePlaylist(target)
                        deleteTarget = null
                    }) { Text("删除", color = Color(0xFFFF6B6B)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text("取消", color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF1C1C1E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.75f)
            )
        }

        // ── 把「我喜欢的」全部放进某个歌单 ──
        if (pickForFavorites) {
            AddToPlaylistSheet(
                title = "全部放进歌单",
                subtitle = "共 ${favoriteRows.size} 首喜欢的歌",
                onPickPlaylist = { id -> viewModel.addAllFavorites(id) },
                onDismiss = { pickForFavorites = false }
            )
        }

        // ── 导入歌单 ──
        if (showImport) {
            AlertDialog(
                onDismissRequest = { if (!importing) showImport = false },
                title = { Text("导入歌单") },
                text = {
                    Column {
                        Text(
                            "把其他 App 的歌单链接粘进来（网易云 / QQ 音乐），会整张导入曲库",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            if (importText.isEmpty()) {
                                Text(
                                    "https://music.163.com/playlist?id=...",
                                    fontSize = 13.sp,
                                    color = Color.White.copy(alpha = 0.3f)
                                )
                            }
                            BasicTextField(
                                value = importText,
                                onValueChange = { importText = it },
                                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                                cursorBrush = SolidColor(Color.White),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "分享文案直接整段粘贴也可以，会自动找链接",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.35f)
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.importFromLink(importText) { pid ->
                                showImport = false
                                importText = ""
                                pid?.let { viewModel.openPlaylistById(it) }
                            }
                        },
                        enabled = !importing
                    ) {
                        Text(
                            if (importing) "导入中…" else "导入",
                            color = if (importing) Color.White.copy(alpha = 0.4f) else Color(0xFF64D2FF)
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { if (!importing) showImport = false }, enabled = !importing) {
                        Text("取消", color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF1C1C1E),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.75f)
            )
        }
    }
}

// ───────── 唯一主列表 ─────────

@Composable
private fun LibraryHome(
    playlists: List<CustomPlaylist>,
    favoriteCount: Int,
    favoriteCover: String,
    localCount: Int,
    onOpenFavorites: () -> Unit,
    onOpenLocal: () -> Unit,
    onOpenPlaylist: (CustomPlaylist) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onDelete: (CustomPlaylist) -> Unit,
    onRename: (CustomPlaylist) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 170.dp)
    ) {
        item {
            FavoriteEntry(count = favoriteCount, cover = favoriteCover, onClick = onOpenFavorites)
        }

        item {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "我的歌单 ${playlists.size}",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }

        if (playlists.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "还没有自建歌单",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Text(
                            "新建一个",
                            fontSize = 12.sp,
                            color = Color(0xFF7DD3FC),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { onCreate() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Text(
                            "或导入歌单",
                            fontSize = 12.sp,
                            color = Color(0xFF7DD3FC),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { onImport() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        items(playlists, key = { it.id }) { pl ->
            PlaylistEntry(
                playlist = pl,
                onClick = { onOpenPlaylist(pl) },
                onDelete = { onDelete(pl) },
                onRename = { onRename(pl) }
            )
        }

        // 本地音乐降级为一个小入口，不再占一整个 Tab
        item {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .clickable { onOpenLocal() }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("本地音乐", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                    Text(
                        "$localCount 首 · 手机里扫描到的歌",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.35f)
                    )
                }
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun FavoriteEntry(count: Int, cover: String, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(cover.ifBlank { null }).size(160, 160).build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = painterResource(R.drawable.default_album_art),
                fallback = painterResource(R.drawable.default_album_art)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x33F472B6))
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("我喜欢的音乐", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(2.dp))
            Text(
                "$count 首 · 含网易云收藏，自动同步",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.42f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun PlaylistEntry(
    playlist: CustomPlaylist,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(playlist.coverUrl.ifBlank { null })
                    .size(140, 140)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = painterResource(R.drawable.default_album_art),
                fallback = painterResource(R.drawable.default_album_art)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                playlist.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (playlist.kind == CustomPlaylist.KIND_IMPORTED) {
                    "导入自${playlist.source.ifBlank { "其他 App" }}"
                } else {
                    "自建歌单"
                },
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f)
            )
        }
        Box {
            androidx.compose.material3.IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "更多",
                    tint = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = Color(0xFF242426)
            ) {
                DropdownMenuItem(
                    text = { Text("重命名", fontSize = 13.sp, color = Color.White) },
                    onClick = { menu = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("删除", fontSize = 13.sp, color = Color(0xFFFF6B6B)) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
    }
}

// ───────── 本地音乐子页（从主列表点进去） ─────────

@Composable
private fun LocalSongsPage(
    songs: List<Song>,
    currentSong: Song?,
    isScanning: Boolean,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
    onFavorite: (Long) -> Unit,
    onDelete: (Song) -> Unit,
    onLongPress: (Song) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PressableIconButton(onClick = onBack, buttonSize = 44.dp, baseAlpha = 0.08f) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("本地音乐", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    if (isScanning) "正在扫描…" else "${songs.size} 首",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
            PressableIconButton(onClick = onRescan, buttonSize = 44.dp, baseAlpha = 0.08f) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "重新扫描",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (songs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.empty_music),
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 170.dp)
            ) {
                itemsIndexed(songs, key = { _, song -> "local_${song.id}" }) { index, song ->
                    GlassSongCard(
                        song = song,
                        isPlaying = currentSong?.id == song.id,
                        onClick = { onPlay(song, songs) },
                        onFavoriteToggle = { onFavorite(song.id) },
                        onDelete = { onDelete(song) },
                        onLongPress = { onLongPress(song) },
                        index = index
                    )
                }
            }
        }
    }
}

// ───────── 输入弹窗 ─────────

@Composable
private fun InputDialog(
    title: String,
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (value.isEmpty()) {
                    Text(hint, fontSize = 13.sp, color = Color.White.copy(alpha = 0.3f))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    cursorBrush = SolidColor(Color(0xFF7DD3FC)),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText, color = Color(0xFF64D2FF)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Color.White.copy(alpha = 0.7f)) }
        },
        containerColor = Color(0xFF1C1C1E),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.75f)
    )
}
