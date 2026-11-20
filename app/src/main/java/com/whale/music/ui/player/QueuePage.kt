package com.whale.music.ui.player

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 播放页「更多」页签 — 播放队列：点歌跳播，当前曲高亮。
 */
@Composable
fun QueuePage(
    viewModel: PlayerViewModel,
    accent: Color
) {
    val uiState by viewModel.uiState.collectAsState()
    val queue = remember(uiState.currentSong, uiState.playMode) { viewModel.queue() }
    val currentId = uiState.currentSong?.id

    if (queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("队列是空的", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            Text(
                "播放队列",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${queue.size} 首",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            itemsIndexed(queue) { index, song ->
                val playing = song.id == currentId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 7.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (playing) accent.copy(alpha = 0.14f)
                            else Color.Transparent
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { viewModel.playAt(index) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    // 序号 / 正在播放指示
                    Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                        if (playing) {
                            Icon(
                                Icons.Default.Equalizer,
                                contentDescription = "正在播放",
                                tint = accent,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Text(
                                "${index + 1}",
                                color = Color.White.copy(alpha = 0.40f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            color = if (playing) accent else Color.White.copy(alpha = 0.92f),
                            fontSize = 14.sp,
                            fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOfNotNull(
                                song.artist.takeIf { it.isNotBlank() },
                                song.album.takeIf { it.isNotBlank() }
                            ).joinToString(" · ").ifBlank { "未知歌手" },
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
