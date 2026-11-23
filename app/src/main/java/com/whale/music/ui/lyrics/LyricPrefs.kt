package com.whale.music.ui.lyrics

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * 歌词样式（借鉴 Sonance 的舞台体系，MIT）。
 * stageLabel 是当前句上方小字里的英文舞台名。
 */
enum class LyricStyle(val id: String, val label: String, val stageLabel: String, val desc: String) {
    CLASSIC("classic", "经典", "CLASSIC", "上下滑动切歌的沉浸大字歌词"),
    VERTICAL("vertical", "竖屏歌词", "POETRY SCROLL", "古风右起竖排诗卷，支持中英混排自适应与横向滑动浏览"),
    FLUID("fluid", "流体声波景深", "VOCAL FOCUS", "声波纹理衬底，远离当前句的歌词逐渐景深模糊"),
    VINYL("vinyl", "黑胶环形声轨", "VINYL ORBIT", "黑胶盘面随播放旋转，时间戳内联在歌词左侧"),
    SPATIAL("spatial", "3D 双向深空星廊", "SPATIAL STAGE", "网格地面向纵深延伸，歌词带倒影从远处飘来");

    companion object {
        fun fromId(id: String?): LyricStyle = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

/** 歌词玻璃底板强度：面板透明度 / 默认模糊 / 默认透出。 */
enum class GlassStyle(
    val label: String,
    val surfaceAlpha: Float,
    val defaultBlurDp: Int,
    val defaultVisibility: Int,
    val liquid: Boolean = false
) {
    OFF("关闭", 0f, 0, 100),
    FAINT("淡", 0.10f, 6, 100),
    STANDARD("标准", 0.18f, 10, 95),
    STRONG("强", 0.28f, 16, 88),
    LIQUID("液态玻璃", 0.22f, 20, 92, liquid = true);

    companion object {
        fun fromOrdinal(i: Int): GlassStyle = entries.getOrElse(i) { OFF }
    }
}

/** 亮度自适应：按歌词所在区域的背景明暗调整文字深浅。 */
enum class LyricAdaptMode(val label: String, val desc: String) {
    OFF("关闭", ""),
    CURRENT_ONLY("只调当前句", "当前句按所在区域调：压在偏亮背景上会压暗，偏暗的会提亮"),
    ALL("全部", "当前句和其它行都按所在区域调：压在偏亮背景上的那几行会被压暗，偏暗的会被提亮");

    companion object {
        fun fromOrdinal(i: Int): LyricAdaptMode = entries.getOrElse(i) { OFF }
    }
}

/**
 * 歌词显示偏好（SharedPreferences "whale_lyrics"）。
 * 全部项 StateFlow 化，设置面板改完即时生效，不用重建页面。
 * 「歌词偏移」按歌曲存：只对这首歌生效。
 */
object LyricPrefs {
    private const val PREFS = "whale_lyrics"

    private lateinit var prefs: android.content.SharedPreferences

    private val _style = MutableStateFlow(LyricStyle.CLASSIC)
    val style: StateFlow<LyricStyle> = _style

    private val _glassStyle = MutableStateFlow(GlassStyle.STANDARD)
    val glassStyle: StateFlow<GlassStyle> = _glassStyle

    private val _glassBlurDp = MutableStateFlow(10)
    val glassBlurDp: StateFlow<Int> = _glassBlurDp

    /** 透出 %：玻璃透出程度（越大背景越清晰），影响歌词文字的不透明度 */
    private val _glassVisibility = MutableStateFlow(92)
    val glassVisibility: StateFlow<Int> = _glassVisibility

    private val _fontSize = MutableStateFlow(23)
    val fontSize: StateFlow<Int> = _fontSize

    private val _simpleChinese = MutableStateFlow(true)
    val simpleChinese: StateFlow<Boolean> = _simpleChinese

    private val _adaptMode = MutableStateFlow(LyricAdaptMode.ALL)
    val adaptMode: StateFlow<LyricAdaptMode> = _adaptMode

    private val _hideStatusBar = MutableStateFlow(false)
    val hideStatusBar: StateFlow<Boolean> = _hideStatusBar

    /** 沉浸歌词页（全屏玻璃 + 大字贴边 + 紧凑控件）；关 = 经典布局 */
    private val _immersivePage = MutableStateFlow(true)
    val immersivePage: StateFlow<Boolean> = _immersivePage

    private val _lyricHaptic = MutableStateFlow(true)
    val lyricHaptic: StateFlow<Boolean> = _lyricHaptic

    /** 歌词页闲置 3 秒自动收起所有控件（歌名 / 进度条 / 按钮），点一下再淡回来 */
    private val _autoHideControls = MutableStateFlow(true)
    val autoHideControls: StateFlow<Boolean> = _autoHideControls

    /** 歌曲 key（"${albumId}:${id}"）→ 偏移秒数（歌词慢了往正调） */
    private val _offsets = MutableStateFlow<Map<String, Float>>(emptyMap())
    val offsets: StateFlow<Map<String, Float>> = _offsets

    /** init 前被调用时不崩（UI 先行渲染场景），偏好暂不落盘 */
    private fun persist(block: (android.content.SharedPreferences.Editor) -> Unit) {
        if (::prefs.isInitialized) {
            val e = prefs.edit()
            block(e)
            e.apply()
        }
    }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _style.value = LyricStyle.fromId(prefs.getString("style", LyricStyle.CLASSIC.id))
        _glassStyle.value = GlassStyle.fromOrdinal(prefs.getInt("glass_style", GlassStyle.STANDARD.ordinal))
        _glassBlurDp.value = prefs.getInt("glass_blur", 10)
        _glassVisibility.value = prefs.getInt("glass_visibility", 92)
        _fontSize.value = prefs.getInt("font_size", 23)
        _simpleChinese.value = prefs.getBoolean("simple_cn", true)
        _adaptMode.value = LyricAdaptMode.fromOrdinal(prefs.getInt("adapt_mode", LyricAdaptMode.ALL.ordinal))
        _hideStatusBar.value = prefs.getBoolean("hide_status_bar", false)
        _immersivePage.value = prefs.getBoolean("immersive_page", true)
        _lyricHaptic.value = prefs.getBoolean("lyric_haptic", true)
        _autoHideControls.value = prefs.getBoolean("auto_hide_controls", true)
        _offsets.value = parseOffsets(prefs.getString("offsets", "{}") ?: "{}")
    }

    private fun parseOffsets(json: String): Map<String, Float> = try {
        val obj = JSONObject(json)
        buildMap {
            for (k in obj.keys()) put(k, obj.getDouble(k).toFloat())
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun saveOffsets(map: Map<String, Float>) {
        if (!::prefs.isInitialized) return
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v.toDouble()) }
        persist { e -> e.putString("offsets", obj.toString()) }
    }

    fun setImmersivePage(v: Boolean) {
        _immersivePage.value = v
        persist { e -> e.putBoolean("immersive_page", v) }
    }

    fun setStyle(v: LyricStyle) {
        _style.value = v
        persist { e -> e.putString("style", v.id) }
    }

    fun setGlassStyle(v: GlassStyle) {
        _glassStyle.value = v
        // 切强度顺带套用该强度的默认模糊/透出（同 Sonance 交互）
        _glassBlurDp.value = v.defaultBlurDp
        _glassVisibility.value = v.defaultVisibility
        persist { e ->
             e.putInt("glass_style", v.ordinal)
             e.putInt("glass_blur", v.defaultBlurDp)
             e.putInt("glass_visibility", v.defaultVisibility)
             }
    }

    fun setGlassBlurDp(v: Int) {
        _glassBlurDp.value = v.coerceIn(0, 30)
        persist { e -> e.putInt("glass_blur", _glassBlurDp.value) }
    }

    fun setGlassVisibility(v: Int) {
        _glassVisibility.value = v.coerceIn(40, 100)
        persist { e -> e.putInt("glass_visibility", _glassVisibility.value) }
    }

    fun setFontSize(v: Int) {
        _fontSize.value = v.coerceIn(14, 40)
        persist { e -> e.putInt("font_size", _fontSize.value) }
    }

    fun setSimpleChinese(v: Boolean) {
        _simpleChinese.value = v
        persist { e -> e.putBoolean("simple_cn", v) }
    }

    fun setAdaptMode(v: LyricAdaptMode) {
        _adaptMode.value = v
        persist { e -> e.putInt("adapt_mode", v.ordinal) }
    }

    fun setHideStatusBar(v: Boolean) {
        _hideStatusBar.value = v
        persist { e -> e.putBoolean("hide_status_bar", v) }
    }

    fun setLyricHaptic(v: Boolean) {
        _lyricHaptic.value = v
        persist { e -> e.putBoolean("lyric_haptic", v) }
    }

    fun setAutoHideControls(v: Boolean) {
        _autoHideControls.value = v
        persist { e -> e.putBoolean("auto_hide_controls", v) }
    }

    fun songKey(albumId: Long, id: Long): String = "$albumId:$id"

    /** 这首歌的偏移秒数 */
    fun offsetSec(key: String): Float = _offsets.value[key] ?: 0f

    fun setOffsetSec(key: String, sec: Float) {
        val rounded = (sec * 10).toInt() / 10f // 0.1s 步进
        val next = if (abs(rounded) < 0.05f) {
            _offsets.value - key
        } else {
            _offsets.value + (key to rounded)
        }
        _offsets.value = next
        saveOffsets(next)
    }

    private fun abs(v: Float) = if (v < 0) -v else v
}
