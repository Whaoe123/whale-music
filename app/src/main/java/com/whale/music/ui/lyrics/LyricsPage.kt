package com.whale.music.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.whale.music.data.remote.LyricLine
import com.whale.music.util.TraditionalSimplify

/**
 * 歌词页总装：玻璃底板（液态玻璃强度/模糊/透出）+ 样式分发（舞台 4 种 / 竖排）
 * + 繁转简显示层 + 设置面板入口。
 *
 * @param bgBackdrop 背景层（模糊封面+氛围光）的 backdrop，玻璃面板折射它
 */
@Composable
fun LyricsPage(
    lines: List<LyricLine>,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    songKey: String,
    accent: Color,
    bgProfile: BgProfile?,
    bgBackdrop: Backdrop,
    /** false 时由外层铺满整屏玻璃（竖屏歌词页），此处不再画卡片玻璃 */
    showGlass: Boolean = true,
    onSeek: (Long) -> Unit
) {
    val style by LyricPrefs.style.collectAsState()
    val fontSize by LyricPrefs.fontSize.collectAsState()
    val simple by LyricPrefs.simpleChinese.collectAsState()
    val adaptMode by LyricPrefs.adaptMode.collectAsState()
    val haptic by LyricPrefs.lyricHaptic.collectAsState()
    val offsets by LyricPrefs.offsets.collectAsState()
    val offsetMs = ((offsets[songKey] ?: 0f) * 1000L).toLong()

    // 繁转简只改显示层
    val displayLines = remember(lines, simple) {
        if (simple) lines.map { it.copy(text = TraditionalSimplify.toSimplified(it.text)) } else lines
    }

    Box(Modifier.fillMaxSize()) {
        // 玻璃底板：折射背景层（强度/模糊/透出随设置即时变）
        if (showGlass) {
            LyricsGlassPanel(
                bgBackdrop = bgBackdrop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(RoundedCornerShape(24.dp)),
                panelShape = RoundedCornerShape(24.dp)
            )
        }

        if (displayLines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无歌词",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 15.sp
                )
            }
        } else {
            when (style) {
                LyricStyle.VERTICAL -> VerticalPoetryLyrics(
                    lines = displayLines,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    fontSizeSp = fontSize,
                    accent = accent,
                    offsetMs = offsetMs,
                    scrollHaptic = haptic,
                    onSeek = onSeek
                )
                else -> StageLyrics(
                    lines = displayLines,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    style = style,
                    fontSizeSp = fontSize,
                    adaptMode = adaptMode,
                    bgProfile = bgProfile,
                    accent = accent,
                    offsetMs = offsetMs,
                    scrollHaptic = haptic,
                    onSeek = onSeek
                )
            }
        }

    }
}

/**
 * 歌词玻璃面板：折射背景层（强度/模糊/透出随设置即时变）。
 * [panelShape] 可配：竖屏歌词页用 0 圆角铺满整屏（lens 效果只接受圆角形状，
 * 直角 RectangleShape 会直接抛异常闪退，所以用 RoundedCornerShape(0.dp)）。
 */
@Composable
fun LyricsGlassPanel(
    bgBackdrop: Backdrop,
    modifier: Modifier = Modifier,
    panelShape: Shape = RoundedCornerShape(0.dp)
) {
    val glassStyle by LyricPrefs.glassStyle.collectAsState()
    val glassBlurDp by LyricPrefs.glassBlurDp.collectAsState()
    val glassVisibility by LyricPrefs.glassVisibility.collectAsState()
    if (glassStyle == GlassStyle.OFF) return
    val surfaceAlpha = glassStyle.surfaceAlpha *
        (1f - (glassVisibility - 40f).coerceIn(0f, 60f) / 60f * 0.5f)
    Box(
        modifier.drawBackdrop(
            backdrop = bgBackdrop,
            shape = { panelShape },
            effects = {
                vibrancy()
                blur(glassBlurDp.toFloat().dp.toPx())
                lens(8f.dp.toPx(), 14f.dp.toPx(), chromaticAberration = true)
            },
            highlight = { Highlight.Default.copy(alpha = 0.35f) },
            shadow = { Shadow(alpha = 0.25f) },
            onDrawSurface = {
                if (glassStyle.liquid) {
                    // 液态玻璃：顶部镜面高光
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = surfaceAlpha + 0.10f),
                                Color.White.copy(alpha = surfaceAlpha),
                                Color.White.copy(alpha = surfaceAlpha * 0.7f)
                            )
                        )
                    )
                } else {
                    drawRect(Color.White.copy(alpha = surfaceAlpha))
                }
            }
        )
    )
}
