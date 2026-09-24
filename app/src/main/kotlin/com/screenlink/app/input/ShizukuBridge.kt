package com.screenlink.app.input

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    DENIED,
    AUTHORIZED;

    val label: String
        get() = when (this) {
            NOT_INSTALLED -> "未安装 Shizuku"
            NOT_RUNNING -> "Shizuku 未运行"
            DENIED -> "未授权给本应用"
            AUTHORIZED -> "已授权"
        }
}

/**
 * 只做三件事：盯着 Shizuku 的 binder、申请权限、借用 shell 身份干活。
 * 真正的注入逻辑在 [InputInjector]。
 *
 * 注意：Shizuku 13 把 newProcess 收成了 private 静态方法，只能用反射调；
 * 老版本是 public，两条路都留着。
 */
object ShizukuBridge {

    const val PERMISSION_REQUEST_CODE = 4001
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var initialized = false
    private lateinit var appContext: Context

    /** rikka.shizuku.SystemServiceHelper#getSystemService(String) */
    private val systemServiceMethod: Method? by lazy {
        runCatching {
            Class.forName("rikka.shizuku.SystemServiceHelper")
                .getMethod("getSystemService", String::class.java)
        }.getOrNull()
    }

    private val newProcessMethod: Method? by lazy {
        val cmdType = Array<String>::class.java
        runCatching {
            Class.forName("rikka.shizuku.Shizuku")
                .getDeclaredMethod("newProcess", cmdType, cmdType, String::class.java)
                .apply { isAccessible = true }
        }.getOrNull() ?: runCatching {
            Class.forName("rikka.shizuku.Shizuku")
                .getMethod("newProcess", cmdType, cmdType, String::class.java)
        }.getOrNull()
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        runCatching {
            Shizuku.addBinderReceivedListener(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() = refresh()
            })
            Shizuku.addBinderDeadListener(object : Shizuku.OnBinderDeadListener {
                override fun onBinderDead() = refresh()
            })
            Shizuku.addRequestPermissionResultListener(
                object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) = refresh()
                },
            )
        }
        refresh()
    }

    fun refresh() {
        _state.value = runCatching {
            when {
                !Shizuku.pingBinder() -> if (isShizukuInstalled()) {
                    ShizukuState.NOT_RUNNING
                } else {
                    ShizukuState.NOT_INSTALLED
                }

                Shizuku.getVersion() < 11 -> ShizukuState.NOT_RUNNING

                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    ShizukuState.AUTHORIZED

                else -> ShizukuState.DENIED
            }
        }.getOrElse { ShizukuState.NOT_INSTALLED }
    }

    private fun isShizukuInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    val hasPermission: Boolean get() = _state.value == ShizukuState.AUTHORIZED

    fun requestPermission() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
        refresh()
    }

    /** 以 shell 身份向系统服务管理器要一个 binder */
    fun systemService(name: String): IBinder? {
        if (!hasPermission) return null
        val method = systemServiceMethod ?: return null
        return runCatching { method.invoke(null, name) as? IBinder }.getOrNull()
    }

    /**
     * 把系统服务 binder 包一层，事务会由 Shizuku 服务端（shell 身份）发出。
     * 不包的话事务带的是本 App 的 uid，INJECT_EVENTS 之类的系统权限校验必然被拒。
     */
    fun wrapBinder(binder: IBinder): IBinder? = runCatching {
        Class.forName("rikka.shizuku.ShizukuBinderWrapper")
            .getConstructor(IBinder::class.java)
            .newInstance(binder) as IBinder
    }.getOrNull()

    /** 以 shell 身份跑一条命令 */
    fun newProcess(command: Array<String>): Process? {
        if (!hasPermission) return null
        val method = newProcessMethod ?: return null
        return runCatching { method.invoke(null, command, null, null) as? Process }.getOrNull()
    }

    fun version(): String = runCatching { "v${Shizuku.getVersion()}" }.getOrDefault("未知")
}
