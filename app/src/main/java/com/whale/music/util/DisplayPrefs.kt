package com.whale.music.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 展示类偏好（设置页可调，播放页通过 StateFlow 实时生效）。
 *
 * - Hi·Res 金标：开关 + 缩放（0.7 ~ 1.4，默认 1.0）
 */
object DisplayPrefs {

    private const val FILE = "whale_display"
    private const val KEY_HIRES_ENABLED = "hires_badge_enabled"
    private const val KEY_HIRES_SCALE = "hires_badge_scale"

    private val _hiresEnabled = MutableStateFlow(true)
    val hiresEnabled: StateFlow<Boolean> = _hiresEnabled.asStateFlow()

    private val _hiresScale = MutableStateFlow(1.0f)
    val hiresScale: StateFlow<Float> = _hiresScale.asStateFlow()

    @Volatile
    private var sp: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = prefs
        _hiresEnabled.value = prefs.getBoolean(KEY_HIRES_ENABLED, true)
        _hiresScale.value = prefs.getFloat(KEY_HIRES_SCALE, 1.0f)
    }

    fun setHiresEnabled(enabled: Boolean) {
        sp?.edit()?.putBoolean(KEY_HIRES_ENABLED, enabled)?.apply()
        _hiresEnabled.value = enabled
    }

    fun setHiresScale(scale: Float) {
        val v = scale.coerceIn(0.7f, 1.4f)
        sp?.edit()?.putFloat(KEY_HIRES_SCALE, v)?.apply()
        _hiresScale.value = v
    }
}
