package com.whale.music.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.whale.music.player.audio.AudioVisualizer
import com.whale.music.player.audio.FxPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 实时频谱柱：数据来自播放链路上的 [AudioVisualizer]（PCM 取样 → FFT → AGC）。
 *
 * - [active] 为 false（暂停）时目标能量归零，柱子自然回落；
 * - 露出与否由设置里的「频谱可视化」开关控制，关闭时整组归零淡出；
 * - 绘制前在后台线程做 attack/release 平滑（FFT 只有 20~40Hz，
 *   60fps 插值才不会一格一格跳）。
 *
 * @param barCount 柱数（对数频段映射，低频区天然更稀）
 */
@Composable
fun SpectrumBars(
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 48
) {
    val enabled by FxPrefs.spectrumEnabled.collectAsState()
    val level by FxPrefs.spectrumLevel.collectAsState()
    // active/level 随播放状态频繁变化，但不值得重启轮询循环：经 updatedState 读最新值
    val activeState by rememberUpdatedState(active)
    val levelState by rememberUpdatedState(level)

    val viz = remember(barCount) { AudioVisualizer(barCount) }
    // 平滑后的显示值（绘制线程只读这份快照，与音频线程的 viz.levels 解耦）
    var levels by remember { mutableStateOf(FloatArray(barCount)) }
    val gradient = remember(accent) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.92f),
                accent.copy(alpha = 0.85f),
                accent.copy(alpha = 0.30f)
            )
        )
    }

    LaunchedEffect(enabled, barCount) {
        if (!enabled) {
            levels = FloatArray(barCount)
            return@LaunchedEffect
        }
        // 目标值的 attack/release 平滑工作区，只在这里被写
        val display = FloatArray(barCount)
        while (isActive) {
            withContext(Dispatchers.Default) {
                if (!viz.poll(levelState, activeState)) viz.decayToZero()
                val target = viz.levels
                var i = 0
                while (i < display.size && i < target.size) {
                    val d = target[i] - display[i]
                    // 快起慢落：上升跟得上鼓点，下落有拖尾
                    display[i] += d * (if (d > 0f) 0.38f else 0.16f)
                    i++
                }
            }
            levels = display.copyOf()
            delay(16) // ~60fps
        }
    }

    Canvas(modifier) {
        val n = levels.size
        if (n == 0 || size.height <= 0f) return@Canvas
        val slot = size.width / n
        val barW = slot * 0.58f
        val radius = CornerRadius(barW / 2f, barW / 2f)
        var i = 0
        while (i < n) {
            val h = levels[i].coerceIn(0f, 1f) * size.height
            if (h > 1f) {
                val x = i * slot + (slot - barW) / 2f
                drawRoundRect(
                    brush = gradient,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = radius
                )
            }
            i++
        }
    }
}
