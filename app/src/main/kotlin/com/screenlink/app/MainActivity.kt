package com.screenlink.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.screenlink.app.core.AppSettings
import com.screenlink.app.core.ServiceLocator
import com.screenlink.app.ui.nav.AppNav
import com.screenlink.app.ui.theme.ScreenLinkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by ServiceLocator.settings.settings
                .collectAsState(initial = AppSettings())
            ScreenLinkTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                AppNav()
            }
        }
    }
}
