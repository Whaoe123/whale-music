package com.whale.music.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.whale.music.R
import com.whale.music.data.model.CustomPlaylist
import com.whale.music.data.model.CustomPlaylistSong
import com.whale.music.ui.components.PressableIconButton
import com.whale.music.ui.components.pressScale

/**
 * 歌单详情 —— 自建 / 导入共用。行样式与汽水页、网易云页完全一致。
 */
@Composable
fun CustomPlaylistDetail(
    playlist: CustomPlaylist,
    songs: List<CustomPlaylistSong>?,
    playingTitle: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayAll: (Int) -> Unit,
    onRemove: (CustomPlaylistSong) -> Unit,
    onDeletePlaylist: () -> Unit,
    onRename: () -> Unit,
    /** false = 虚拟只读歌单（如「我喜欢的音乐」），隐藏重命名/删除菜单 */
    menuEnabled: Boolean = true,
    removePrompt: String = "从歌单移除",
    /** 顶栏额外动作（文案 → 回调），虚拟歌单用来放「全部放进歌单」 */
    extraAction: Pair<String, () -> Unit>? = null,
    /** 非空时每行末尾显示下载按钮（已在本地的行不显示） */
    onDownload: ((CustomPlaylistSong) -> Unit)? = null,
    /** 正在下载的那一行的 songKey，用来把图标换成转圈 */
    downloadingKey: String? = null
) {
    var pendingRemove by remember { mutableStateOf<CustomPlaylistSong?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    val list = songs.orEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                Text(
                    text = playlist.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        if (playlist.kind == CustomPlaylist.KIND_IMPORTED) {
                            append("从${playlist.source.ifBlank { "其他 App" }}导入 · ")
                        }
                        append("${list.size} 首")
                    },
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }

            if (extraAction != null && list.isNotEmpty()) {
                Text(
                    text = extraAction.first,
                    fontSize = 11.sp,
                    color = Color(0xFF7DD3FC),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { extraAction.second() }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                )
                Spacer(Modifier.width(4.dp))
            }

            if (list.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable { onPlayAll(0) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF7DD3FC),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("播放", fontSize = 12.sp, color = Color(0xFF7DD3FC))
                }
                Spacer(Modifier.width(8.dp))
            }

            if (menuEnabled) {
                Box {
                    PressableIconButton(
                        onClick = { showMenu = true },
                        buttonSize = 44.dp,
                        baseAlpha = 0.08f
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = Color(0xFF242426)
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("重命名", fontSize = 13.sp, color = Color.White) },
                            onClick = { showMenu = false; onRename() }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("删除歌单", fontSize = 13.sp, color = Color(0xFFFF6B6B)) },
                            onClick = { showMenu = false; onDeletePlaylist() }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (songs == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.5f),
                    strokeWidth = 2.dp
                )
            }
        } else if (list.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.25f),
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "歌单还是空的\n长按任意歌曲就能加进来",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 152.dp)
            ) {
                itemsIndexed(list, key = { _, s -> s.songKey }) { index, song ->
                    PlaylistTrackRow(
                        index = index,
                        item = song,
                        isPlaying = isPlaying && playingTitle == song.title,
                        onClick = { onPlayAll(index) },
                        onMore = { pendingRemove = song },
                        onDownload = onDownload?.let { cb -> { cb(song) } },
                        isDownloading = downloadingKey == song.songKey
                    )
                }
            }
        }
    }

    pendingRemove?.let { song ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(removePrompt) },
            text = { Text("把「${song.title}」从「${playlist.name}」里移除？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    onRemove(song)
                }) { Text("移除", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text("取消", color = Color.White.copy(alpha = 0.7f))
                }
            },
            containerColor = Color(0xFF1C1C1E),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.75f)
        )
    }
}

/** 歌单里的一行：与 QsTrackRow / RemoteSongCard 同一套尺寸与配色 */
@Composable
private fun PlaylistTrackRow(
    index: Int,
    item: CustomPlaylistSong,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onDownload: (() -> Unit)? = null,
    isDownloading: Boolean = false
) {
    val pressSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    val container = if (isPlaying) 0.16f else 0.05f
    val context = LocalContext.current

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
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "${index + 1}",
                fontSize = 11.sp,
                color = if (isPlaying) Color(0xFF7DD3FC) else Color.White.copy(alpha = 0.3f)
            )
        }
        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.coverUrl.ifBlank { null })
                    .size(140, 140)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = androidx.compose.ui.res.painterResource(R.drawable.default_album_art),
                error = androidx.compose.ui.res.painterResource(R.drawable.default_album_art),
                fallback = androidx.compose.ui.res.painterResource(R.drawable.default_album_art)
            )
        }

        Spacer(Modifier.width(11.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                fontSize = 14.sp,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                color = if (isPlaying) Color(0xFF7DD3FC) else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.artist,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.42f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = formatMs(item.duration),
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.28f)
        )
        // 下载：只在能下的行出现（本地已有的歌不显示）
        if (onDownload != null && item.localSongId <= 0L) {
            Spacer(Modifier.width(8.dp))
            if (isDownloading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    color = Color(0xFF7DD3FC),
                    strokeWidth = 1.6.dp
                )
            } else {
                // 图标本体 19dp，点击热区放大到 32dp，避免点不中
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onDownload() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "下载",
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Default.MoreVert,
            contentDescription = "更多",
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier
                .size(18.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onMore() }
        )
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
