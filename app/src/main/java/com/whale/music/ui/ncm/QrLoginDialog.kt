package com.whale.music.ui.ncm

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.whale.music.ncm.QrStatus
import kotlinx.coroutines.delay

/**
 * 网易云扫码登录弹窗 — 网页全程不上屏：
 * 二维码内容 = https://music.163.com/login?codekey=<unikey>，zxing 本地生成，
 * 后台轮询扫码状态，用网易云 App 扫码完成登录。
 */
@Composable
fun QrLoginDialog(
    viewModel: NcmViewModel,
    onDismiss: () -> Unit,
    onSwitchToWeb: () -> Unit = {}
) {
    val qr by viewModel.qrState.collectAsState()
    val pollInfo by viewModel.pollInfo.collectAsState()

    // 打开即取码（startLogin 幂等：已有一张有效码不会重复换 key）
    LaunchedEffect(Unit) { viewModel.startLogin() }

    // 登录成功：短暂停留展示后自动关窗
    LaunchedEffect(qr?.status) {
        if (qr?.status == QrStatus.SUCCESS) {
            delay(900)
            viewModel.stopLogin()
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = {
            viewModel.stopLogin()
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xFF122244), RoundedCornerShape(24.dp))
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "网易云音乐登录 · 20261115c",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "打开网易云 App「扫一扫」",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(18.dp))

            // 二维码（白底保证可扫）
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = qr?.let { rememberQrBitmap(it.qrUrl) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "登录二维码",
                        modifier = Modifier.size(192.dp)
                    )
                } else {
                    Text(
                        text = "二维码加载中…",
                        color = Color(0xFF122244),
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            val statusText = when (qr?.status) {
                QrStatus.SCANNED -> "已扫码，请在手机上确认"
                QrStatus.EXPIRED -> "二维码已过期"
                QrStatus.SUCCESS -> "登录成功"
                QrStatus.RISK_CONTROLLED -> "登录请求被风控拦截，请切换网络"
                QrStatus.BLOCKED -> "网易云拒绝了扫码登录（8821），请改用网页登录"
                else -> "等待扫码…"
            }
            Text(
                text = statusText,
                color = when (qr?.status) {
                    QrStatus.EXPIRED -> Color(0xFFFFB4A2)
                    QrStatus.SUCCESS -> Color(0xFF93C5FD)
                    else -> Color.White.copy(alpha = 0.75f)
                },
                fontSize = 14.sp
            )

            Spacer(Modifier.height(4.dp))

            // 轮询诊断行（排障用：响应码 / 登录态名单 / 时间）
            Text(
                text = pollInfo,
                color = Color(0xFF93C5FD),
                fontSize = 12.sp
            )

            if (qr?.status == QrStatus.BLOCKED) {
                TextButton(onClick = onSwitchToWeb) {
                    Text(
                        text = "改用网页登录",
                        color = Color(0xFF60A5FA),
                        fontSize = 15.sp
                    )
                }
            } else {
                TextButton(onClick = { viewModel.startLogin(force = true) }) {
                    Text(
                        text = if (qr?.status == QrStatus.EXPIRED) "刷新二维码" else "换一个二维码",
                        color = Color(0xFF60A5FA),
                        fontSize = 13.sp
                    )
                }
            }
            TextButton(onClick = {
                viewModel.stopLogin()
                onDismiss()
            }) {
                Text(
                    text = "取消",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            }
        }
    }
}

/** zxing 生成黑白二维码位图（内容变化才重新生成） */
@Composable
private fun rememberQrBitmap(content: String): Bitmap? {
    return androidx.compose.runtime.remember(content) {
        try {
            val size = 512
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    bmp.setPixel(
                        x, y,
                        if (matrix.get(x, y)) android.graphics.Color.BLACK
                        else android.graphics.Color.WHITE
                    )
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
