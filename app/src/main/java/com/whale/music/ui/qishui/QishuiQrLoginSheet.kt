package com.whale.music.ui.qishui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/** 二维码卡片状态 */
private enum class QrPhase { LOADING, SHOWING, SCANNED, EXPIRED, TIMEOUT }

/**
 * 只显示二维码的登录卡片 —— 网页全程在屏幕外跑，界面上永远只有一张二维码图片。
 *
 * 依据实测（2026-11）：
 * - 汽水音乐没有自己的网页登录（qishui.douyin.com 全站是下载引导页），
 *   它的账号体系就是抖音账号，所以二维码只能来自抖音 web 登录弹窗。
 * - 直连 passport 二维码接口必被 4031 风控（需前端 JS 现算 a_bogus），只能在浏览器环境里取。
 * - https://www.douyin.com/user/self 未登录会直接弹出登录面板，且没有首页视频流抢资源，
 *   二维码约 7~9 秒出现，比加载首页快很多、也不卡。
 * - 二维码是 <img src="data:image/png;base64...">（约 178×178），可直接取原始 base64 解码成位图，
 *   不截图、不裁切，永远清晰且尺寸由我们控制。
 *
 * 因此：离屏 WebView 只负责「跑 JS 拿到二维码 base64」，界面用 Compose Image 渲染，
 * 用户看到的是一张干净的二维码卡片，没有任何网页痕迹。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun QishuiQrLoginSheet(
    onClose: () -> Unit,
    onLoggedIn: (cookie: String, nickname: String) -> Unit
) {
    var phase by remember { mutableStateOf(QrPhase.LOADING) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var fullMode by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val dismissInteraction = remember { MutableInteractionSource() }

    // 登录成功：Cookie 里出现 sessionid 就算完成
    LaunchedEffect(Unit) {
        val cm = CookieManager.getInstance()
        while (true) {
            val cookie = runCatching { cm.getCookie("https://www.douyin.com") }
                .getOrNull().orEmpty()
            if (cookie.contains("sessionid=") || cookie.contains("sessionid_ss=") ||
                cookie.contains("sid_tt=")
            ) {
                onLoggedIn(cookie, "")
                break
            }
            delay(1000)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { webView?.stopLoading() }
            runCatching { webView?.destroy() }
            webView = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── 遮罩（仅非完整页面模式）──
        if (!fullMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.74f))
                    .clickable(
                        interactionSource = dismissInteraction,
                        indication = null,
                        onClick = onClose
                    )
            )
        }

        if (!fullMode) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                QrCard(
                    phase = phase,
                    qrBitmap = qrBitmap,
                    onClose = onClose,
                    onRefresh = {
                        phase = QrPhase.LOADING
                        qrBitmap = null
                        reloadKey++
                    },
                    onFullMode = { fullMode = true }
                )
            }
        }

        // ── WebView：屏幕外运行；仅"完整页面"模式才移到前台显示 ──
        AndroidView(
            modifier = if (fullMode) {
                Modifier
                    .fillMaxSize()
                    .padding(top = 44.dp, bottom = 24.dp)
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
            } else {
                Modifier
                    .requiredSize(390.dp, 844.dp)
                    .offset(x = (-1600).dp)
                    .graphicsLayer { alpha = 0.01f }
            },
            factory = { ctx -> createLoginWebView(ctx, onPhase = { p ->
                phase = when (p) {
                    "qr" -> if (phase == QrPhase.LOADING || phase == QrPhase.EXPIRED) {
                        QrPhase.SHOWING
                    } else phase
                    "scanned" -> QrPhase.SCANNED
                    "expired" -> QrPhase.EXPIRED
                    "timeout" -> if (qrBitmap == null) QrPhase.TIMEOUT else phase
                    else -> phase
                }
            }, onQr = { src ->
                val bmp = decodeQr(src)
                if (bmp != null) {
                    qrBitmap = bmp
                    if (phase == QrPhase.LOADING || phase == QrPhase.EXPIRED) {
                        phase = QrPhase.SHOWING
                    }
                }
            }, onCreated = { webView = it }) },
            update = { view ->
                if ((view.tag as? Int) != reloadKey) {
                    view.tag = reloadKey
                    view.loadUrl(LOGIN_URL)
                }
            }
        )

        // ── 完整页面模式的关闭按钮 ──
        if (fullMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 54.dp, end = 24.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable {
                        fullMode = false
                        phase = QrPhase.LOADING
                        qrBitmap = null
                        reloadKey++
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "回到二维码",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
// 二维码卡片
// ────────────────────────────────────────────────────────────

@Composable
private fun QrCard(
    phase: QrPhase,
    qrBitmap: Bitmap?,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onFullMode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 38.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF15151B))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { }
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "登录汽水音乐",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = "用抖音 App 扫码，手机上点确认",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.40f)
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 二维码区：白底卡片，只放解码后的二维码位图
        Box(
            modifier = Modifier
                .size(232.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            val bmp = qrBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "登录二维码",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None
                )
            }

            when (phase) {
                QrPhase.LOADING -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = Color.Black.copy(alpha = 0.35f),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "正在获取二维码…",
                            fontSize = 11.sp,
                            color = Color.Black.copy(alpha = 0.45f)
                        )
                    }
                }

                QrPhase.EXPIRED -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.90f))
                            .clickable(onClick = onRefresh),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = Color.Black.copy(alpha = 0.55f),
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "二维码已失效",
                                fontSize = 13.sp,
                                color = Color.Black.copy(alpha = 0.75f)
                            )
                            Text(
                                "点一下刷新",
                                fontSize = 11.sp,
                                color = Color.Black.copy(alpha = 0.45f)
                            )
                        }
                    }
                }

                QrPhase.SCANNED -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1DB954).copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "已扫描",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                "请在手机上点「确认登录」",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }

                QrPhase.TIMEOUT -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "没拿到二维码",
                            fontSize = 13.sp,
                            color = Color.Black.copy(alpha = 0.70f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "抖音可能要求先做一次安全验证",
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            color = Color.Black.copy(alpha = 0.40f)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.06f))
                                    .clickable(onClick = onRefresh)
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = Color.Black.copy(alpha = 0.6f),
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "重试",
                                        fontSize = 11.sp,
                                        color = Color.Black.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black)
                                    .clickable(onClick = onFullMode)
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text("手动登录", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }

                else -> Unit
            }

            // 扫码中：底部小提示条，不遮二维码
            if (phase == QrPhase.SHOWING) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "打开抖音 App 扫一扫",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 手动登录入口常驻：不等到超时才出现
        if (phase != QrPhase.SCANNED && phase != QrPhase.TIMEOUT) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable(onClick = onFullMode)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    "扫码打不开？手动登录",
                    fontSize = 11.5.sp,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        Text(
            text = when (phase) {
                QrPhase.SHOWING -> "扫完码后手机上会弹确认，点一下即可"
                QrPhase.SCANNED -> "已扫描，请在手机上确认"
                QrPhase.EXPIRED -> "二维码 1 分钟有效，失效后点一下刷新"
                QrPhase.TIMEOUT -> "也可以点「手动登录」直接操作完整页面"
                QrPhase.LOADING -> "约 5 秒，请稍等"
            },
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.40f)
        )
    }
}

// ────────────────────────────────────────────────────────────
// WebView（离屏）
// ────────────────────────────────────────────────────────────

/** 未登录会直接弹出登录面板，且不带首页视频流，最快出码 */
private const val LOGIN_URL = "https://www.douyin.com/user/self"

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
private fun createLoginWebView(
    ctx: android.content.Context,
    onPhase: (String) -> Unit,
    onQr: (String) -> Unit,
    onCreated: (WebView) -> Unit
): WebView = WebView(ctx).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    setBackgroundColor(AndroidColor.TRANSPARENT)
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = false
        displayZoomControls = false
        cacheMode = WebSettings.LOAD_DEFAULT
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        mediaPlaybackRequiresUserGesture = true
        // 桌面 UA：实测该 UA 下登录面板结构稳定、二维码为 data:image，最容易取到
        userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
    val self = this
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(self, true)
    }
    addJavascriptInterface(
        object {
            @JavascriptInterface
            fun state(value: String) {
                post { onPhase(value) }
            }

            @JavascriptInterface
            fun qr(src: String) {
                post { onQr(src) }
            }
        },
        "WhaleBridge"
    )
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.evaluateJavascript(QR_SCRIPT, null)
        }
    }
    webChromeClient = WebChromeClient()
    tag = 0
    loadUrl(LOGIN_URL)
    onCreated(this)
}

