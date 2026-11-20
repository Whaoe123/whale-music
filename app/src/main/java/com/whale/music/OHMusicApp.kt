package com.whale.music

import android.app.Application
import com.whale.music.data.remote.LyricSource
import com.whale.music.util.CoverResolver
import com.whale.music.util.CrashGuard
import com.whale.music.ui.lyrics.LyricPrefs
import com.whale.music.util.DisplayPrefs
import com.whale.music.util.GlassPrefs
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OHMusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.init(this)
        CoverResolver.init(this)
        // 歌词磁盘哨兵缓存（防重复打网易接口）
        LyricSource.attachCacheDir(cacheDir)
        // 展示偏好（Hi·Res 金标开关 / 大小）
        DisplayPrefs.init(this)
        // 液态玻璃全局配方（折射 / 厚度 / 色散 / 模糊 / 高光）
        GlassPrefs.init(this)
        LyricPrefs.init(this)
    }
}
