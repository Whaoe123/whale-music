package com.whale.music.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 液态玻璃全局配方（设置页「液态玻璃」可调，所有玻璃控件实时生效）。
 *
 * 五项参数对应 kyant backdrop 的 lens/effects：
 * - 折射 refraction：lens 折射强度（100 = 基准 32dp）
 * - 深度/厚度 thickness：lens 厚度（100 = 基准 16dp，depthEffect 常开）
 * - 色散 dispersion：lens chromaticAberration（库为布尔开关）
 * - 背景模糊 blur：采样模糊半径（100 = 基准 4.3dp）
 * - 高光/反射 highlight：边缘高光强度（100 = 基准 0.78 alpha）
 *
 * 数值为百分比（0 ~ 200 / 0 ~ 300），默认 100 = AuraPlayer 基准配方。
 */
object GlassPrefs {

    private const val FILE = "whale_glass"
    private const val KEY_REFRACTION = "glass_refraction"
    private const val KEY_THICKNESS = "glass_thickness"
    private const val KEY_DISPERSION = "glass_dispersion"
    private const val KEY_BLUR = "glass_blur"
    private const val KEY_HIGHLIGHT = "glass_highlight"

    /** 折射 0~200 */
    private val _refraction = MutableStateFlow(100f)
    val refraction: StateFlow<Float> = _refraction.asStateFlow()

    /** 深度/厚度 0~200 */
    private val _thickness = MutableStateFlow(100f)
    val thickness: StateFlow<Float> = _thickness.asStateFlow()

    /** 色散（chromatic aberration） */
    private val _dispersion = MutableStateFlow(false)
    val dispersion: StateFlow<Boolean> = _dispersion.asStateFlow()

    /** 背景模糊 0~300 */
    private val _blur = MutableStateFlow(100f)
    val blur: StateFlow<Float> = _blur.asStateFlow()

    /** 高光/反射 0~200 */
    private val _highlight = MutableStateFlow(100f)
    val highlight: StateFlow<Float> = _highlight.asStateFlow()

    @Volatile
    private var sp: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = prefs
        _refraction.value = prefs.getFloat(KEY_REFRACTION, 100f)
        _thickness.value = prefs.getFloat(KEY_THICKNESS, 100f)
        _dispersion.value = prefs.getBoolean(KEY_DISPERSION, false)
        _blur.value = prefs.getFloat(KEY_BLUR, 100f)
        _highlight.value = prefs.getFloat(KEY_HIGHLIGHT, 100f)
    }

    fun setRefraction(percent: Float) {
        val v = percent.coerceIn(0f, 200f)
        sp?.edit()?.putFloat(KEY_REFRACTION, v)?.apply()
        _refraction.value = v
    }

    fun setThickness(percent: Float) {
        val v = percent.coerceIn(0f, 200f)
        sp?.edit()?.putFloat(KEY_THICKNESS, v)?.apply()
        _thickness.value = v
    }

    fun setDispersion(enabled: Boolean) {
        sp?.edit()?.putBoolean(KEY_DISPERSION, enabled)?.apply()
        _dispersion.value = enabled
    }

    fun setBlur(percent: Float) {
        val v = percent.coerceIn(0f, 300f)
        sp?.edit()?.putFloat(KEY_BLUR, v)?.apply()
        _blur.value = v
    }

    fun setHighlight(percent: Float) {
        val v = percent.coerceIn(0f, 200f)
        sp?.edit()?.putFloat(KEY_HIGHLIGHT, v)?.apply()
        _highlight.value = v
    }

    /** 恢复默认（AuraPlayer 基准配方） */
    fun reset() {
        setRefraction(100f)
        setThickness(100f)
        setDispersion(false)
        setBlur(100f)
        setHighlight(100f)
    }
}