/** data:image/png;base64,... → Bitmap */
private fun decodeQr(src: String): Bitmap? = runCatching {
    val idx = src.indexOf("base64,")
    if (idx < 0) return@runCatching null
    val bytes = Base64.decode(src.substring(idx + 7), Base64.DEFAULT)
    if (bytes.isEmpty()) return@runCatching null
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

/**
 * 注入脚本（只取数据，不做任何 DOM 改版）：
 * 1. 只在登录面板范围内找方形 data:image 二维码（实测 178×178 的 base64 PNG）
 * 2. src 变化才回传一次（换码/刷新）
 * 3. 读面板文案判断「已扫描 / 已失效」
 * 4. 30 秒内没拿到就上报 timeout
 */
private const val QR_SCRIPT = """
(function(){
  if (window.__whaleQr) return;
  window.__whaleQr = true;

  function tell(s){ try{ WhaleBridge.state(s); }catch(e){} }
  function push(s){ try{ WhaleBridge.qr(s); }catch(e){} }
  function squared(r){ return r.width >= 120 && r.width <= 400 && Math.abs(r.width - r.height) <= 12; }

  function roots(){
    var out = [];
    var sels = ['#login-panel-new','[id^="login-full-panel"]','#douyin-login-new-id','.login-panel'];
    for (var i=0;i<sels.length;i++){
      var el = document.querySelector(sels[i]);
      if (el) out.push(el);
    }
    if (!out.length) out.push(document);
    return out;
  }

  function findQr(){
    var rs = roots();
    for (var k=0;k<rs.length;k++){
      var imgs = rs[k].querySelectorAll('img');
      for (var i=0;i<imgs.length;i++){
        var el = imgs[i];
        if (!squared(el.getBoundingClientRect())) continue;
        var src = el.src || '';
        if (src.indexOf('data:image') === 0) return src;
      }
      var cs = rs[k].querySelectorAll('canvas');
      for (var j=0;j<cs.length;j++){
        if (!squared(cs[j].getBoundingClientRect())) continue;
        try { return cs[j].toDataURL('image/png'); } catch(e){}
      }
    }
    return null;
  }

  function panelText(){
    var rs = roots();
    return (rs[0] && rs[0].innerText) || '';
  }

  var lastSrc = '', tries = 0;
  var timer = setInterval(function(){
    tries++;
    var src = findQr();
    if (src){
      if (src !== lastSrc){ lastSrc = src; push(src); tell('qr'); }
      if (/扫描成功|请在手机上(确认|点击确认)|确认登录/.test(panelText())) tell('scanned');
      return;
    }
    if (/二维码(已)?(失效|过期)/.test(panelText())) { tell('expired'); return; }
    if (tries > 75){ clearInterval(timer); tell('timeout'); }
  }, 400);
})();
""";
