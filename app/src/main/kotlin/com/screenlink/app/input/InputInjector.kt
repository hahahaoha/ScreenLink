package com.screenlink.app.input

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import com.screenlink.app.core.ServiceLocator
import com.screenlink.app.net.ScreenGeometryProvider
import com.screenlink.app.net.TouchAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 远程输入的落地实现。
 *
 * 首选：通过 Shizuku 拿到的 IInputManager binder 直接 injectInputEvent，
 * 和真实触摸事件一样，能做到实时跟手拖动。
 *
 * 退路：用 Shizuku 以 shell 身份跑 `input tap / input swipe / input keyevent`，
 * 兼容性最好（部分 ROM 会拦隐藏 API），代价是只能"抬手才生效"。
 */
object InputInjector {

    private const val INJECT_MODE_ASYNC = 0
    private const val MOVE_THRESHOLD_PX = 12f
    private const val GESTURE_TIMEOUT_MS = 6000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screenlink-inject")
    }
    private val scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())

    private val _mode = MutableStateFlow("未初始化")
    val mode: StateFlow<String> = _mode.asStateFlow()

    private var inputManager: Any? = null
    private var injectInputEvent: Method? = null
    private var usingBinder = false

    private var gestureActive = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var watchdog: Job? = null

    /** 建会话时调一次，决定用哪条路径 */
    fun prepare(): String {
        if (!usingBinder) setupBinder()
        val label = if (usingBinder) "Shizuku 实时注入" else shellLabel()
        _mode.value = label
        return label
    }

    private fun shellLabel(): String =
        if (ShizukuBridge.hasPermission) "Shell 命令注入（兼容模式）" else "无注入能力（缺 Shizuku 授权）"

    private fun setupBinder(): Boolean {
        val binder = ShizukuBridge.systemService("input")
        if (binder == null) {
            usingBinder = false
            return false
        }
        return try {
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val service = stub
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)
            val method = Class.forName("android.hardware.input.IInputManager")
                .getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                )
            inputManager = service
            injectInputEvent = method
            usingBinder = service != null
            usingBinder
        } catch (t: Throwable) {
            inputManager = null
            injectInputEvent = null
            usingBinder = false
            false
        }
    }

    fun handleTouch(action: Int, nx: Float, ny: Float) {
        scope.launch { dispatchTouch(action, nx, ny) }
    }

    fun handleKey(keyCode: Int) {
        scope.launch {
            if (injectKeyViaBinder(keyCode)) return@launch
            shell("input", "keyevent", keyCode.toString())
        }
    }

    fun reset() {
        gestureActive = false
        watchdog?.cancel()
        watchdog = null
    }

    private fun dispatchTouch(action: Int, nx: Float, ny: Float) {
        val (screenW, screenH) = screenSize()
        if (screenW <= 0 || screenH <= 0) return
        val x = nx.coerceIn(0f, 1f) * screenW
        val y = ny.coerceIn(0f, 1f) * screenH

        when (action) {
            TouchAction.DOWN -> {
                gestureActive = true
                downTime = SystemClock.uptimeMillis()
                downX = x
                downY = y
                lastX = x
                lastY = y
                moved = false
                if (usingBinder) {
                    injectTouch(MotionEvent.ACTION_DOWN, x, y)
                } else {
                    shellLabel().let { } // 命令模式按下不做动作，等抬手判断是点还是滑
                }
                armWatchdog(nx, ny)
            }

            TouchAction.MOVE -> {
                if (!gestureActive) return
                if (abs(x - downX) > MOVE_THRESHOLD_PX || abs(y - downY) > MOVE_THRESHOLD_PX) {
                    moved = true
                }
                lastX = x
                lastY = y
                if (usingBinder) injectTouch(MotionEvent.ACTION_MOVE, x, y)
                armWatchdog(nx, ny)
            }

            TouchAction.UP, TouchAction.CANCEL -> {
                if (!gestureActive) return
                gestureActive = false
                watchdog?.cancel()
                watchdog = null
                val duration = (SystemClock.uptimeMillis() - downTime).toInt().coerceIn(20, 2000)
                if (usingBinder) {
                    injectTouch(
                        if (action == TouchAction.CANCEL) MotionEvent.ACTION_CANCEL
                        else MotionEvent.ACTION_UP,
                        x,
                        y,
                    )
                } else if (moved) {
                    shell(
                        "input", "swipe",
                        downX.roundToInt().toString(),
                        downY.roundToInt().toString(),
                        lastX.roundToInt().toString(),
                        lastY.roundToInt().toString(),
                        duration.toString(),
                    )
                } else {
                    shell(
                        "input", "tap",
                        x.roundToInt().toString(),
                        y.roundToInt().toString(),
                    )
                }
            }
        }
    }

    /** 防止主控端网络抖动导致"手指一直按着" */
    private fun armWatchdog(nx: Float, ny: Float) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(GESTURE_TIMEOUT_MS)
            if (gestureActive) {
                gestureActive = false
                if (usingBinder) {
                    injectTouch(MotionEvent.ACTION_CANCEL, lastX, lastY)
                }
            }
        }
    }

    private fun injectTouch(action: Int, x: Float, y: Float): Boolean {
        val manager = inputManager ?: return false
        val method = injectInputEvent ?: return false
        return try {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            method.invoke(manager, event, INJECT_MODE_ASYNC)
            event.recycle()
            true
        } catch (t: Throwable) {
            // 隐藏 API 被拦了，退化到命令模式
            usingBinder = false
            inputManager = null
            injectInputEvent = null
            _mode.value = shellLabel()
            false
        }
    }

    private fun injectKeyViaBinder(keyCode: Int): Boolean {
        if (!usingBinder) return false
        val manager = inputManager ?: return false
        val method = injectInputEvent ?: return false
        return try {
            val now = SystemClock.uptimeMillis()
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(now, now, action, keyCode, 0)
                event.source = InputDevice.SOURCE_KEYBOARD
                method.invoke(manager, event, INJECT_MODE_ASYNC)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun shell(vararg parts: String) {
        val process = ShizukuBridge.newProcess(arrayOf(*parts)) ?: return
        runCatching { process.waitFor() }
    }

    private fun screenSize(): Pair<Int, Int> {
        if (ScreenGeometryProvider.isValid) {
            return ScreenGeometryProvider.realWidth to ScreenGeometryProvider.realHeight
        }
        val context = ServiceLocator.context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val wm = context.getSystemService(WindowManager::class.java)
                val bounds = wm?.maximumWindowMetrics?.bounds
                if (bounds != null && bounds.width() > 0) return bounds.width() to bounds.height()
            }
        }
        @Suppress("DEPRECATION")
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }
}
