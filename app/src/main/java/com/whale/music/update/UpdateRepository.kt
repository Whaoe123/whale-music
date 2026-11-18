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
     * 拉取 update.json；按顺序试多个源，每个源试两次。
     * 网络失败 / 解析失败返回 null（静默跳过本次检查）。
     */
    fun fetchRemote(): UpdateInfo? {
        for (source in UPDATE_SOURCES) {
            repeat(2) { attempt ->
                val info = fetchOnce(source)
                if (info != null) return info
                if (attempt == 0) runCatching { Thread.sleep(500L) }
            }
        }
        return null
    }

    private fun fetchOnce(url: String): UpdateInfo? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000   // 国内到 raw.githubusercontent 首次握手可能很慢
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "Whale-Updater")
        try {
            if (conn.responseCode != 200) return null
            conn.inputStream.use { input ->
                UpdateInfo.parse(input.readBytes().decodeToString())
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

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
        val target = File(dir, "whale-update.apk")

        val sources = buildList {
            add(info.url)
            if (info.url.startsWith("https://github.com/")) {
                // 公共加速前缀（国内到 GitHub 直连常见几十 KB/s）
                add("https://ghfast.top/" + info.url)
                add("https://ghproxy.net/" + info.url)
                add("https://gh-proxy.com/" + info.url)
            }
        }

        var lastError: Exception? = null
        for (src in sources) {
            try {
                return downloadFrom(src, target, onProgress)
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

    private fun downloadFrom(url: String, target: File, onProgress: (Float, Float) -> Unit): File {
        val existing = if (target.exists()) target.length() else 0L

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Whale-Updater")
        conn.instanceFollowRedirects = true
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")

        val code = conn.responseCode
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
            val loopStart = System.nanoTime()

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
                        // 慢就换源：3 秒后平均速度仍低于 120KB/s，抛出去让上层切下一个源（已下字节保留续传）
                        val secs = (now - loopStart) / 1_000_000_000.0
                        if (secs > 3.0 && (done - baseDone) / secs < 120 * 1024) {
                            error("速度过慢（${((done - baseDone) / secs / 1024).toInt()} KB/s），换源")
                        }

                        val elapsed = now - windowStart
                        var speedKbps = 0f
                        if (elapsed >= 500_000_000L) {          // 每 0.5s 算一次速度
                            speedKbps = windowBytes * 1000f / (elapsed / 1_000_000f) / 1024f
                            windowStart = now
                            windowBytes = 0L
                        }
                        if (total > 0) {
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f), speedKbps)
                        }
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
            "https://fastly.jsdelivr.net/gh/Whaoe123/whale-music@main/update.json"
        )

        /** 兼容旧引用 */
        const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/Whaoe123/whale-music/main/update.json"

        private const val KEY_DISMISSED = "dismissed_version_code"
    }
}
