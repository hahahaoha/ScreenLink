package com.screenlink.app.core

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 画质档位：宽度 = 采集后画面的宽度上限（等比缩放） */
object QualityPreset {
    val widths = listOf(360, 480, 720, 1080)
    val qualities = listOf(30, 45, 60, 80)
    val fpsList = listOf(5, 10, 15, 24, 30)
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** 被控端监听端口 */
    val port: Int = 27100,
    /** 连接密钥，被控端每次连接都会校验 */
    val accessKey: String = "",
    /** 采集宽度上限 */
    val captureWidth: Int = 720,
    /** JPEG 质量 */
    val jpegQuality: Int = 45,
    /** 最高帧率 */
    val maxFps: Int = 15,
    /** 主控端上次连接过的地址 */
    val lastHost: String = "",
    val lastPort: Int = 27100,
    /** 注入方式：AUTO / BINDER / SHELL */
    val injectMode: String = "AUTO",
    /** 被控时保持屏幕常亮 */
    val keepAwake: Boolean = true,
)
