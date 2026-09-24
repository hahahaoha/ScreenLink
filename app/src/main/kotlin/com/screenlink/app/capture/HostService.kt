package com.screenlink.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.screenlink.app.MainActivity
import com.screenlink.app.R
import com.screenlink.app.input.InputInjector
import com.screenlink.app.net.HostServer
import com.screenlink.app.net.ScreenGeometryProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 被控端的心脏：一个 mediaProjection 类型的前台服务，
 * 同时持有 TCP 服务端和屏幕采集，UI 只是它的遥控器。
 */
class HostService : Service() {

    companion object {
        const val ACTION_START = "com.screenlink.action.START_HOST"
        const val ACTION_STOP = "com.screenlink.action.STOP_HOST"
        const val ACTION_APPLY = "com.screenlink.action.APPLY_HOST"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PORT = "port"
        const val EXTRA_KEY = "key"

        private const val CHANNEL_ID = "screenlink_host"
        private const val NOTIFICATION_ID = 2001

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            port: Int,
            key: String,
        ) {
            val intent = Intent(context, HostService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_KEY, key)
            }
            context.startForegroundService(intent)
        }

        /** 改了端口或密钥，让服务把监听重新拉起来 */
        fun apply(context: Context, port: Int, key: String) {
            val intent = Intent(context, HostService::class.java).apply {
                action = ACTION_APPLY
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_KEY, key)
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, HostService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var server: HostServer
    private var engine: CaptureEngine? = null
    private var projection: MediaProjection? = null

    @Volatile
    private var started = false

    @Volatile
    private var currentKey: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        server = HostServer { message -> HostRuntime.log(message) }
        server.touchHandler = { action, x, y -> InputInjector.handleTouch(action, x, y) }
        server.keyHandler = { keyCode -> InputInjector.handleKey(keyCode) }
        server.qualityHandler = { width, quality, fps ->
            LiveQuality.apply(width, quality, fps)
            HostRuntime.log("画质已切换：${LiveQuality.captureWidth}px / q${LiveQuality.jpegQuality} / ${LiveQuality.maxFps}fps")
        }
        observeServer()
    }

    private fun observeServer() {
        scope.launch {
            var previousClient: String? = null
            server.state.collect { state ->
                val client = state.client
                if (client != null && previousClient == null) {
                    val mode = InputInjector.prepare()
                    HostRuntime.log("注入模式：$mode")
                    HostRuntime.state.update { it.copy(injectMode = mode) }
                }
                if (client == null && previousClient != null) {
                    InputInjector.reset()
                }
                previousClient = client
                HostRuntime.state.update {
                    it.copy(
                        listening = state.running,
                        port = if (state.port > 0) state.port else it.port,
                        client = client,
                        frames = state.framesSent,
                        error = state.error ?: it.error,
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                HostRuntime.log("已停止共享")
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_APPLY -> {
                val port = intent.getIntExtra(EXTRA_PORT, 27100)
                val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
                currentKey = key
                server.setKey(key)
                if (started && server.state.value.port != port) {
                    server.stop()
                    server.start(port, key)
                    HostRuntime.log("改为监听端口 $port")
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 27100)
                val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

                if (!started) {
                    // Android 14+ 要求先起前台服务，再去拿 MediaProjection
                    if (!startAsForeground()) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    if (data == null || resultCode == 0) {
                        HostRuntime.log("没有拿到屏幕采集授权")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    val mediaProjection = runCatching {
                        manager.getMediaProjection(resultCode, data)
                    }.getOrNull()
                    if (mediaProjection == null) {
                        HostRuntime.log("屏幕采集被拒绝或不支持")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    projection = mediaProjection
                    started = true
                    HostRuntime.state.update { it.copy(running = true, error = null) }
                    startCapture(mediaProjection)
                }

                currentKey = key
                server.setKey(key)
                if (!server.state.value.running || server.state.value.port != port) {
                    server.stop()
                    server.start(port, key)
                }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(mediaProjection: MediaProjection) {
        var lastSentWidth = 0
        var lastSentHeight = 0
        val captureEngine = CaptureEngine(
            context = this,
            projection = mediaProjection,
            hasClient = { server.hasClient },
            onFrame = { jpeg, width, height ->
                // 尺寸变了先打招呼，主控端才知道怎么换算坐标
                if (width != lastSentWidth || height != lastSentHeight) {
                    lastSentWidth = width
                    lastSentHeight = height
                }
                server.pushFrame(jpeg, width, height)
            },
            onGeometry = { realWidth, realHeight, captureWidth, captureHeight ->
                ScreenGeometryProvider.update(realWidth, realHeight, captureWidth, captureHeight)
                HostRuntime.state.update {
                    it.copy(
                        realWidth = realWidth,
                        realHeight = realHeight,
                        captureWidth = captureWidth,
                        captureHeight = captureHeight,
                    )
                }
                server.sendConfig(realWidth, realHeight, captureWidth, captureHeight)
            },
            onFps = { fps -> HostRuntime.state.update { it.copy(fps = fps) } },
            onError = { message ->
                HostRuntime.log(message)
                HostRuntime.state.update { it.copy(error = message) }
                teardown()
                stopSelf()
            },
        )
        engine = captureEngine
        captureEngine.start(scope)
        HostRuntime.log("屏幕采集已启动")
    }

    private fun startAsForeground(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        }.getOrElse {
            HostRuntime.log("前台服务启动失败：${it.message}")
            false
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_host_title))
            .setContentText(getString(R.string.notif_host_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_notification, getString(R.string.action_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_host_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_host_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun teardown() {
        runCatching { engine?.release() }
        engine = null
        projection = null
        runCatching { server.stop() }
        started = false
        InputInjector.reset()
        HostRuntime.state.update { HostUiState(error = it.error) }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() {
        teardown()
        runCatching { server.shutdown() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
