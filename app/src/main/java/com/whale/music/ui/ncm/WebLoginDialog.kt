package com.whale.music.ui.ncm

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** 登录页直链（hash 路由，网易云 SPA） */
private const val LOGIN_URL = "https://music.163.com/#/login"
/** 桌面版 UA：与实测可用请求一致，避免网易云把 WebView 重定向到移动端坏页 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** 网页加载状态 */
private enum class PageState { LOADING, OK, FAILED, TIMEOUT }

/**
 * 网页登录弹窗 —— 扫码被 8821 风控后的主要通道。
 *
 * 原理：用系统 WebView 打开网易云登录页，用户在真实浏览器上下文里登录
 * （密码 / 短信 / 网页扫码都行），登录成功后从 CookieManager 读出
 * HttpOnly 的 MUSIC_U 交给 App。WebView 请求带真实浏览器指纹，
 * 不触发 App 接口层的 8821 拦截。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginDialog(
    onCookie: (String) -> Unit,
    onSwitchToPaste: () -> Unit,
    onDismiss: () -> Unit
) {
    var done by remember { mutableStateOf(false) }
    var pageState by remember { mutableStateOf(PageState.LOADING) }
    var errMsg by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // MUSIC_U 是 HttpOnly，document.cookie 读不到，只能走 CookieManager
    LaunchedEffect(Unit) {
        while (!done) {
            val cookies = try {
                CookieManager.getInstance().getCookie("https://music.163.com")
            } catch (e: Exception) {
                null
            }
            if (cookies != null && cookies.contains("MUSIC_U=")) {
                done = true
                onCookie(cookies)
                break
            }
            delay(1200)
        }
    }

    // 加载超时检测：15 秒还没完成就提示，不再无声空白
    LaunchedEffect(webView) {
        if (webView == null) return@LaunchedEffect
        pageState = PageState.LOADING
        var elapsed = 0L
        while (elapsed < 15_000 && pageState == PageState.LOADING) {
            delay(1000)
            elapsed += 1000
        }
        if (pageState == PageState.LOADING) pageState = PageState.TIMEOUT
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF122244))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "网页登录网易云",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSwitchToPaste) {
                    Text("粘贴Cookie", color = Color(0xFF60A5FA), fontSize = 13.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White, RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webView = this
                            setBackgroundColor(AndroidColor.WHITE)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                loadsImagesAutomatically = true
                                userAgentString = DESKTOP_UA
                                allowContentAccess = true
                                mixedContentMode =
                                    android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    pageState = PageState.OK
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    // 只关心主文档加载失败；子资源错误忽略
                                    if (request?.isForMainFrame == true) {
                                        errMsg = error?.description?.toString()?.take(60) ?: "网络错误"
                                        pageState = PageState.FAILED
                                    }
                                }
                            }
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            loadUrl(LOGIN_URL)
                        }
                    }
                )

                when (pageState) {
                    PageState.LOADING -> Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = Color(0xFF60A5FA),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("正在打开网易云登录页…", color = Color(0xFF334155), fontSize = 13.sp)
                        }
                    }
                    PageState.TIMEOUT -> Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "页面加载很慢。可稍等，或改用「粘贴Cookie」",
                                color = Color(0xFF334155),
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { pageState = PageState.LOADING; webView?.loadUrl(LOGIN_URL) }) {
                                    Text("重试加载", fontSize = 13.sp)
                                }
                                TextButton(onClick = onSwitchToPaste) {
                                    Text("粘贴Cookie", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    PageState.FAILED -> Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("加载失败：$errMsg", color = Color(0xFFB91C1C), fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { pageState = PageState.LOADING; webView?.loadUrl(LOGIN_URL) }) {
                                    Text("重试", fontSize = 13.sp)
                                }
                                TextButton(onClick = onSwitchToPaste) {
                                    Text("改用粘贴 Cookie", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    PageState.OK -> Unit
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "登录成功后自动返回并加载红心歌曲；页面打不开就点「粘贴Cookie」",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

/** 手动粘贴 Cookie（PC 浏览器 F12 复制整串 Cookie 后粘进来） */
@Composable
fun CookieLoginDialog(
    onLogin: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF122244), RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "粘贴 Cookie 登录",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "电脑浏览器登录 music.163.com → F12 → Network → 任一请求的\nCookie 请求头，整串复制（必须包含 MUSIC_U=）",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                placeholder = { Text("MUSIC_U=...; __csrf=...", fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color.White,
                    fontSize = 12.sp
                )
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                }
                TextButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onLogin(text.trim())
                            onDismiss()
                        }
                    }
                ) {
                    Text("登录", color = Color(0xFF60A5FA), fontSize = 14.sp)
                }
            }
        }
    }
}
