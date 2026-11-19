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
        data class Downloading(val info: UpdateInfo, val progress: Float, val speedKbps: Float = 0f) : State
        data class Ready(val info: UpdateInfo, val file: File) : State
        data class Failed(val message: String, val info: UpdateInfo) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 本次检查结果（Toast 用）：失败原因 / 当前与服务器版本对照 */
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    private var checking = false

    /** 手动点「检测更新」触发的检查：发现新版本直接自动下载（启动时的自动检查不自动下，免得偷跑流量） */
    private var autoUpdate = false

    /** 自动下载完成后，不再多问一步，直接拉起系统安装页 */
    private var autoInstallWhenReady = false

    /** 启动后延迟调用一次；重复调用会被忽略 */
    fun check() {
        if (checking) return
        checking = true
        _state.value = State.Checking
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { repo.fetchRemote() }
            val local = withContext(Dispatchers.IO) { repo.currentVersionCode() }
            // 消费「手动检测自动更新」标记：本次检查无论成败都用掉了，不留给下次启动
            val auto = autoUpdate
            autoUpdate = false
            if (info == null) {
                // 检查失败：不弹窗，但告诉用户一声，避免"以为没在更新"
                _state.value = State.UpToDate
                _note.value = "更新检查失败：连不上服务器（本机 $local）"
                return@launch
            }
            if (repo.hasNewVersion(info)) {
                _state.value = State.Available(info)
                if (auto) {
                    // 手动检测：直接开下，一路到系统安装页，不再让用户多点「立即更新」
                    autoInstallWhenReady = true
                    download()
                }
            } else {
                _state.value = State.UpToDate
                _note.value = "更新检查完成：已是最新（本机 $local / 服务器 ${info.versionCode}）"
            }
        }
    }

    fun consumeNote() {
        _note.value = null
    }

    /** 设置页「检测更新」用：强制重新检查一次（忽略上次结果与节流），找到新版直接更 */
    fun checkNow() {
        checking = false
        _state.value = State.Idle
        _note.value = null
        autoUpdate = true
        check()
    }

    /** 设置页展示用：当前版本号 */
    fun localVersionLabel(): String = repo.localVersionLabel()

    /** 服务器上的最新版本（已检查过才有值），设置页对照显示 */
    fun remoteVersionLabel(): String? = when (val s = _state.value) {
        is State.Available -> "${s.info.versionName}（版本号 ${s.info.versionCode}）"
        else -> null
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
                    repo.download(info) { p, speed ->
                        _state.value = State.Downloading(info, p, speed)
                    }
                }
                _state.value = State.Ready(info, file)
                if (autoInstallWhenReady) {
                    autoInstallWhenReady = false
                    install() // 自动流程：直接拉起系统安装页（系统仍会弹确认，不会静默装）
                }
            } catch (e: Exception) {
                Log.w("UpdateViewModel", "下载更新失败", e)
                autoInstallWhenReady = false
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
