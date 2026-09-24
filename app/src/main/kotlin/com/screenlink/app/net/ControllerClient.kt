package com.screenlink.app.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * 主控端：连到被控端，收画面，发触摸 / 按键。
 *
 * 会话用 generation 计数隔离：断开重连时，旧会话的收尾代码不会误伤新连接。
 */
class ControllerClient(private val log: (String) -> Unit) {

    data class State(
        val connecting: Boolean = false,
        val connected: Boolean = false,
        val error: String? = null,
        val captureWidth: Int = 0,
        val captureHeight: Int = 0,
        val realWidth: Int = 0,
        val realHeight: Int = 0,
        val fps: Float = 0f,
        val latencyMs: Int = -1,
        val kbps: Int = 0,
        val frames: Long = 0,
        val bytes: Long = 0,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val generation = AtomicInteger(0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private var sessionJob: Job? = null
    private var writerJob: Job? = null
    private var pingJob: Job? = null
    private var socket: Socket? = null
    private var outbox = Channel<Frame>(64)

    private val decodeOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inDither = false
    }

    fun connect(host: String, port: Int, key: String, width: Int, quality: Int, fps: Int) {
        disconnect()
        val normalized = Crypto.normalizeKey(key)
        if (normalized.length < 4) {
            _state.update { it.copy(connecting = false, connected = false, error = "密钥至少要 4 位") }
            return
        }
        outbox = Channel(64)
        val token = generation.incrementAndGet()
        _frame.value = null
        sessionJob = scope.launch {
            runSession(token, host, port, normalized, intArrayOf(width, quality, fps))
        }
    }

    private suspend fun runSession(
        token: Int,
        host: String,
        port: Int,
        key: String,
        quality: IntArray,
    ) {
        try {
            _state.update { it.copy(connecting = true, connected = false, error = null) }
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 8000)
            s.soTimeout = 15000
            socket = s

            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

            val hello = FrameIO.read(input)
            if (hello.type != MsgType.HELLO || hello.payload.size < 18) {
                throw ProtocolException("对端不是 ScreenLink 被控端")
            }
            val version = hello.payload[0].toInt()
            if (version != PROTO_VERSION) {
                throw ProtocolException("协议版本不一致（对端 v$version，本机 v$PROTO_VERSION）")
            }
            val serverNonce = hello.payload.copyOfRange(1, 17)
            val clientNonce = Crypto.randomBytes(16)
            FrameIO.write(
                output,
                MsgType.AUTH,
                Payloads.auth(clientNonce, Crypto.authTag(key, serverNonce, clientNonce)),
            )

            val result = FrameIO.read(input)
            if (result.type != MsgType.AUTH_RESULT) throw ProtocolException("认证响应异常")
            val (ok, reason) = Payloads.parseAuthResult(result.payload)
            if (!ok) throw ProtocolException(reason.ifBlank { "认证失败" })

            val secure = SecureChannel(input, output, Crypto.sessionKey(key, serverNonce, clientNonce))
            _state.update { it.copy(connecting = false, connected = true, error = null) }
            log("已连接到 $host:$port")

            secure.send(MsgType.SET_QUALITY, Payloads.setQuality(quality[0], quality[1], quality[2]))

            writerJob = scope.launch {
                try {
                    for (message in outbox) {
                        secure.send(message.type, message.payload)
                    }
                } catch (t: Throwable) {
                    if (t !is CancellationException) log("发送失败：${t.message}")
                }
            }

            pingJob = scope.launch {
                while (isActive) {
                    delay(3000)
                    sendPing()
                }
            }

            readLoop(secure)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (generation.get() == token) {
                val message = friendly(t)
                _state.update { it.copy(connecting = false, connected = false, error = message) }
                log(message)
            }
        } finally {
            if (generation.get() == token) {
                cleanup()
                _state.update {
                    it.copy(connecting = false, connected = false, fps = 0f, latencyMs = -1)
                }
            }
        }
    }

