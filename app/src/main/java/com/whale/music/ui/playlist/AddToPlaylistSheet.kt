package com.whale.music.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.whale.music.data.model.RemoteSong
import com.whale.music.data.model.Song

/**
 * 「加入歌单」底部弹层 —— 任意列表长按调起。
 *
 * 传 remoteSong（在线歌）或 localSong（本地歌）二选一；
 * 顶部可以直接新建一个歌单并把这首歌放进去。
 */
@Composable
fun AddToPlaylistSheet(
    onDismiss: () -> Unit,
    remoteSong: RemoteSong? = null,
    localSong: Song? = null,
    /** 给定时不加歌，而是把选中的歌单 id 回调出去（用于「全部放进歌单」） */
    onPickPlaylist: ((Long) -> Unit)? = null,
    title: String = "加入歌单",
    subtitle: String? = null,
    viewModel: PlaylistViewModel = hiltViewModel()
) {
    val playlists by viewModel.customPlaylists.collectAsState()
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(Color(0xFF151517))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                    .padding(horizontal = 20.dp)
                    .padding(top = 14.dp, bottom = 26.dp)
            ) {
                // 抓手
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 38.dp, height = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.18f))
                )
                Spacer(Modifier.height(16.dp))

                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle ?: (remoteSong?.title ?: localSong?.title).orEmpty(),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(14.dp))

                // 新建歌单 + 直接放入
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (newName.isEmpty()) {
                            Text(
                                "新建歌单并放进去",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.32f)
                            )
                        }
                        BasicTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                            cursorBrush = SolidColor(Color(0xFF7DD3FC)),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (newName.isNotBlank() && !creating) {
                        Text(
                            "创建",
                            fontSize = 12.sp,
                            color = Color(0xFF7DD3FC),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable {
                                    creating = true
                                    viewModel.createPlaylist(newName) { id ->
                                        if (onPickPlaylist != null) {
                                            onPickPlaylist(id)
                                        } else {
                                            remoteSong?.let { viewModel.addRemoteSong(id, it) }
                                            localSong?.let { viewModel.addLocalSong(id, it) }
                                        }
                                        onDismiss()
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (playlists.isEmpty()) {
                    Text(
                        "还没有歌单，上面输入名字就能建一个",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 14.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(playlists, key = { it.id }) { pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (onPickPlaylist != null) {
                                            onPickPlaylist(pl.id)
                                        } else {
                                            remoteSong?.let { viewModel.addRemoteSong(pl.id, it) }
                                            localSong?.let { viewModel.addLocalSong(pl.id, it) }
                                        }
                                        onDismiss()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.QueueMusic,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.45f),
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = pl.name,
                                    fontSize = 14.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (pl.kind == com.whale.music.data.model.CustomPlaylist.KIND_IMPORTED) {
                                    Text(
                                        "导入",
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.35f)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("收起", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
                }
            }
        }
    }
}
