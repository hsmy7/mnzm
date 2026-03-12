package com.xianxia.sect

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class XianxiaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
