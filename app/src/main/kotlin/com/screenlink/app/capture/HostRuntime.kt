package com.screenlink.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 被控端的画质参数，采集线程每帧都会读一次，随时可改 */
object LiveQuality {
    @Volatile var captureWidth: Int = 720
    @Volatile var jpegQuality: Int = 45
    @Volatile var maxFps: Int = 15

    fun apply(width: Int, quality: Int, fps: Int) {
        captureWidth = width.coerceIn(240, 2160)
        jpegQuality = quality.coerceIn(20, 95)
        maxFps = fps.coerceIn(1, 60)
    }
}

data class HostUiState(
    val running: Boolean = false,
    val listening: Boolean = false,
    val port: Int = 27100,
    val client: String? = null,
    val realWidth: Int = 0,
    val realHeight: Int = 0,
    val captureWidth: Int = 0,
    val captureHeight: Int = 0,
    val fps: Float = 0f,
    val frames: Long = 0,
    val injectMode: String = "未初始化",
    val error: String? = null,
)

/** 被控端服务的共享状态，UI 直接订阅它，不用 bindService */
object HostRuntime {

    val state = MutableStateFlow(HostUiState())
    val logs = MutableStateFlow<List<String>>(emptyList())

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private const val MAX_LOGS = 150

    fun log(text: String) {
        val line = "${timeFormat.format(Date())}  $text"
        logs.value = (listOf(line) + logs.value).take(MAX_LOGS)
    }

    fun clearLogs() {
        logs.value = emptyList()
    }
}
