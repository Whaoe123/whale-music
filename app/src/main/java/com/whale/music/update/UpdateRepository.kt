package com.whale.music.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Singleton

/** 服务端 update.json 里的一个版本条目 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val changelog: String
) {
    companion object {
        /** 解析失败一律返回 null —— 更新检查绝不能把 App 弄崩 */
        fun parse(json: String): UpdateInfo? = runCatching {
            val o = JSONObject(json)
            UpdateInfo(
                versionCode = o.optInt("versionCode", 0),
                versionName = o.optString("versionName", ""),
                url = o.optString("url", ""),
                sha256 = o.optString("sha256", ""),
                changelog = o.optString("changelog", "")
            ).takeIf { it.versionCode > 0 && it.url.startsWith("http") }
        }.getOrNull()
    }
}

/**
 * 应用内更新：检查 update.json → 下载 APK → 校验 sha256 → 跳系统安装器。
 *
 * 服务端只要托管两个静态文件（update.json + apk），不需要任何后端程序：
 * - update.json: {"versionCode":9,"versionName":"Whale 1.8","url":"https://…/xxx.apk","sha256":"…","changelog":"…"}
 * - apk: GitHub Release 资产或任意静态托管。
 *
 * 比较只看 versionCode（单调递增），versionName 仅用于展示。
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs by lazy {
        context.getSharedPreferences("whale_update", Context.MODE_PRIVATE)
    }

    /** 当前安装包的版本说明，如 "Whale 1.11（版本号 21）" */
    fun localVersionLabel(): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName}（版本号 ${currentVersionCode()}）"
    }.getOrDefault("未知版本")

    /** 当前安装包的 versionCode */
    fun currentVersionCode(): Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode
        }
    }.getOrDefault(0)

    /**
     * 拉取 update.json：**所有源并行请求，谁先返回有效结果用谁**。
     * 串行逐个试时，单个源挂住（国内到 raw 常见）会把整个检查拖到 1 分钟以上；
     * 并行后最坏耗时 ≈ 最慢一个源的超时（6s 连接 + 8s 读），实测通常 1~2 秒出结果。
     * 全部失败时回退到**上次成功拉到的缓存**，离线也能弹更新窗（下载另说）。
     * 带 cb= 时间戳绕过 CDN 缓存，避免拿到旧版 update.json 误判「已是最新」。
     */
    fun fetchRemote(): UpdateInfo? {
        val stamp = System.currentTimeMillis()
        val sources = UPDATE_SOURCES.map { "$it?cb=$stamp" }
        val bodies = arrayOfNulls<String>(sources.size)
        val latch = CountDownLatch(sources.size)
        sources.forEachIndexed { i, src ->
            Thread {
                try {
                    bodies[i] = fetchRaw(src)
                } catch (_: Throwable) {
                    // 单源失败不影响其他源
                } finally {
                    latch.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
        // 先等第一个成功（最多 15s），全结束了还没成功就收工
        val deadline = System.currentTimeMillis() + 15_000
        var body: String? = null
        while (body == null && System.currentTimeMillis() < deadline) {
            body = bodies.firstOrNull { !it.isNullOrBlank() }
            if (body == null) {
                if (latch.count == 0L) break
                runCatching { Thread.sleep(80L) }
            }
        }
        body = body ?: bodies.firstOrNull { !it.isNullOrBlank() }

        val info = body?.let { UpdateInfo.parse(it) }
        if (info != null) {
            runCatching { prefs.edit().putString(KEY_LAST_REMOTE, body).apply() }
            return info
        }
        // 网络全挂：退回上次成功的版本（hasNewVersion 仍会把关，不会误弹）
        return runCatching {
            prefs.getString(KEY_LAST_REMOTE, null)?.let { UpdateInfo.parse(it) }
        }.getOrNull()
    }

    /** 拉原始正文；超时调短（6s 连 / 8s 读），坏源快速让位，不拖累整个检查 */
    private fun fetchRaw(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "Whale-Updater")
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (conn.responseCode != 200) return null
            conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()?.takeIf { it.contains("versionCode") }

    /** 远端版本比本机新，且用户没对这个版本点过「以后再说」 */
    fun hasNewVersion(info: UpdateInfo): Boolean {
        if (info.versionCode <= currentVersionCode()) return false
        return prefs.getInt(KEY_DISMISSED, 0) < info.versionCode
    }

    /** 「以后再说」：记住这个版本，下次启动不再打扰 */
    fun dismiss(versionCode: Int) {
        prefs.edit().putInt(KEY_DISMISSED, versionCode).apply()
    }

    /**
     * 下载 APK 到应用缓存目录，边下边报进度（含速度），完成后校验 sha256。
     *
     * 慢网络对策：
     * 1. 多个下载源依次尝试（GitHub 直连 → 公共加速前缀），换源时**断点续传**不重下；
     * 2. 支持 Range 续传，网络闪断后从已有字节继续；
     * 3. 128KB 大缓冲 + 速度统计。
     * 无论走哪个源，最后都按 update.json 里的 sha256 校验，被篡改一律拒装。
     *
     * @param onProgress (0..1 进度, 速度 KB/s)
     */
    fun download(info: UpdateInfo, onProgress: (Float, Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // 残段按版本号命名：旧版本没下完的字节不参与本次续传（跨版本 Range 起点对不上会 416），
        // 顺手清掉其他版本留下的残段
        val target = File(dir, "whale-update-${info.versionCode}.apk")
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("whale-update") && f.name != target.name) f.delete()
        }

        val sources = buildList {
            if (info.url.startsWith("https://github.com/")) {
                // 加速前缀排前面（实测本机：gh-proxy 253KB/s、ghfast 183KB/s，直连 227KB/s）；
                // ghproxy.net 已剔除：首字节要 32 秒，纯干等超时
                add("https://gh-proxy.com/" + info.url)
                add("https://ghfast.top/" + info.url)
            }
            add(info.url) // 直连兜底
        }

        var lastError: Exception? = null
        for (src in sources) {
            try {
                return downloadFrom(src, target, info, onProgress)
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w("UpdateRepo", "下载源失败，换下一个: $src → ${e.message}")
                // 文件不完整/校验不过 → 丢掉残段，避免把坏数据带到下一个源
                if (target.exists() && !isResumable(target, info)) target.delete()
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    /** 残段是否还能续（大小不超过预期） */
    private fun isResumable(file: File, info: UpdateInfo): Boolean = file.exists() && file.length() in 1..(1024L * 1024 * 300)

    private fun downloadFrom(url: String, target: File, info: UpdateInfo, onProgress: (Float, Float) -> Unit): File {
        val existing = if (target.exists()) target.length() else 0L

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000   // 单次读最多等 15s：一个字节都不吐的死源尽快让位换下一个
        conn.setRequestProperty("User-Agent", "Whale-Updater")
        conn.instanceFollowRedirects = true
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")

        val code = conn.responseCode
        // 416 = Range 起点超出服务端文件长度：残段要么其实已经下完，要么和这次的文件对不上。
        // 不能当致命错误直接放弃 —— 先验残段 sha256，完整就直接用；不完整就删掉重新下（不再带 Range）
        if (code == 416 && existing > 0) {
            conn.disconnect()
            if (info.sha256.isNotBlank() && verify(target, info.sha256)) {
                onProgress(1f, 0f)
                return target
            }
            target.delete()
            return downloadFrom(url, target, info, onProgress) // existing=0 → 不再发 Range，不会再次 416
        }
        val resumed = code == 206 && existing > 0
        if (code !in 200..299) {
            conn.disconnect()
            error("HTTP $code")
        }

        val contentLen = conn.contentLength.toLong()
        val total = (if (resumed) existing + contentLen else contentLen).coerceAtLeast(0L)

        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        try {
            // 续传前先把已有字节喂进摘要
            if (resumed && existing > 0) {
                FileInputStream(target).use { input ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
                done = existing
            }

            var windowStart = System.nanoTime()
            var windowBytes = 0L
            val baseDone = done          // 续传进来的字节不算"这一段的速度"
            var loopStart = 0L           // 收到第一个字节才起算（握手时间不计入速度，避免误判慢源）

            conn.inputStream.use { input ->
                FileOutputStream(target, resumed).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        windowBytes += n

                        val now = System.nanoTime()
                        if (loopStart == 0L) loopStart = now
                        // 慢就换源：4 秒后平均速度仍低于 80KB/s，抛出去让上层切下一个源（已下字节保留续传）。
                        // 阈值取 80 是按实测定的（可用源普遍 180~250KB/s），避免把正常爬坡的源误踢
                        val secs = (now - loopStart) / 1_000_000_000.0
                        if (secs > 4.0 && (done - baseDone) / secs < 80 * 1024) {
                            error("速度过慢（${((done - baseDone) / secs / 1024).toInt()} KB/s），换源")
                        }

                        val elapsed = now - windowStart
                        var speedKbps = 0f
                        if (elapsed >= 500_000_000L) {          // 每 0.5s 算一次速度
                            speedKbps = windowBytes * 1000f / (elapsed / 1_000_000f) / 1024f
                            windowStart = now
                            windowBytes = 0L
                        }
                        // 总大小未知（个别代理不给 Content-Length）时也照报速度，让界面立刻有动静
                        val ratio = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                        onProgress(ratio, speedKbps)
                    }
                }
            }
            onProgress(1f, 0f)

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = info.sha256.trim().lowercase()
            if (expected.isNotEmpty() && actual != expected) {
                target.delete()
                error("校验失败")
            }
            if (target.length() < 1024L) {
                target.delete()
                error("文件不完整")
            }
            return target
        } finally {
            conn.disconnect()
        }
    }

    /** 交给系统安装器（Android 7+ 必须走 FileProvider） */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Android 8+ 需要用户单独允许「安装未知应用」 */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 校验已下载文件的 sha256（安装前再查一次，防止缓存被替换） */
    fun verify(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == expected.trim().lowercase()
        }.getOrDefault(false)
    }

    companion object {
        /**
         * 版本信息源（按顺序尝试）：仓库根目录的 update.json。
         * raw.githubusercontent 在国内偶尔慢/不通，jsDelivr 作备用。
         * 改这里可以换成你自己的 OSS / Gitee / Cloudflare 地址。
         */
        val UPDATE_SOURCES = listOf(
            "https://raw.githubusercontent.com/Whaoe123/whale-music/main/update.json",
            "https://cdn.jsdelivr.net/gh/Whaoe123/whale-music@main/update.json",
            "https://fastly.jsdelivr.net/gh/Whaoe123/whale-music@main/update.json",
            // ghfast 中转 raw：raw 直连被墙/超时时的第 4 路（并行请求，不增加等待）
            "https://ghfast.top/https://raw.githubusercontent.com/Whaoe123/whale-music/main/update.json"
        )

        /** 兼容旧引用 */
        const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/Whaoe123/whale-music/main/update.json"

        private const val KEY_DISMISSED = "dismissed_version_code"

        /** 上次成功拉到的 update.json 原文（网络全挂时兜底用） */
        private const val KEY_LAST_REMOTE = "last_remote_update_json"
    }
}
