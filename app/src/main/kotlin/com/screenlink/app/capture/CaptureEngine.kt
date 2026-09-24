package com.screenlink.app.capture

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * MediaProjection -> VirtualDisplay（按画质缩放）-> ImageReader -> JPEG。
 *
 * 关键点：虚拟屏直接建成缩放后的尺寸，缩放交给系统合成器，省 CPU。
 * 屏幕旋转、画质调整都会触发重建虚拟屏，并把新的尺寸同步给主控端。
 */
class CaptureEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val hasClient: () -> Boolean,
    private val onFrame: (jpeg: ByteArray, width: Int, height: Int) -> Unit,
    private val onGeometry: (realWidth: Int, realHeight: Int, captureWidth: Int, captureHeight: Int) -> Unit,
    private val onFps: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val encoder = JpegEncoder()

    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile private var pendingResize = true
    @Volatile private var released = false
    @Volatile private var appliedWidthLimit = -1

    private var loopJob: Job? = null
    private var callback: MediaProjection.Callback? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) pendingResize = true
        }
    }

    private val densityDpi: Int
        get() = context.resources.displayMetrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_LOW)

    fun start(scope: CoroutineScope) {
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (released) return
                onError("系统已停止屏幕采集")
            }
        }
        callback = projectionCallback
        runCatching { projection.registerCallback(projectionCallback, handler) }
        runCatching { displayManager.registerDisplayListener(displayListener, handler) }
        loopJob = scope.launch { loop() }
    }

    private suspend fun loop() {
        var lastFrameAt = 0L
        var fpsWindowStart = SystemClock.elapsedRealtime()
        var fpsCounter = 0

        while (!released) {
            if (appliedWidthLimit != LiveQuality.captureWidth) pendingResize = true
            if (pendingResize) rebuild()

            val currentReader = reader
            if (currentReader == null) {
                delay(50)
                continue
            }

            val image = try {
                currentReader.acquireLatestImage()
            } catch (t: Throwable) {
                null
            }
            if (image == null) {
                delay(3)
                continue
            }

            try {
                if (!hasClient()) {
                    // 没客户端就不编码，但要把缓冲抽干，否则生产端会卡住
                    delay(120)
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                val interval = (1000f / LiveQuality.maxFps.coerceIn(1, 60)).toLong()
                if (now - lastFrameAt < interval) continue
                lastFrameAt = now

                val jpeg = encoder.encode(image, LiveQuality.jpegQuality) ?: continue
                onFrame(jpeg, image.width, image.height)

                fpsCounter++
                val elapsed = now - fpsWindowStart
                if (elapsed >= 1000) {
                    onFps(fpsCounter * 1000f / elapsed)
                    fpsWindowStart = now
                    fpsCounter = 0
                }
            } finally {
                runCatching { image.close() }
            }
        }
    }

    private fun rebuild() {
        pendingResize = false
        appliedWidthLimit = LiveQuality.captureWidth
        val (realWidth, realHeight) = realScreenSize()
        val (captureWidth, captureHeight) = scaledSize(realWidth, realHeight)
        if (captureWidth <= 0 || captureHeight <= 0) return

        val existingDisplay = virtualDisplay
        val existingReader = reader
        if (existingDisplay != null && existingReader != null) {
            val newReader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                3,
            )
            val resized = runCatching {
                existingDisplay.resize(captureWidth, captureHeight, densityDpi)
                existingDisplay.surface = newReader.surface
            }.isSuccess
            if (resized) {
                reader = newReader
                runCatching { existingReader.close() }
                onGeometry(realWidth, realHeight, captureWidth, captureHeight)
                return
            }
            runCatching { newReader.close() }
        }
        createVirtualDisplay(captureWidth, captureHeight, realWidth, realHeight)
    }

    private fun createVirtualDisplay(
        captureWidth: Int,
        captureHeight: Int,
        realWidth: Int,
        realHeight: Int,
    ) {
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        virtualDisplay = null
        reader = null

        val newReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3)
        val display = runCatching {
            projection.createVirtualDisplay(
                "ScreenLink",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                null,
                handler,
            )
        }.getOrNull()

        if (display == null) {
            runCatching { newReader.close() }
            onError("创建虚拟屏失败，请重新授权屏幕采集")
            return
        }
        reader = newReader
        virtualDisplay = display
        onGeometry(realWidth, realHeight, captureWidth, captureHeight)
    }

    @Suppress("DEPRECATION")
    private fun realScreenSize(): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        return if (width > 0 && height > 0) width to height else 1080 to 1920
    }

    private fun scaledSize(realWidth: Int, realHeight: Int): Pair<Int, Int> {
        val limit = LiveQuality.captureWidth
        if (limit <= 0 || realWidth <= limit) return realWidth to realHeight
        val ratio = limit.toFloat() / realWidth
        val scaled = (realHeight * ratio).roundToInt()
        return limit.coerceAtLeast(2) to scaled.coerceAtLeast(2)
    }

    fun release() {
        released = true
        loopJob?.cancel()
        loopJob = null
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        callback?.let { runCatching { projection.unregisterCallback(it) } }
        callback = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
        encoder.release()
        runCatching { projection.stop() }
    }
}
