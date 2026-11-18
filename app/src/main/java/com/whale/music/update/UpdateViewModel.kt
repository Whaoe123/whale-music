package com.whale.music.update

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 应用内更新状态机：
 * Idle → Checking → UpToDate / Available → Downloading → Ready（点「安装」跳系统安装器）
 * 任何一步失败进入 Failed 可重试；启动检查失败则静默结束，不打扰用户。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repo: UpdateRepository
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val info: UpdateInfo, val progress: Float) : State
        data class Ready(val info: UpdateInfo, val file: File) : State
        data class Failed(val message: String, val info: UpdateInfo) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var checking = false

    /** 启动后延迟调用一次；重复调用会被忽略 */
    fun check() {
        if (checking) return
        checking = true
        _state.value = State.Checking
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { repo.fetchRemote() }
            if (info == null) {
                // 检查失败静默收尾：不提示、不报错
                _state.value = State.UpToDate
                return@launch
            }
            _state.value = if (repo.hasNewVersion(info)) {
                State.Available(info)
            } else {
                State.UpToDate
            }
        }
    }

    /** 「以后再说」：本版本不再提示；同时放开检查，下次启动还能重新查 */
    fun dismiss() {
        val s = _state.value
        if (s is State.Available) repo.dismiss(s.info.versionCode)
        _state.value = State.UpToDate
        checking = false
    }

    /** 「立即更新」：后台下载并校验 sha256 */
    fun download() {
        val info = when (val s = _state.value) {
            is State.Available -> s.info
            is State.Failed -> s.info
            is State.Ready -> s.info
            else -> return
        }
        _state.value = State.Downloading(info, 0f)
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    repo.download(info) { p ->
                        _state.value = State.Downloading(info, p)
                    }
                }
                _state.value = State.Ready(info, file)
            } catch (e: Exception) {
                Log.w("UpdateViewModel", "下载更新失败", e)
                _state.value = State.Failed(e.message ?: "下载失败", info)
            }
        }
    }

    /** 点「安装」：先确认未知来源权限，再交给系统安装器 */
    fun install() {
        val ready = _state.value as? State.Ready ?: return
        if (!repo.canInstall()) {
            repo.requestInstallPermission()
            return
        }
        if (!repo.verify(ready.file, ready.info.sha256)) {
            _state.value = State.Failed("校验失败", ready.info)
            return
        }
        runCatching { repo.install(ready.file) }.onFailure {
            _state.value = State.Failed("无法启动安装", ready.info)
        }
    }

    /** 安装页回来后继续安装（未知来源权限刚被授予） */
    fun retryInstall() {
        if (_state.value is State.Ready) install()
    }
}
