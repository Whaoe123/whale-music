package com.whale.music.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.music.R
import com.whale.music.update.UpdateViewModel

/**
 * 应用内更新弹窗：新版本说明 / 下载进度 / 安装 / 失败重试。
 * 只在有新版本时由 MainActivity 挂载，其余状态不渲染任何东西。
 */
@Composable
fun UpdateDialog(
    state: UpdateViewModel.State,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onInstall: () -> Unit
) {
    val info = when (state) {
        is UpdateViewModel.State.Available -> state.info
        is UpdateViewModel.State.Downloading -> state.info
        is UpdateViewModel.State.Ready -> state.info
        is UpdateViewModel.State.Failed -> state.info
        else -> return
    }

    AlertDialog(
        onDismissRequest = {
            if (state !is UpdateViewModel.State.Downloading) onDismiss()
        },
        containerColor = Color(0xFF1C1C1E),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.75f),
        title = {
            Text(
                text = stringResource(R.string.update_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.update_version, info.versionName),
                    fontSize = 13.sp,
                    color = Color(0xFF60A5FA)
                )

                val changelog = info.changelog.trim()
                if (changelog.isNotEmpty()) {
                    Text(
                        text = changelog,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                when (state) {
                    is UpdateViewModel.State.Downloading -> {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF3B82F6),
                            trackColor = Color(0x40FFFFFF)
                        )
                        val percent = (state.progress * 100).toInt()
                        val speed = state.speedKbps
                        Text(
                            text = if (speed > 0f) {
                                stringResource(
                                    R.string.update_downloading_speed,
                                    percent,
                                    formatSpeed(speed)
                                )
                            } else {
                                stringResource(R.string.update_downloading, percent)
                            },
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }

                    is UpdateViewModel.State.Failed -> {
                        Text(
                            text = stringResource(R.string.update_failed, state.message),
                            fontSize = 13.sp,
                            color = Color(0xFFFF6B6B)
                        )
                    }

                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateViewModel.State.Available -> TextButton(onClick = onUpdate) {
                    Text(
                        stringResource(R.string.update_now),
                        color = Color(0xFF60A5FA),
                        fontWeight = FontWeight.Bold
                    )
                }

                is UpdateViewModel.State.Downloading -> {}

                is UpdateViewModel.State.Ready -> TextButton(onClick = onInstall) {
                    Text(
                        stringResource(R.string.update_install),
                        color = Color(0xFF60A5FA),
                        fontWeight = FontWeight.Bold
                    )
                }

                is UpdateViewModel.State.Failed -> TextButton(onClick = onUpdate) {
                    Text(stringResource(R.string.update_retry), color = Color(0xFF60A5FA))
                }

                else -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_close), color = Color(0xFF60A5FA))
                }
            }
        },
        dismissButton = {
            when (state) {
                is UpdateViewModel.State.Available,
                is UpdateViewModel.State.Failed -> TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(R.string.update_later),
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }

                is UpdateViewModel.State.Ready -> TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(R.string.update_later),
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }

                else -> Unit
            }
        }
    )
}

/** 速度显示：>=1024 KB/s 用 MB/s */
private fun formatSpeed(kbps: Float): String =
    if (kbps >= 1024f) String.format("%.1f MB/s", kbps / 1024f)
    else String.format("%d KB/s", kbps.toInt())
