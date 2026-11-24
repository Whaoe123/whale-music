package com.whale.music.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.music.data.remote.LyricLine
import kotlinx.coroutines.launch

/**
 * 古风右起竖排诗卷歌词 — 排版方案借鉴 Sonance（gxslcl/Sonance，MIT）。
 * 右起竖排、中英混排自适应（汉字正立 / 短数字纵中横 / 英文单词旋转 90°）、
 * 横向滑动浏览、当前句高亮 + 竖向行进度 + 时间戳胶囊。
 */
@Composable
fun VerticalPoetryLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    fontSizeSp: Int,
    accent: Color,
    offsetMs: Long,
    scrollHaptic: Boolean,
    /** 没有控件时点击只唤醒控件，不跳转 */
    tapToSeek: Boolean = true,
    onSeek: (Long) -> Unit
) {
    if (lines.isEmpty()) return
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val smoothPos = rememberSmoothPosition(positionMs, isPlaying)
    val nextTimes = remember(lines) { lyricNextTimes(lines.map { it.timeMs }) }
    val current = remember(smoothPos.value, offsetMs) {
        val pos = smoothPos.value + offsetMs
        lines.indexOfLast { it.timeMs <= pos + 200 }.coerceAtLeast(0)
    }

    val listState = rememberLazyListState()
    val lastTouch = remember { longArrayOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    val latestHaptic by rememberUpdatedState(scrollHaptic)
    val prevVisible = remember { intArrayOf(-1) }

    // 横向浏览：触摸期间不打扰，5 秒后回到当前句
    LaunchedEffect(current) {
        if (android.os.SystemClock.uptimeMillis() - lastTouch[0] < SCROLL_HOLD_MS) return@LaunchedEffect
        if (dragging) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != current) {
            listState.animateScrollToItem(current)
        }
    }
    // 手动滚动跨行轻震
    LaunchedEffect(Unit) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            if (idx != prevVisible[0]) {
                if (prevVisible[0] >= 0 && dragging && latestHaptic) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                prevVisible[0] = idx
            }
        }
    }

    val charSize = fontSizeSp.toFloat()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxWeight = with(density) {
            (maxHeight.toPx() / (charSize * 1.28f * density.density))
        }.toInt().coerceIn(6, 22)
        val halfWidth = with(density) { (maxWidth / 2) }
        Box(Modifier.fillMaxSize()) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            // 旁听触摸时机，不消费手势，LazyRow 照常滚
                            do {
                                val event = awaitPointerEvent()
                                lastTouch[0] = android.os.SystemClock.uptimeMillis()
                                dragging = true
                            } while (event.changes.any { it.pressed })
                            dragging = false
                            lastTouch[0] = android.os.SystemClock.uptimeMillis()
                        }
                    },
                // 两侧留半屏：animateScrollToItem 时当前句落在画面中央
                contentPadding = PaddingValues(horizontal = halfWidth),
                reverseLayout = true
            ) {
                itemsIndexed(lines) { index, line ->
                    VerticalLineItem(
                        line = line,
                        isCurrent = index == current,
                        charSize = charSize,
                        accent = accent,
                        maxWeight = maxWeight,
                        onClick = {
                            if (tapToSeek) {
                                onSeek((line.timeMs - offsetMs).coerceAtLeast(0L))
                                lastTouch[0] = android.os.SystemClock.uptimeMillis()
                                scope.launch { listState.animateScrollToItem(index) }
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 一行竖排诗卷：右起第一列在最右，续列往左（干净诗卷，无多余标记） */
@Composable
private fun VerticalLineItem(
    line: LyricLine,
    isCurrent: Boolean,
    charSize: Float,
    accent: Color,
    maxWeight: Int,
    onClick: () -> Unit
) {
    val tokens = remember(line.text) { tokenizeVerticalLine(line.text) }
    val columns = remember(tokens, maxWeight) { splitTokensIntoColumns(tokens, maxWeight) }
    val textColor = if (isCurrent) accent else Color.White.copy(alpha = 0.52f)
    val weight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        // 右起：第一列在最右
        for (col in columns.indices.reversed()) {
            VerticalTokenColumn(
                tokens = columns[col],
                charSize = charSize,
                color = textColor,
                weight = weight
            )
            if (col > 0) Spacer(Modifier.width(14.dp))
        }
    }
}

/** 一列竖排文本 */
@Composable
private fun VerticalTokenColumn(
    tokens: List<VerticalToken>,
    charSize: Float,
    color: Color,
    weight: FontWeight
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (token in tokens) {
            when (token) {
                is VerticalToken.UprightChar -> Text(
                    token.ch.toString(),
                    fontSize = charSize.sp,
                    fontWeight = weight,
                    color = color,
                    lineHeight = (charSize * 1.16f).sp
                )
                is VerticalToken.CompactHorizontal -> Text(
                    token.text,
                    fontSize = (charSize * 0.72f).sp,
                    fontWeight = weight,
                    color = color,
                    lineHeight = charSize.sp
                )
                is VerticalToken.RotatedWord -> Text(
                    token.word,
                    fontSize = (charSize * 0.62f).sp,
                    fontWeight = weight,
                    color = color,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.rotate90Layout()
                )
                is VerticalToken.SpacerGap -> Spacer(Modifier.height((charSize * 0.5f).dp))
            }
        }
    }
}
