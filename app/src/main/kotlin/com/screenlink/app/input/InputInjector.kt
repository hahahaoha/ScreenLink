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

enum class InjectorMode(val label: String) {
    AUTO("自动（优先实时）"),
    BINDER("强制实时注入"),
    SHELL("强制命令注入"),
}

/** 全局注入方式配置，设置页会改它 */
object InjectorConfig {
    @Volatile
    var mode: InjectorMode = InjectorMode.AUTO
}

/**
 * 远程输入的落地实现。
 *
 * 关键点：Shizuku 的 SystemServiceHelper 拿到的是**裸 binder**，如果直接调，
 * 事务会带我们 App 自己的 uid，InputManagerService 按 INJECT_EVENTS 校验时必然拒绝
 * （Android 13 及以前返回 false，Android 14+ 直接抛 SecurityException）。
 * 必须用 ShizukuBinderWrapper 包一层，事务才会以 shell 身份从 Shizuku 服务端发出。
 *
 * 实时路径失败时自动退到 Shizuku 跑 `input tap / swipe / keyevent`，保证还能用。
 */
object InputInjector {

    private const val MODE_ASYNC = 0
    private const val MODE_WAIT_FOR_FINISH = 1

    private const val MOVE_THRESHOLD_PX = 12f
    private const val GESTURE_TIMEOUT_MS = 6000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screenlink-inject")
    }
    private val scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())

    private val _status = MutableStateFlow("未初始化")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 用来把注入相关的话同步给界面和主控端 */
    var onLog: ((String) -> Unit)? = null

    private var inputManager: Any? = null
    private var injectInputEvent: Method? = null
    private var usingBinder = false
    private var binderProxied = false

    private var gestureActive = false
    private var gestureOnShell = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var watchdog: Job? = null
    private var loggedTouch = false

    // ────────────────────────── 初始化 ──────────────────────────

    fun prepare(): String {
        if (InjectorConfig.mode == InjectorMode.SHELL) {
            detachBinder()
        } else if (!usingBinder) {
            setupBinder()
        }
        val label = if (usingBinder) binderLabel() else shellLabel()
        _status.value = label
        return label
    }

    private fun binderLabel(): String =
        if (binderProxied) "Shizuku 实时注入（已代理）" else "Shizuku 实时注入（直连）"

    private fun shellLabel(): String =
        if (ShizukuBridge.hasPermission) "Shell 命令注入（兼容模式）" else "无注入能力（缺 Shizuku 授权）"

    private fun setupBinder(): Boolean {
        val raw = ShizukuBridge.systemService("input")
        if (raw == null) {
            _lastError.value = "拿不到 input 系统服务"
            onLog?.invoke("拿不到 input 系统服务，先确认 Shizuku 已授权")
            return false
        }
        // 先试代理（正确姿势），代理类不存在时退回裸 binder
        val proxied = ShizukuBridge.wrapBinder(raw)
        val candidates = mutableListOf<Pair<Any, Boolean>>()
        if (proxied != null) candidates.add(proxied to true)
        candidates.add(raw to false)

        for ((binder, isProxy) in candidates) {
            if (attach(binder)) {
                binderProxied = isProxy
                return true
            }
        }
        _lastError.value = "挂载 IInputManager 失败"
        onLog?.invoke("挂载 IInputManager 失败，只能走命令模式")
        return false
    }

    private fun attach(binder: Any): Boolean = try {
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        val service = stub
            .getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder)
        val method = Class.forName("android.hardware.input.IInputManager")
            .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        if (service == null) {
            false
        } else {
            inputManager = service
            injectInputEvent = method
            usingBinder = true
            true
        }
    } catch (t: Throwable) {
        _lastError.value = "反射失败：${t.javaClass.simpleName} ${t.message ?: ""}"
        inputManager = null
        injectInputEvent = null
        usingBinder = false
        false
    }

    private fun detachBinder() {
        inputManager = null
        injectInputEvent = null
        usingBinder = false
        binderProxied = false
    }

    private fun degrade(reason: String, detail: String?) {
        if (!usingBinder) return
        detachBinder()
        gestureOnShell = true
        _status.value = shellLabel()
        _lastError.value = reason
        val extra = detail?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
        onLog?.invoke("实时注入不可用：$reason$extra，已切到 Shell 命令注入（抬手才生效）")
    }

    // ────────────────────────── 对外接口 ──────────────────────────

    fun handleTouch(action: Int, nx: Float, ny: Float) {
        scope.launch { dispatchTouch(action, nx, ny) }
    }

    fun handleKey(keyCode: Int) {
        scope.launch {
            if (usingBinder && injectKeyViaBinder(keyCode, MODE_ASYNC)) return@launch
            shell("input", "keyevent", keyCode.toString())
        }
    }

    fun reset() {
        gestureActive = false
        gestureOnShell = false
        watchdog?.cancel()
        watchdog = null
    }

    /** 自检：注入一次「最近任务」，看屏幕有没有反应，并把真实结果报出来 */
    fun selfTest() {
        scope.launch {
            val label = prepare()
            onLog?.invoke("自检开始，当前方式：$label")
            val (width, height) = screenSize()
            onLog?.invoke("屏幕尺寸：${width}×${height}")
            delay(1500)

            if (usingBinder) {
                val ok = injectKeyViaBinder(KeyEvent.KEYCODE_APP_SWITCH, MODE_WAIT_FOR_FINISH)
                if (ok) {
                    onLog?.invoke("自检成功：实时注入生效，屏幕上应该弹出了最近任务")
                    _lastError.value = null
                    return@launch
                }
                onLog?.invoke("自检：实时注入被拒绝${_lastError.value?.let { "（$it）" } ?: ""}")
            }

            shell("input", "keyevent", KeyEvent.KEYCODE_APP_SWITCH.toString())
            onLog?.invoke("自检：已用命令模式发送「最近任务」，屏幕有反应吗？")
        }
    }

    // ────────────────────────── 触摸 ──────────────────────────

    private fun dispatchTouch(action: Int, nx: Float, ny: Float) {
        val (screenW, screenH) = screenSize()
        if (screenW <= 0 || screenH <= 0) return
        val x = nx.coerceIn(0f, 1f) * screenW
        val y = ny.coerceIn(0f, 1f) * screenH

        when (action) {
            TouchAction.DOWN -> {
                gestureActive = true
                gestureOnShell = !usingBinder
                downTime = SystemClock.uptimeMillis()
                downX = x
                downY = y
                lastX = x
                lastY = y
                moved = false

                if (usingBinder && !injectTouchViaBinder(MotionEvent.ACTION_DOWN, x, y, MODE_ASYNC)) {
                    degrade("按下事件被系统拒绝", _lastError.value)
                }
                if (!loggedTouch) {
                    loggedTouch = true
                    onLog?.invoke("首次触摸注入到 ($x, $y) / 屏幕 ${screenW}×${screenH}")
                }
                armWatchdog()
            }

            TouchAction.MOVE -> {
                if (!gestureActive) return
                if (abs(x - downX) > MOVE_THRESHOLD_PX || abs(y - downY) > MOVE_THRESHOLD_PX) {
                    moved = true
                }
                lastX = x
                lastY = y
                if (usingBinder && !gestureOnShell) {
                    if (!injectTouchViaBinder(MotionEvent.ACTION_MOVE, x, y, MODE_ASYNC)) {
                        degrade("移动事件被系统拒绝", _lastError.value)
                    }
                }
                armWatchdog()
            }

            TouchAction.UP, TouchAction.CANCEL -> {
                if (!gestureActive) return
                gestureActive = false
                watchdog?.cancel()
                watchdog = null
                val duration = (SystemClock.uptimeMillis() - downTime).toInt().coerceIn(20, 2000)

                if (usingBinder && !gestureOnShell) {
                    val eventAction = if (action == TouchAction.CANCEL) {
                        MotionEvent.ACTION_CANCEL
                    } else {
                        MotionEvent.ACTION_UP
                    }
                    if (injectTouchViaBinder(eventAction, x, y, MODE_ASYNC)) return
                    degrade("抬起事件被系统拒绝", _lastError.value)
                }
                shellTapOrSwipe(duration)
            }
        }
    }

    private fun shellTapOrSwipe(durationMs: Int) {
        if (moved) {
            shell(
                "input", "swipe",
                downX.roundToInt().toString(),
                downY.roundToInt().toString(),
                lastX.roundToInt().toString(),
                lastY.roundToInt().toString(),
                durationMs.toString(),
            )
        } else {
            shell("input", "tap", lastX.roundToInt().toString(), lastY.roundToInt().toString())
        }
        gestureOnShell = false
    }

    /** 防止主控端网络抖动导致"手指一直按着" */
    private fun armWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(GESTURE_TIMEOUT_MS)
            if (gestureActive) {
                gestureActive = false
                if (usingBinder && !gestureOnShell) {
                    injectTouchViaBinder(MotionEvent.ACTION_CANCEL, lastX, lastY, MODE_ASYNC)
                }
            }
        }
    }

    // ────────────────────────── 注入实现 ──────────────────────────

    private fun injectTouchViaBinder(action: Int, x: Float, y: Float, syncMode: Int): Boolean {
        val manager = inputManager ?: return false
        val method = injectInputEvent ?: return false
        return try {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            val result = method.invoke(manager, event, syncMode)
            event.recycle()
            val ok = result as? Boolean ?: false
            if (!ok) _lastError.value = "injectInputEvent 返回 false（权限或事件校验没过）"
            ok
        } catch (t: Throwable) {
            _lastError.value = "${t.javaClass.simpleName}: ${t.message ?: ""}"
            false
        }
    }

    private fun injectKeyViaBinder(keyCode: Int, syncMode: Int): Boolean {
        val manager = inputManager ?: return false
        val method = injectInputEvent ?: return false
        return try {
            val now = SystemClock.uptimeMillis()
            var ok = true
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(now, now, action, keyCode, 0)
                event.source = InputDevice.SOURCE_KEYBOARD
                val result = method.invoke(manager, event, syncMode)
                if (result as? Boolean != true) ok = false
            }
            if (!ok) _lastError.value = "injectInputEvent 返回 false（权限或事件校验没过）"
            ok
        } catch (t: Throwable) {
            _lastError.value = "${t.javaClass.simpleName}: ${t.message ?: ""}"
            false
        }
    }

    private fun shell(vararg parts: String) {
        val process = ShizukuBridge.newProcess(arrayOf(*parts))
        if (process == null) {
            _lastError.value = "shell 进程启动失败"
            onLog?.invoke("命令注入失败：Shizuku 没能起 shell 进程")
            return
        }
        runCatching { process.waitFor() }
    }

    // ────────────────────────── 几何 ──────────────────────────

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
