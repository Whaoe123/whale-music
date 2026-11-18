package com.whale.music

import android.app.Application
import com.whale.music.util.CoverResolver
import com.whale.music.util.CrashGuard
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OHMusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.init(this)
        CoverResolver.init(this)
    }
}
