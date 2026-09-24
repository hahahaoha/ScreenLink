package com.screenlink.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

data class HostServerState(
    val running: Boolean = false,
    val port: Int = 0,
    val client: String? = null,
    val framesSent: Long = 0,
    val error: String? = null,
)

/**
 * 被控端：监听 TCP 端口，等主控端连进来。
 * 一次只服务一个客户端，密钥错了直接拒绝。
 */
class HostServer(private val log: (String) -> Unit) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _state = MutableStateFlow(HostServerState())
    val state: StateFlow<HostServerState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var accessKey: String = ""

    private val frameCounter = AtomicLong(0)

    /** 收到主控端触摸事件（归一化坐标） */
    var touchHandler: ((action: Int, x: Float, y: Float) -> Unit)? = null

    /** 收到主控端按键 */
    var keyHandler: ((keyCode: Int) -> Unit)? = null

    /** 主控端要求调整画质 */
    var qualityHandler: ((width: Int, quality: Int, fps: Int) -> Unit)? = null

    private class Session(
        val socket: Socket,
        val channel: SecureChannel,
        val address: String,
    )

    val hasClient: Boolean get() = session != null

    fun start(port: Int, key: String) {
        if (_state.value.running) return
        accessKey = key
        acceptJob = scope.launch {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
                serverSocket = socket
                _state.update { it.copy(running = true, port = port, error = null) }
                log("已监听 0.0.0.0:$port，等待主控端连接")
                while (isActive) {
                    val client = try {
                        socket.accept()
                    } catch (t: Throwable) {
                        if (isActive) log("监听中断：${t.message}")
                        break
                    }
                    launch { handleClient(client) }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(running = false, error = t.message ?: "端口占用或权限不足") }
                log("启动监听失败：${t.message}")
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        session?.let { closeSession(it, "被控端已停止") }
        _state.update { HostServerState() }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    fun pushFrame(jpeg: ByteArray, width: Int, height: Int) {
        val current = session ?: return
        try {
            current.channel.send(
                MsgType.FRAME,
                Payloads.frame(System.currentTimeMillis(), width, height, jpeg),
            )
            val total = frameCounter.incrementAndGet()
            if (total % 15L == 0L) {
                _state.update { it.copy(framesSent = total) }
            }
        } catch (t: Throwable) {
            closeSession(current, "发送画面失败：${t.message}")
        }
    }

    fun sendConfig(realWidth: Int, realHeight: Int, captureWidth: Int, captureHeight: Int) {
        val current = session ?: return
        runCatching {
            current.channel.send(
                MsgType.CONFIG,
                Payloads.config(realWidth, realHeight, captureWidth, captureHeight),
            )
        }
    }

    fun sendLog(text: String) {
        val current = session ?: return
        runCatching { current.channel.send(MsgType.LOG, Payloads.text(text)) }
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        val address = client.inetAddress?.hostAddress ?: "未知地址"
        try {
            client.tcpNoDelay = true
            client.soTimeout = 20000
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))

            val serverNonce = Crypto.randomBytes(16)
            FrameIO.write(output, MsgType.HELLO, Payloads.hello(serverNonce, accessKey.isNotBlank()))

            val authFrame = FrameIO.read(input)
            if (authFrame.type != MsgType.AUTH) throw ProtocolException("握手顺序不对")
            if (authFrame.payload.size < 48) throw ProtocolException("认证数据长度不对")
            val clientNonce = authFrame.payload.copyOfRange(0, 16)
            val tag = authFrame.payload.copyOfRange(16, 48)

            if (session != null) {
                FrameIO.write(output, MsgType.AUTH_RESULT, Payloads.authResult(false, "被控端已有客户端在连接"))
                log("拒绝 $address：已有连接")
                return@withContext
            }

            if (accessKey.isBlank()) {
                FrameIO.write(output, MsgType.AUTH_RESULT, Payloads.authResult(false, "被控端还没设置密钥"))
                log("拒绝 $address：未设置密钥")
                return@withContext
            }

            val expected = Crypto.authTag(accessKey, serverNonce, clientNonce)
            if (!Crypto.constantTimeEquals(expected, tag)) {
                delay(800) // 拖慢暴力尝试
                FrameIO.write(output, MsgType.AUTH_RESULT, Payloads.authResult(false, "密钥错误"))
                log("拒绝 $address：密钥错误")
                return@withContext
            }

            FrameIO.write(output, MsgType.AUTH_RESULT, Payloads.authResult(true, "OK"))
            val secure = SecureChannel(input, output, Crypto.sessionKey(accessKey, serverNonce, clientNonce))
            val newSession = Session(client, secure, address)
            session = newSession
            _state.update { it.copy(client = address) }
            log("主控端已接入：$address")

            // 立刻把屏幕尺寸告诉对方，免得等第一帧
            sendConfig(
                ScreenGeometryProvider.realWidth,
                ScreenGeometryProvider.realHeight,
                ScreenGeometryProvider.captureWidth,
                ScreenGeometryProvider.captureHeight,
            )

            while (isActive) {
                val frame = secure.receive()
                when (frame.type) {
                    MsgType.TOUCH -> {
                        Payloads.parseTouch(frame.payload)?.let { (action, x, y) ->
                            touchHandler?.invoke(action, x, y)
                        }
                    }

                    MsgType.KEY -> Payloads.parseKey(frame.payload)?.let { keyHandler?.invoke(it) }

                    MsgType.PING -> secure.send(MsgType.PONG, frame.payload)

                    MsgType.SET_QUALITY -> {
                        Payloads.parseSetQuality(frame.payload)?.let { (width, quality, fps) ->
                            log("主控端调整画质：宽 $width / 质量 $quality / $fps fps")
                            qualityHandler?.invoke(width, quality, fps)
                        }
                    }

                    MsgType.BYE -> {
                        log("主控端主动断开")
                        return@withContext
                    }
                }
            }
        } catch (t: Throwable) {
            if (t !is java.io.EOFException && t !is java.net.SocketTimeoutException) {
                log("连接异常：${t.message}")
            }
        } finally {
            session?.takeIf { it.socket === client }?.let { closeSession(it, "客户端已断开") }
        }
    }

    private fun closeSession(target: Session, reason: String) {
        if (session === target) session = null
        runCatching { target.channel.send(MsgType.BYE, Payloads.text(reason)) }
        runCatching { target.socket.close() }
        _state.update { it.copy(client = null) }
        log("$reason（$frameCounter 帧已发送）")
    }

    fun setKey(key: String) {
        accessKey = key
    }
}
