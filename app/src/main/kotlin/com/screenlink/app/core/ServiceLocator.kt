package com.screenlink.app.core

import android.app.Application
import android.content.Context

object ServiceLocator {
    lateinit var app: Application
        private set
    lateinit var settings: SettingsRepository
        private set

    fun init(application: Application) {
        app = application
        settings = SettingsRepository(application)
    }

    val context: Context get() = app
}
