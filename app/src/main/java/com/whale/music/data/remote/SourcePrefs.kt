package com.whale.music.data.remote

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放取流的音源选择与先后顺序（多选，默认四个全开）。
 *
 * [ALL] 的排列就是兜底优先级：网易云 → 哔哩哔哩 → 酷我 → lx 脚本。
 * 设置页勾选/取消（至少保留一个，否则一首歌都出不了声），
 * [RemoteMusicController] 每次取流读 [order] 按顺序逐个试。
 *
 * 只勾一个音源 = 该音源独占出声，方便逐个测试。
 */
object SourcePrefs {

    private const val PREFS = "whale_sources"

    /** 固定优先级顺序（展示顺序 = 兜底顺序），id 与搜索音源 / RemoteSong.source 对齐 */
    val ALL = listOf("ncm", "bili", "kw", "lx")

    private lateinit var prefs: SharedPreferences

    private val _enabled = MutableStateFlow(ALL.toSet())

    /** 已勾选的音源集合（顺序一律按 [ALL]，集合本身不承载顺序） */
    val enabled: StateFlow<Set<String>> = _enabled

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString("enabled", null)
        _enabled.value = if (saved == null) {
            ALL.toSet() // 首次安装：四个全开
        } else {
            saved.split(',')
                .filter { it in ALL }
                .toSet()
                .ifEmpty { ALL.toSet() } // 老数据坏了也别变成「一个都没有」
        }
    }

    /** 当前生效的音源顺序（按 [ALL] 的优先级排列，只含已勾选的） */
    fun order(): List<String> = ALL.filter { it in _enabled.value }

    /**
     * 勾选 / 取消一个音源。
     * @return false = 拒绝（最后一个音源不能取消，至少要留一个出声的）
     */
    fun setEnabled(id: String, on: Boolean): Boolean {
        if (id !in ALL) return false
        val next = if (on) _enabled.value + id else _enabled.value - id
        if (next.isEmpty()) return false
        _enabled.value = next
        if (::prefs.isInitialized) {
            prefs.edit().putString("enabled", order().joinToString(",")).apply()
        }
        return true
    }
}
