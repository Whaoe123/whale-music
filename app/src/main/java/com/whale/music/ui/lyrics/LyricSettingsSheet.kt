package com.whale.music.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

private val Accent = Color(0xFF6EA8FF)

/**
 * 歌词设置面板 — 细小控件版 + 与全 App 一致的玻璃材质：
 * 细胶囊 chips + 细轨滑杆（同进度条质感）+ 小号开关。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricSettingsSheet(
    songKey: String,
    bgBackdrop: Backdrop,
    onDismiss: () -> Unit
) {
    val style by LyricPrefs.style.collectAsState()
    val glassStyle by LyricPrefs.glassStyle.collectAsState()
    val glassBlurDp by LyricPrefs.glassBlurDp.collectAsState()
    val glassVisibility by LyricPrefs.glassVisibility.collectAsState()
    val fontSize by LyricPrefs.fontSize.collectAsState()
    val simple by LyricPrefs.simpleChinese.collectAsState()
    val adaptMode by LyricPrefs.adaptMode.collectAsState()
    val hideStatusBar by LyricPrefs.hideStatusBar.collectAsState()
    val immersive by LyricPrefs.immersivePage.collectAsState()
    val haptic by LyricPrefs.lyricHaptic.collectAsState()
    val autoHide by LyricPrefs.autoHideControls.collectAsState()
    val offsets by LyricPrefs.offsets.collectAsState()
    val offsetSec = offsets[songKey] ?: 0f

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(22.dp))
                // 玻璃材质（与播放控件/导航条同款）：折射背景层
                .drawBackdrop(
                    backdrop = bgBackdrop,
                    shape = { RoundedCornerShape(22.dp) },
                    effects = {
                        vibrancy()
                        blur(18f.dp.toPx())
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.45f) },
                    shadow = { Shadow(alpha = 0.35f) },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.11f)) }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* 拦截穿透 */ }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            // 标题 + 关闭
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("歌词显示", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, null, tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── 玻璃强度 ──
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                GlassStyle.entries.forEachIndexed { i, g ->
                    SlimChip(
                        label = g.label,
                        selected = glassStyle.ordinal == i,
                        onClick = { LyricPrefs.setGlassStyle(GlassStyle.fromOrdinal(i)) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ThinSliderRow(
                label = "模糊", valueText = "${glassBlurDp}dp",
                value = glassBlurDp.toFloat(), range = 0f..30f,
                onChange = { LyricPrefs.setGlassBlurDp(it.toInt()) }
            )
            ThinSliderRow(
                label = "透出", valueText = "$glassVisibility%",
                value = glassVisibility.toFloat(), range = 40f..100f,
                onChange = { LyricPrefs.setGlassVisibility(it.toInt()) }
            )

            // ── 字号 + 简繁 ──
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("A", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                ThinSlider(
                    value = fontSize.toFloat(), range = 14f..40f,
                    modifier = Modifier.weight(1f),
                    onChange = { LyricPrefs.setFontSize(it.toInt()) }
                )
                Text("$fontSize", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
                Spacer(Modifier.width(6.dp))
                Text("简", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                SlimSwitch(checked = simple, onChange = { LyricPrefs.setSimpleChinese(it) })
            }
            Spacer(Modifier.height(8.dp))

            // ── 亮度自适应 ──
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                LyricAdaptMode.entries.forEachIndexed { i, m ->
                    SlimChip(
                        label = m.label,
                        selected = adaptMode.ordinal == i,
                        onClick = { LyricPrefs.setAdaptMode(LyricAdaptMode.fromOrdinal(i)) }
                    )
                }
            }
            val adaptDesc = adaptMode.desc
            if (adaptDesc.isNotEmpty()) {
                Text(adaptDesc, color = Color.White.copy(alpha = 0.42f), fontSize = 10.5.sp,
                    lineHeight = 15.sp, modifier = Modifier.padding(top = 5.dp))
            }
            Spacer(Modifier.height(8.dp))

            // ── 歌词样式 ──
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                LyricStyle.entries.forEachIndexed { i, st ->
                    SlimChip(
                        label = st.label,
                        selected = style.ordinal == i,
                        onClick = { LyricPrefs.setStyle(LyricStyle.entries[i]) }
                    )
                }
            }
            Text(style.desc, color = Color.White.copy(alpha = 0.42f), fontSize = 10.5.sp,
                lineHeight = 15.sp, modifier = Modifier.padding(top = 5.dp))
            Spacer(Modifier.height(8.dp))

            // ── 开关行 ──
            SlimSwitchRow(
                title = "沉浸歌词页",
                desc = "全屏玻璃 + 大字歌词贴边 + 紧凑控件；关 = 经典布局（歌名块 + 常规进度条）",
                checked = immersive,
                onChange = { LyricPrefs.setImmersivePage(it) }
            )
            SlimSwitchRow(
                title = "隐藏状态栏",
                desc = "仅对全屏歌词页生效；返回封面页自动恢复",
                checked = hideStatusBar,
                onChange = { LyricPrefs.setHideStatusBar(it) }
            )
            SlimSwitchRow(
                title = "闲置自动隐藏控件",
                desc = "歌词页 3 秒不动就把歌名、进度条、设置与切歌按钮淡出，点一下再淡回来",
                checked = autoHide,
                onChange = { LyricPrefs.setAutoHideControls(it) }
            )
            SlimSwitchRow(
                title = "滚动歌词震动",
                desc = "手动滑动歌词跨行时轻震反馈",
                checked = haptic,
                onChange = { LyricPrefs.setLyricHaptic(it) }
            )

            // ── 歌词偏移 ──
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("歌词偏移", color = Color.White, fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold)
                    Text(
                        "歌词慢了往提前调，快了往推迟调；只对这首歌",
                        color = Color.White.copy(alpha = 0.42f), fontSize = 10.5.sp,
                        lineHeight = 14.sp
                    )
                }
                StepButton(Icons.Default.Remove) {
                    LyricPrefs.setOffsetSec(songKey, (offsetSec * 10 - 1).toInt() / 10f)
                }
                Text(
                    "%+.1fs".format(offsetSec),
                    color = if (offsetSec == 0f) Color.White.copy(alpha = 0.7f) else Accent,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(52.dp),
                    textAlign = TextAlign.Center
                )
                StepButton(Icons.Default.Add) {
                    LyricPrefs.setOffsetSec(songKey, (offsetSec * 10 + 1).toInt() / 10f)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 细胶囊 chip：小号、描边未选中、淡蓝填充选中 */
@Composable
private fun SlimChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) Modifier.background(Accent.copy(alpha = 0.20f))
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(14.dp))
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Accent else Color.White.copy(alpha = 0.72f),
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/** 细轨滑杆（进度条同款质感）：按下/拖动都改值 */
@Composable
private fun ThinSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val span = range.endInclusive - range.start
    Canvas(
        modifier = modifier
            .height(22.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(range) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onChange(range.start + span * (down.position.x / widthPx).coerceIn(0f, 1f))
                    down.consume()
                    drag(down.id) { change ->
                        onChange(range.start + span * (change.position.x / widthPx).coerceIn(0f, 1f))
                        change.consume()
                    }
                }
            }
    ) {
        val trackH = 3.dp.toPx()
        val y = size.height / 2f
        val t = ((value - range.start) / span).coerceIn(0f, 1f)
        drawRoundRect(
            Color.White.copy(alpha = 0.16f),
            topLeft = Offset(0f, y - trackH / 2f),
            size = Size(size.width, trackH),
            cornerRadius = CornerRadius(trackH / 2f)
        )
        val w = size.width * t
        if (w > 0f) {
            drawRoundRect(
                Accent,
                topLeft = Offset(0f, y - trackH / 2f),
                size = Size(w, trackH),
                cornerRadius = CornerRadius(trackH / 2f)
            )
            drawCircle(Accent, 5.dp.toPx(), Offset(w, y))
        }
    }
}

/** 细滑杆行：label + 轨 + 值 */
@Composable
private fun ThinSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp,
            modifier = Modifier.width(34.dp))
        ThinSlider(value, range, Modifier.weight(1f), onChange)
        Text(valueText, color = Accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(46.dp), textAlign = TextAlign.End)
    }
    Spacer(Modifier.height(4.dp))
}

/** 小号开关 */
@Composable
private fun SlimSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier
            .height(28.dp)
            .graphicsLayer {
                scaleX = 0.78f
                scaleY = 0.78f
            },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
            uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
            uncheckedBorderColor = Color.Transparent
        )
    )
}

/** 开关行（标题 + 说明 + 小开关） */
@Composable
private fun SlimSwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = Color.White.copy(alpha = 0.42f), fontSize = 10.5.sp,
                lineHeight = 14.sp)
        }
        SlimSwitch(checked = checked, onChange = onChange)
    }
    Spacer(Modifier.height(6.dp))
}

/** 歌词偏移 − / + 小按钮 */
@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(27.dp)
            .clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(13.dp))
    }
}
