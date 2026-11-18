package com.whale.music.util

import android.app.Application
import android.content.Context
import android.widget.Toast
import java.io.File
import java.io.StringWriter
import java.io.PrintWriter

/**
 * 崩溃防护：
 * 1. 把未捕获异常的堆栈写进日志文件，方便排查“打开就闪退”这类问题；
 * 2. 若崩溃发生在启动后短时间内，标记“启动崩溃”，下次启动临时跳过自动播放，
 *    保证 App 永远能打开，不会陷入“一启动就崩”的死循环。
 */
object CrashGuard {
    private const val PREFS = "whale_crash_guard"
    private const val KEY_STARTUP_CRASH = "startup_crash"
    private const val STARTUP_WINDOW_MS = 25_000L

    /** 本次启动是否要跳过自动播放（由“上次启动崩溃”标记决定） */
    @Volatile
    var skipAutoplayOnce = false
        private set

    fun init(app: Application) {
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_STARTUP_CRASH, false)) {
            sp.edit().remove(KEY_STARTUP_CRASH).apply()
            skipAutoplayOnce = true
            try {
                Toast.makeText(
                    app,
                    "上次启动时出现异常，本次已临时跳过自动播放",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Throwable) {
            }
        }

        val launchAt = android.os.SystemClock.elapsedRealtime()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val elapsed = android.os.SystemClock.elapsedRealtime() - launchAt
                if (elapsed < STARTUP_WINDOW_MS) {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_STARTUP_CRASH, true).apply()
                }
                writeLog(app, thread, throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeLog(app: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val text = buildString {
            append("time: ").append(java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
            ).format(java.util.Date()))
            append("\nthread: ").append(thread.name)
            append("\nversion: ").append(runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName
            }.getOrNull().orEmpty())
            append("\n\n").append(sw.toString())
        }

        // 1) 应用外部私有目录（可直接用文件管理器访问）
        runCatching {
            val dir = app.getExternalFilesDir(null) ?: app.filesDir
            File(dir, "last_crash.txt").writeText(text)
            val history = File(dir, "crash_history.txt")
            history.appendText("\n=====\n$text")
            // 只保留最近 5 次崩溃
            if (history.length() > 400_000) {
                history.writeText(history.readText().takeLast(200_000))
            }
        }

        // 2) 同步一份到系统下载目录，方便直接发给别人
        runCatching {
            val resolver = app.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "whale_crash_log.txt")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return@runCatching
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }
}
