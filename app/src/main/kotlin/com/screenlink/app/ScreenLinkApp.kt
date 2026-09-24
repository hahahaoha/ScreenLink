package com.screenlink.app

import android.app.Application
import com.screenlink.app.core.ServiceLocator
import com.screenlink.app.input.ShizukuBridge

class ScreenLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        ShizukuBridge.init(this)
    }
}