    private suspend fun readLoop(secure: SecureChannel) = withContext(Dispatchers.IO) {
        var windowStart = SystemClock.elapsedRealtime()
        var framesInWindow = 0
        var bytesInWindow = 0
        var frames = 0L
        var bytes = 0L

        while (isActive) {
            val message = secure.receive()
            when (message.type) {
                MsgType.FRAME -> {
                    val header = Payloads.parseFrameHeader(message.payload) ?: continue
                    val jpeg = Payloads.jpegOf(message.payload)
                    if (jpeg.isEmpty()) continue
                    val bitmap = try {
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decodeOptions)
                    } catch (t: Throwable) {
                        null
                    } ?: continue

                    _frame.value = bitmap
                    frames++
                    bytes += jpeg.size
                    framesInWindow++
                    bytesInWindow += jpeg.size

                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - windowStart
                    if (elapsed >= 1000) {
                        _state.update {
                            it.copy(
                                fps = framesInWindow * 1000f / elapsed,
                                kbps = (bytesInWindow * 1000L / elapsed / 1024L).toInt(),
                                frames = frames,
                                bytes = bytes,
                                captureWidth = header[1].toInt(),
                                captureHeight = header[2].toInt(),
                            )
                        }
                        windowStart = now
                        framesInWindow = 0
                        bytesInWindow = 0
                    }
                }

                MsgType.PONG -> {
                    if (message.payload.size >= 8) {
                        val sentAt = ByteBuffer.wrap(message.payload, 0, 8).long
                        val latency = (SystemClock.elapsedRealtime() - sentAt).toInt()
                        _state.update { it.copy(latencyMs = latency.coerceIn(0, 9999)) }
                    }
                }

                MsgType.CONFIG -> {
                    Payloads.parseConfig(message.payload)?.let { config ->
                        if (config[0] > 0 && config[1] > 0) {
                            _state.update {
                                it.copy(
                                    realWidth = config[0],
                                    realHeight = config[1],
                                    captureWidth = config[2],
                                    captureHeight = config[3],
                                )
                            }
                        }
                    }
                }

                MsgType.LOG -> log(String(message.payload, Charsets.UTF_8))
                MsgType.BYE -> throw EOFException(String(message.payload, Charsets.UTF_8))
            }
        }
    }

    private fun friendly(t: Throwable): String = when (t) {
        is EOFException -> t.message?.takeIf { it.isNotBlank() } ?: "连接被对方关闭"
        is SocketTimeoutException -> "连接超时（对方没响应）"
        is ConnectException -> "连不上：检查 IP、端口，以及两台设备是否在同一网络"
        is UnknownHostException -> "地址解析失败"
        is ProtocolException -> t.message ?: "协议错误"
        is IOException -> "网络错误：${t.message}"
        else -> "出错了：${t.message}"
    }

    fun sendTouch(action: Int, x: Float, y: Float) {
        outbox.trySend(Frame(MsgType.TOUCH, Payloads.touch(action, x, y)))
    }

    fun sendKey(keyCode: Int) {
        outbox.trySend(Frame(MsgType.KEY, Payloads.key(keyCode)))
    }

    private fun sendPing() {
        val payload = ByteBuffer.allocate(8).putLong(SystemClock.elapsedRealtime()).array()
        outbox.trySend(Frame(MsgType.PING, payload))
    }

    fun disconnect() {
        generation.incrementAndGet()
        sessionJob?.cancel()
        sessionJob = null
        cleanup()
        _state.update { it.copy(connecting = false, connected = false, fps = 0f, latencyMs = -1) }
    }

    private fun cleanup() {
        writerJob?.cancel()
        writerJob = null
        pingJob?.cancel()
        pingJob = null
        runCatching { socket?.close() }
        socket = null
        runCatching { outbox.close() }
    }

    fun release() {
        disconnect()
        scope.cancel()
    }
}
