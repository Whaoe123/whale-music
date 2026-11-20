package com.whale.music.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.whale.music.R
import com.whale.music.ncm.NcmLevel
import com.whale.music.ui.player.PlayerViewModel
import com.whale.music.update.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（第四页）—— 现在散落在播放页弹窗 / 汽水页里的设置都收拢到这：
 * 音质选择、启动自动播放、检测更新、清理缓存。
 * lx 音源管理在这一节加入（见 LxSourceSection）。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val playerVm: PlayerViewModel = hiltViewModel()
    val updateVm: UpdateViewModel = hiltViewModel()
    val updateState by updateVm.state.collectAsState()

    var quality by remember { mutableStateOf(playerVm.qualityLevel()) }
    var autoplay by remember { mutableStateOf(viewModel.autoplayEnabled()) }
    // Hi·Res 金标（DisplayPrefs StateFlow，改完播放页立刻生效）
    val hiresEnabled by viewModel.hiresEnabled.collectAsState()
    val hiresScale by viewModel.hiresScale.collectAsState()
    // 底部导航栏 / 播放页开关（默认开启）
    val navBarEnabled by viewModel.navBarEnabled.collectAsState()
    val playerPageEnabled by viewModel.playerPageEnabled.collectAsState()
    // 分区折叠：默认展开「播放 / 界面」，其余点标题展开（页面更整洁）
    var secPlay by rememberSaveable { mutableStateOf(true) }
    var secUi by rememberSaveable { mutableStateOf(true) }
    var secBadge by rememberSaveable { mutableStateOf(false) }
    var secGlass by rememberSaveable { mutableStateOf(false) }
    var secUpdate by rememberSaveable { mutableStateOf(false) }
    var secCache by rememberSaveable { mutableStateOf(false) }
    var secAbout by rememberSaveable { mutableStateOf(false) }
    // 液态玻璃（GlassPrefs：折射 / 厚度 / 色散 / 模糊 / 高光，全局玻璃控件实时生效）
    val glassRefraction by com.whale.music.util.GlassPrefs.refraction.collectAsState()
    val glassThickness by com.whale.music.util.GlassPrefs.thickness.collectAsState()
    val glassDispersion by com.whale.music.util.GlassPrefs.dispersion.collectAsState()
    val glassBlur by com.whale.music.util.GlassPrefs.blur.collectAsState()
    val glassHighlight by com.whale.music.util.GlassPrefs.highlight.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── 材质背景：与发现页 / 曲库同一套「封面模糊 + 暗色遮罩」 ──
        val artwork by playerVm.artwork.collectAsState()
        Crossfade(
            targetState = artwork,
            animationSpec = tween(1200),
            label = "settings_bg"
        ) { uri ->
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uri)
                    .size(512, 512)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(64.dp),
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(2.0f) }),
                alpha = 0.9f,
                placeholder = painterResource(R.drawable.default_album_art),
                error = painterResource(R.drawable.default_album_art),
                fallback = painterResource(R.drawable.default_album_art)
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp)
                // 底部给迷你条 + 底部导航让位（导航关掉时只给迷你条留）
                .padding(bottom = if (navBarEnabled) 170.dp else 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
        Spacer(Modifier.height(6.dp))
        Text(
            "设置",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        // ───────── 播放 ─────────
        SettingsCard(
            title = "播放",
            expanded = secPlay,
            onToggle = { secPlay = !secPlay }
        ) {
            // 音质档位（搬自播放页「更多 → 音质」弹窗）
            QualityOptions(
                selected = quality,
                onSelect = { lv ->
                    quality = lv
                    playerVm.setQualityLevel(lv)
                }
            )
            Spacer(Modifier.height(10.dp))
            SettingsDivider()
            Spacer(Modifier.height(6.dp))
            // 启动自动播放（搬自汽水页开关）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启动自动播放", color = Color.White, fontSize = 15.sp)
                    Text(
                        "打开 App 自动接着上次的歌单播",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = autoplay,
                    onCheckedChange = {
                        autoplay = it
                        viewModel.setAutoplayEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF60A5FA),
                        uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f)
                    )
                )
            }
        }

        // ───────── 封面金标（Hi·Res 徽章开关 + 大小） ─────────
        SettingsCard(
            title = "封面金标",
            expanded = secBadge,
            onToggle = { secBadge = !secBadge }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hi·Res 金标", color = Color.White, fontSize = 15.sp)
                    Text(
                        "高音质歌曲在播放页封面下方点亮金色徽章",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = hiresEnabled,
                    onCheckedChange = { viewModel.setHiresEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF60A5FA),
                        uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f)
                    )
                )
            }
            if (hiresEnabled) {
                Spacer(Modifier.height(10.dp))
                SettingsDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "大小",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${"%.2f".format(hiresScale)}×",
                        color = Color(0xFF60A5FA),
                        fontSize = 13.sp
                    )
                }
                Slider(
                    value = hiresScale,
                    onValueChange = { viewModel.setHiresScale(it) },
                    valueRange = 0.7f..1.4f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF60A5FA),
                        activeTrackColor = Color(0xFF60A5FA),
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                    )
                )
            }
        }

        // ───────── 界面（播放页 / 底部导航栏开关） ─────────
        SettingsCard(
            title = "界面",
            expanded = secUi,
            onToggle = { secUi = !secUi }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("播放页", color = Color.White, fontSize = 15.sp)
                    Text(
                        "关掉后隐藏播放页，用迷你播放条控制播放",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = playerPageEnabled,
                    onCheckedChange = { viewModel.setPlayerPageEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF60A5FA),
                        uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f)
                    )
                )
            }
            Spacer(Modifier.height(10.dp))
            SettingsDivider()
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("底部导航栏", color = Color.White, fontSize = 15.sp)
                    Text(
                        "关掉后隐藏底部导航，左右滑动切换页面",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = navBarEnabled,
                    onCheckedChange = { viewModel.setNavBarEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF60A5FA),
                        uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f)
                    )
                )
            }
        }

        // ───────── 液态玻璃（全局玻璃材质：折射 / 厚度 / 色散 / 模糊 / 高光） ─────────
        SettingsCard(
            title = "液态玻璃",
            expanded = secGlass,
            onToggle = { secGlass = !secGlass }
        ) {
            Text(
                "卡片、底部导航、按钮共用的玻璃配方。调完整个界面的玻璃质感同步变化。",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(6.dp))
            GlassSliderRow(
                label = "折射",
                hint = "光线穿过玻璃的弯折程度",
                value = glassRefraction,
                range = 0f..200f,
                valueLabel = "${glassRefraction.toInt()}%",
                onValueChange = { com.whale.music.util.GlassPrefs.setRefraction(it) }
            )
            GlassSliderRow(
                label = "深度 / 厚度",
                hint = "玻璃的厚度感，越厚边缘越厚实",
                value = glassThickness,
                range = 0f..200f,
                valueLabel = "${glassThickness.toInt()}%",
                onValueChange = { com.whale.music.util.GlassPrefs.setThickness(it) }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("色散", color = Color.White, fontSize = 15.sp)
                    Text(
                        "边缘出彩虹色差（chromatic aberration）",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = glassDispersion,
                    onCheckedChange = { com.whale.music.util.GlassPrefs.setDispersion(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF60A5FA),
                        uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f)
                    )
                )
            }
            GlassSliderRow(
                label = "背景模糊",
                hint = "玻璃背后内容的模糊程度",
                value = glassBlur,
                range = 0f..300f,
                valueLabel = "${glassBlur.toInt()}%",
                onValueChange = { com.whale.music.util.GlassPrefs.setBlur(it) }
            )
            GlassSliderRow(
                label = "高光 / 反射",
                hint = "玻璃边缘的亮边强度",
                value = glassHighlight,
                range = 0f..200f,
                valueLabel = "${glassHighlight.toInt()}%",
                onValueChange = { com.whale.music.util.GlassPrefs.setHighlight(it) }
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { com.whale.music.util.GlassPrefs.reset() }) {
                    Text("恢复默认", color = Color(0xFF60A5FA), fontWeight = FontWeight.Bold)
                }
            }
        }

        // ───────── 更新 ─────────
        SettingsCard(
            title = "更新",
            expanded = secUpdate,
            onToggle = { secUpdate = !secUpdate }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("当前版本", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                    Text(
                        updateVm.localVersionLabel(),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("更新状态", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            Text(
                updateStatusText(updateState),
                color = Color(0xFF60A5FA),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { updateVm.checkNow() }) {
                    Text("检测更新", color = Color(0xFF60A5FA), fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "手动检测会直接进下载 / 安装流程，有新版本会弹全局更新框。",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp
            )
        }

        // ───────── 缓存 ─────────
        SettingsCard(
            title = "缓存",
            expanded = secCache,
            onToggle = { secCache = !secCache }
        ) {
            Text(
                "清理播放取址、歌词与搜索缓存。清完下一首会重新联网解析，歌单、收藏不受影响。",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    viewModel.clearCaches {
                        android.widget.Toast.makeText(context, "缓存已清理", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("清理缓存", color = Color(0xFF60A5FA), fontWeight = FontWeight.Bold)
                }
            }
        }

        // ───────── lx 音源 ─────────
        LxSourceSection(viewModel = viewModel)

        // ───────── 关于 ─────────
        SettingsCard(
            title = "关于",
            expanded = secAbout,
            onToggle = { secAbout = !secAbout }
        ) {
            Text(
                "Whale · 在线音乐播放器",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "曲源：网易云 · 酷我（在线内容，播放按「歌名 + 歌手 + 时长」三重核对，" +
                    "对不上就跳过，绝不播成别的歌）。",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }

        Spacer(Modifier.height(8.dp))
        }
    }
}

// ───────── 音质档位（搬自播放页弹窗） ─────────

@Composable
private fun QualityOptions(
    selected: NcmLevel?,
    onSelect: (NcmLevel?) -> Unit
) {
    val options = listOf<Pair<NcmLevel?, String>>(
        null to "自动（会员无损 / 免费 320k）",
        NcmLevel.STANDARD to "标准 128k",
        NcmLevel.HIGHER to "较高 192k",
        NcmLevel.EXHIGH to "极高 320k",
        NcmLevel.LOSSLESS to "无损 FLAC",
        NcmLevel.HIRES to "Hi-Res"
    )
    Text("音质", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
    options.forEach { (lv, label) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(lv) }
        ) {
            RadioButton(selected = selected == lv, onClick = null)
            Spacer(Modifier.width(4.dp))
            Text(label, color = Color.White, fontSize = 14.sp)
        }
    }
    Text(
        "选完立即生效：下一首按新档位重新取地址，已下载的文件不变。无损 / Hi-Res 需要网易云会员。",
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
}

// ───────── lx 自定义音源管理 ─────────

@Composable
private fun LxSourceSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scripts by viewModel.lxScripts.collectAsState()
    var importing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    // 从直链在线导入
    var scriptUrl by remember { mutableStateOf("") }
    var urlImporting by remember { mutableStateOf(false) }
    // 分区折叠（默认收起，点标题展开）
    var secLx by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val err = withContext(Dispatchers.IO) { viewModel.importLx(uri) }
                importing = false
                secLx = true // 结果提示在卡片里，自动展开才看得见
                msg = err ?: "音源已导入，搜索与兜底播放立即可用"
            }
        }
    }

    SettingsCard(
        title = "lx 音源（洛雪自定义脚本）",
        expanded = secLx,
        onToggle = { secLx = !secLx }
    ) {
        Text(
            "导入洛雪 v2 音源脚本后：搜索页可切换该音源，酷我找不到的歌也会用它兜底出声。" +
                "脚本运行在离线沙箱里，不能直连网络，一切请求都走受控通道。",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        if (scripts.isEmpty()) {
            Text(
                "还没有导入音源脚本",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 13.sp
            )
        }
        scripts.forEach { s ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, color = Color.White, fontSize = 14.sp)
                    Text(
                        if (s.enabled) "已启用" else "已停用",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 11.sp
                    )
                }
                Text(
                    "删除",
                    color = Color(0xFFF87171),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { viewModel.deleteLx(s.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Switch(
                    checked = s.enabled,
                    onCheckedChange = { viewModel.setLxEnabled(s.id, it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF60A5FA),
                        uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f)
                    )
                )
            }
        }
        msg?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color(0xFF60A5FA), fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(
                enabled = !importing,
                onClick = { picker.launch(arrayOf("*/*")) }
            ) {
                Text(
                    if (importing) "导入中…" else "从文件导入",
                    color = Color(0xFF60A5FA),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 从直链在线导入（洛雪脚本常见 .js 直链）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = scriptUrl,
                onValueChange = { scriptUrl = it },
                placeholder = {
                    Text(
                        "音源脚本直链 https://…",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.35f)
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White.copy(alpha = 0.85f),
                    focusedBorderColor = Color(0xFF60A5FA),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                    cursorColor = Color.White,
                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.06f)
                )
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = !urlImporting && scriptUrl.isNotBlank(),
                onClick = {
                    urlImporting = true
                    scope.launch {
                        val err = withContext(Dispatchers.IO) {
                            viewModel.importLxFromUrl(scriptUrl)
                        }
                        urlImporting = false
                        secLx = true // 结果提示在卡片里，自动展开才看得见
                        msg = err ?: "音源已导入，搜索与兜底播放立即可用"
                        if (err == null) scriptUrl = ""
                    }
                }
            ) {
                Text(
                    if (urlImporting) "导入中…" else "链接导入",
                    color = Color(0xFF60A5FA),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ───────── 更新状态文案（与原设置弹窗同一套） ─────────

private fun updateStatusText(state: UpdateViewModel.State): String = when (state) {
    is UpdateViewModel.State.Idle -> "尚未检查"
    is UpdateViewModel.State.Checking -> "正在检查更新…"
    is UpdateViewModel.State.UpToDate -> "已是最新版本"
    is UpdateViewModel.State.Available -> "发现新版本 ${state.info.versionName}（${state.info.versionCode}）"
    is UpdateViewModel.State.Downloading -> "下载中 ${(state.progress * 100).toInt()}%"
    is UpdateViewModel.State.Ready -> "已下载完成，可点弹窗里的「立即更新」安装"
    is UpdateViewModel.State.Failed -> "失败：${state.message}"
}

// ───────── 通用卡片（可折叠：点标题行收起 / 展开） ─────────

@Composable
private fun SettingsCard(
    title: String,
    /** null = 不可折叠；传状态让标题行可点 */
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
        ) {
            Text(
                title,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (onToggle != null) {
                val rotation by animateFloatAsState(
                    targetValue = if (expanded == true) 180f else 0f,
                    animationSpec = tween(220),
                    label = "settingsChevron"
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded == true) "收起" else "展开",
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = rotation }
                )
            }
        }
        if (onToggle != null) {
            AnimatedVisibility(visible = expanded == true) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    content()
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SettingsDivider() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.1f))
    ) {}
}

// ───────── 液态玻璃滑杆行（折射 / 厚度 / 模糊 / 高光） ─────────

@Composable
private fun GlassSliderRow(
    label: String,
    hint: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 15.sp)
            Text(hint, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
        }
        Text(valueLabel, color = Color(0xFF60A5FA), fontSize = 13.sp)
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        colors = SliderDefaults.colors(
            thumbColor = Color(0xFF60A5FA),
            activeTrackColor = Color(0xFF60A5FA),
            inactiveTrackColor = Color.White.copy(alpha = 0.15f)
        )
    )
}
