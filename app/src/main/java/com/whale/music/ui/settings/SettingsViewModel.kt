package com.whale.music.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whale.music.data.local.PlaybackMemory
import com.whale.music.data.lx.LxSourceManager
import com.whale.music.data.remote.KwSource
import com.whale.music.data.remote.LyricSource
import com.whale.music.data.remote.RemoteMusicController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 设置页状态：启动自动播放开关 + 一键清理缓存 + lx 音源脚本管理。
 * 音质选择走 PlayerViewModel（与播放页共用同一实例），更新走 UpdateViewModel。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val memory: PlaybackMemory,
    private val controller: RemoteMusicController,
    private val lxManager: LxSourceManager
) : ViewModel() {

    /** 已导入的 lx 音源脚本（实时流） */
    val lxScripts: StateFlow<List<LxSourceManager.LxScript>> = lxManager.scripts

    fun autoplayEnabled(): Boolean = memory.autoplayEnabled

    fun setAutoplayEnabled(enabled: Boolean) {
        memory.autoplayEnabled = enabled
    }

    /** 导入 lx 脚本（本地文件）；返回 null = 成功，否则是给用户看的原因 */
    suspend fun importLx(uri: Uri): String? = lxManager.importFrom(uri)

    /** 从直链在线导入 lx 脚本；返回 null = 成功 */
    suspend fun importLxFromUrl(url: String): String? = lxManager.importFromUrl(url)

    fun setLxEnabled(id: String, enabled: Boolean) = lxManager.setEnabled(id, enabled)

    fun deleteLx(id: String) = lxManager.delete(id)

    // ───────── Hi·Res 金标（DisplayPrefs：StateFlow，播放页实时生效） ─────────

    val hiresEnabled = com.whale.music.util.DisplayPrefs.hiresEnabled
    val hiresScale = com.whale.music.util.DisplayPrefs.hiresScale

    fun setHiresEnabled(enabled: Boolean) =
        com.whale.music.util.DisplayPrefs.setHiresEnabled(enabled)

    fun setHiresScale(scale: Float) = com.whale.music.util.DisplayPrefs.setHiresScale(scale)

    /**
     * 清理各类缓存：播放取址 / 歌词（含磁盘哨兵）/ 酷我搜索查询词 / lx 匹配。
     * 音质切换的地址缓存清理由 PlayerViewModel 负责，这里全量兜底。
     */
    fun clearCaches(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                controller.clearResolvedUrls()
                LyricSource.clearCache()
                KwSource.clearCaches()
                lxManager.clearCaches()
            }
            // Toast 要在主线程弹
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}
