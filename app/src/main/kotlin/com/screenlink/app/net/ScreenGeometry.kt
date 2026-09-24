package com.screenlink.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 被控端屏幕的几何信息，注入坐标要靠它把归一化坐标换算成真实像素。
 * 由采集端在建立/调整虚拟屏时写入。
 */
object ScreenGeometryProvider {
    @Volatile var realWidth: Int = 0
    @Volatile var realHeight: Int = 0
    @Volatile var captureWidth: Int = 0
    @Volatile var captureHeight: Int = 0

    fun update(realW: Int, realH: Int, capW: Int, capH: Int) {
        realWidth = realW
        realHeight = realH
        captureWidth = capW
        captureHeight = capH
    }

    val isValid: Boolean get() = realWidth > 0 && realHeight > 0
}
