package com.whale.music.player.audio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 音效与可视化的持久化（SharedPreferences "whale_audio_fx"）。
 *
 * UI 写入 → 立刻推到 [EqBus]（音频线程读）→ 落盘；
 * 进程重启后 [init] 从盘里恢复再推一次 [EqBus]。
 */
object FxPrefs {

    private const val PREFS = "whale_audio_fx"

    private lateinit var prefs: SharedPreferences

    // ── 均衡器 ──

    private val _eqEnabled = MutableStateFlow(false)
    val eqEnabled: StateFlow<Boolean> = _eqEnabled

    private val _eqPreset = MutableStateFlow(EqPreset.OFF)
    val eqPreset: StateFlow<EqPreset> = _eqPreset

    /** 各段增益（dB），与 [EQ_CENTER_FREQS] 一一对应 */
    private val _eqGains = MutableStateFlow(FloatArray(EQ_BAND_COUNT))
    val eqGains: StateFlow<FloatArray> = _eqGains

    // ── 频谱可视化 ──

    private val _spectrumEnabled = MutableStateFlow(true)
    val spectrumEnabled: StateFlow<Boolean> = _spectrumEnabled

    /** 律动幅度 0.5..1.0 */
    private val _spectrumLevel = MutableStateFlow(1.0f)
    val spectrumLevel: StateFlow<Float> = _spectrumLevel

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val enabled = prefs.getBoolean("eq_enabled", false)
        val preset = EqPreset.fromName(prefs.getString("eq_preset", EqPreset.OFF.name))
        val gains = decodeGains(prefs.getString("eq_gains", null))

        _eqEnabled.value = enabled
        _eqPreset.value = preset
        _eqGains.value = gains
        _spectrumEnabled.value = prefs.getBoolean("spectrum_enabled", true)
        _spectrumLevel.value = prefs.getFloat("spectrum_level", 1.0f).coerceIn(0.5f, 1.0f)

        // 恢复到音频线程可见的总线
        EqBus.update(enabled, gains)
    }

    // ───────────────────────── 均衡器 ─────────────────────────

    fun setEqEnabled(v: Boolean) {
        _eqEnabled.value = v
        push()
    }

    /** 选预设：曲线覆盖当前增益；选非「关闭」预设意味着想听效果，顺手打开总开关 */
    fun applyPreset(preset: EqPreset) {
        _eqPreset.value = preset
        _eqGains.value = EqPreset.curveOf(preset)
        if (preset != EqPreset.OFF) _eqEnabled.value = true
        push()
    }

    /** 手动拖动某一段：增益更新并打开总开关（调了就是想听） */
    fun setEqGain(band: Int, db: Float) {
        if (band !in 0 until EQ_BAND_COUNT) return
        val next = _eqGains.value.copyOf()
        next[band] = db.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
        _eqGains.value = next
        _eqEnabled.value = true
        push()
    }

    fun resetEq() {
        _eqEnabled.value = false
        _eqPreset.value = EqPreset.OFF
        _eqGains.value = FloatArray(EQ_BAND_COUNT)
        push()
    }

    private fun push() {
        if (!::prefs.isInitialized) return
        EqBus.update(_eqEnabled.value, _eqGains.value)
        prefs.edit()
            .putBoolean("eq_enabled", _eqEnabled.value)
            .putString("eq_preset", _eqPreset.value.name)
            .putString("eq_gains", encodeGains(_eqGains.value))
            .apply()
    }

    // ───────────────────────── 频谱 ─────────────────────────

    fun setSpectrumEnabled(v: Boolean) {
        _spectrumEnabled.value = v
        if (::prefs.isInitialized) prefs.edit().putBoolean("spectrum_enabled", v).apply()
        if (!v) PcmBus.rms = 0f
    }

    fun setSpectrumLevel(v: Float) {
        _spectrumLevel.value = v.coerceIn(0.5f, 1.0f)
        if (::prefs.isInitialized) {
            prefs.edit().putFloat("spectrum_level", _spectrumLevel.value).apply()
        }
    }

    // ───────────────────────── 编解码 ─────────────────────────

    /** "1.0,-0.5,..." 与 EQ_CENTER_FREQS 同序；null/异常回全 0 */
    private fun decodeGains(raw: String?): FloatArray {
        if (raw.isNullOrBlank()) return FloatArray(EQ_BAND_COUNT)
        val parts = raw.split(',')
        return FloatArray(EQ_BAND_COUNT) { i ->
            parts.getOrNull(i)?.toFloatOrNull()?.coerceIn(EQ_MIN_DB, EQ_MAX_DB) ?: 0f
        }
    }

    private fun encodeGains(gains: FloatArray): String =
        (0 until EQ_BAND_COUNT).joinToString(",") { i ->
            (gains.getOrElse(i) { 0f }).toString()
        }
}
