package com.whale.music.ui.player

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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.whale.music.player.audio.EQ_BAND_COUNT
import com.whale.music.player.audio.EQ_CENTER_FREQS
import com.whale.music.player.audio.EQ_MAX_DB
import com.whale.music.player.audio.EQ_MIN_DB
import com.whale.music.player.audio.EqPreset
import com.whale.music.player.audio.FxPrefs
import com.whale.music.player.audio.eqBandLabel

private val Accent = Color(0xFF6EA8FF)

/**
 * 音效面板 — 玻璃材质与歌词设置面板同款：
 * 总开关 + 预设 chips + 10 段竖滑杆 + 频谱可视化开关。
 *
 * 所有改动经 [FxPrefs] 落盘并立刻推到 [com.whale.music.player.audio.EqBus]，
 * 音频线程在下一块缓冲生效。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerSheet(
    bgBackdrop: Backdrop,
    onDismiss: () -> Unit
) {
    val eqEnabled by FxPrefs.eqEnabled.collectAsState()
    val gains by FxPrefs.eqGains.collectAsState()
    val preset by FxPrefs.eqPreset.collectAsState()
    val spectrumOn by FxPrefs.spectrumEnabled.collectAsState()
    val spectrumLevel by FxPrefs.spectrumLevel.collectAsState()

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
                Text("音效均衡", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
                    Icon(
                        Icons.Default.Close, null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── 总开关 ──
            SlimSwitchRow(
                title = "均衡器",
                desc = if (eqEnabled) "软件 DSP 全机型生效，改动即时同步到播放链路"
                else "关闭时整条滤波链旁路，零开销",
                checked = eqEnabled,
                onChange = { FxPrefs.setEqEnabled(it) }
            )

            // ── 预设 ──
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                EqPreset.entries.forEach { p ->
                    SlimChip(
                        label = p.label,
                        selected = preset == p && (p == EqPreset.OFF || eqEnabled),
                        onClick = { FxPrefs.applyPreset(p) }
                    )
                }
            }
            Text(
                "选预设自动打开均衡器；拖动任意滑杆会切到该段手动模式",
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
            Spacer(Modifier.height(10.dp))

            // ── 10 段竖滑杆 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (i in 0 until EQ_BAND_COUNT) {
                    EqBandSlider(
                        label = eqBandLabel(EQ_CENTER_FREQS[i]),
                        value = gains.getOrElse(i) { 0f },
                        enabled = eqEnabled,
                        modifier = Modifier.weight(1f),
                        onChange = { FxPrefs.setEqGain(i, it) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "±12 dB · 自动前级余量防削波 · 31Hz–16kHz 十段",
                color = Color.White.copy(alpha = 0.38f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            // ── 频谱可视化 ──
            Text(
                "频谱可视化",
                color = Color.White,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "播放页封面下方实时频谱柱，取样自播放链路（开了音效柱子跟着动）",
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 10.5.sp,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(6.dp))
            SlimSwitchRow(
                title = "显示频谱",
                desc = "暂停时柱子自然回落",
                checked = spectrumOn,
                onChange = { FxPrefs.setSpectrumEnabled(it) }
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("律动幅度", color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp,
                    modifier = Modifier.width(52.dp))
                ThinSlider(
                    value = spectrumLevel, range = 0.5f..1f,
                    modifier = Modifier.weight(1f),
                    onChange = { FxPrefs.setSpectrumLevel(it) }
                )
                Text(
                    "${(spectrumLevel * 100).toInt()}%",
                    color = Accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(42.dp), textAlign = TextAlign.End
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 单段竖滑杆：上滑加、下滑减，中线为 0dB。
 * 按下/拖动都改值；柱体在滑杆内画，选中段亮起。
 */
@Composable
private fun EqBandSlider(
    label: String,
    value: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit
) {
    var trackHeightPx by remember { mutableIntStateOf(1) }
    val span = EQ_MAX_DB - EQ_MIN_DB

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // 当前 dB 读数
        Text(
            (if (value > 0f) "+" else "") + if (value == 0f) "0" else "%.1f".format(value),
            color = if (!enabled) Color.White.copy(alpha = 0.30f)
            else if (value == 0f) Color.White.copy(alpha = 0.50f) else Accent,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(3.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .onSizeChanged { trackHeightPx = it.height.coerceAtLeast(1) }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun apply(y: Float) {
                            // 视口顶 = +12dB，底 = -12dB（手指上滑增大）
                            val t = (1f - (y / trackHeightPx).coerceIn(0f, 1f))
                            onChange(EQ_MIN_DB + span * t)
                        }
                        apply(down.position.y)
                        down.consume()
                        drag(down.id) { change ->
                            apply(change.position.y)
                            change.consume()
                        }
                    }
                }
        ) {
            val w = size.width
            val cx = w / 2f
            val trackW = 3.5.dp.toPx()
            val radius = CornerRadius(trackW / 2f)

            // 轨道
            drawRoundRect(
                Color.White.copy(alpha = if (enabled) 0.16f else 0.08f),
                topLeft = Offset(cx - trackW / 2f, 0f),
                size = Size(trackW, size.height),
                cornerRadius = radius
            )
            // 中线（0dB）
            drawLine(
                Color.White.copy(alpha = if (enabled) 0.30f else 0.12f),
                Offset(cx - w * 0.32f, size.height / 2f),
                Offset(cx + w * 0.32f, size.height / 2f),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )
            if (enabled) {
                val t = ((value - EQ_MIN_DB) / span).coerceIn(0f, 1f)
                val y = size.height * (1f - t)
                val midY = size.height / 2f
                // 填充段（中线到当前值）
                val top = minOf(y, midY)
                val h = kotlin.math.abs(y - midY)
                if (h > 0.5f) {
                    drawRoundRect(
                        Accent,
                        topLeft = Offset(cx - trackW / 2f, top),
                        size = Size(trackW, h),
                        cornerRadius = radius
                    )
                }
                // 滑块
                drawCircle(Color.White, radius = 5.5.dp.toPx(), center = Offset(cx, y))
                drawCircle(Accent, radius = 2.4.dp.toPx(), center = Offset(cx, y))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = if (enabled) Color.White.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.35f),
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

/** 细胶囊 chip（与歌词设置面板同款） */
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
        modifier
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

/** 小号开关（与歌词设置面板同款） */
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
