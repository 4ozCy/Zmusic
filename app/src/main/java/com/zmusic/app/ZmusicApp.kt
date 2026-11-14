package com.zmusic.app

import android.app.Application
import com.zmusic.app.debug.BugLog

class ZmusicApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        BugLog.install(this)
        container = AppContainer(this)
    }
}
