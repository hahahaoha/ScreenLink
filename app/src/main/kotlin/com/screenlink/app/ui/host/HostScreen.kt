package com.screenlink.app.ui.host

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.screenlink.app.capture.HostRuntime
import com.screenlink.app.capture.HostService
import com.screenlink.app.core.AppSettings
import com.screenlink.app.core.NetworkUtils
import com.screenlink.app.core.ServiceLocator
import com.screenlink.app.input.InputInjector
import com.screenlink.app.input.ShizukuBridge
import com.screenlink.app.input.ShizukuState
import com.screenlink.app.net.Crypto
import com.screenlink.app.ui.components.LabeledField
import com.screenlink.app.ui.components.SectionCard
import com.screenlink.app.ui.components.StatPill
import com.screenlink.app.ui.components.StatusLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current

    val settings by ServiceLocator.settings.settings.collectAsState(initial = AppSettings())
    val hostState by HostRuntime.state.collectAsState()
    val logs by HostRuntime.logs.collectAsState()
    val shizukuState by ShizukuBridge.state.collectAsState()
    val injectorStatus by InputInjector.status.collectAsState()
    val injectorError by InputInjector.lastError.collectAsState()

    var keyInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var localIp by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings) {
        if (!loaded) {
            keyInput = settings.accessKey
            portInput = settings.port.toString()
            loaded = true
        }
    }

    LaunchedEffect(loaded, hostState.running) {
        localIp = NetworkUtils.localIpv4()
    }

    // 输入改完过一会儿自动存
    LaunchedEffect(keyInput, portInput, loaded) {
        if (!loaded) return@LaunchedEffect
        delay(600)
        ServiceLocator.settings.setAccessKey(keyInput)
        portInput.toIntOrNull()?.let { port ->
            if (port in 1024..65535) ServiceLocator.settings.setPort(port)
        }
    }

    LaunchedEffect(hostState.running, settings.keepAwake) {
        view.keepScreenOn = hostState.running && settings.keepAwake
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data: Intent? = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val port = portInput.toIntOrNull()?.takeIf { it in 1024..65535 } ?: 27100
            HostService.start(context, result.resultCode, data, port, keyInput)
        } else {
            HostRuntime.log("你取消了屏幕采集授权")
        }
    }

    fun ensureKey(): String {
        var normalized = Crypto.normalizeKey(keyInput)
        if (normalized.length < 6) {
            keyInput = Crypto.randomKey()
            normalized = Crypto.normalizeKey(keyInput)
        }
        return normalized
    }

    fun persist() {
        scope.launch {
            ServiceLocator.settings.setAccessKey(keyInput)
            portInput.toIntOrNull()?.let { if (it in 1024..65535) ServiceLocator.settings.setPort(it) }
        }
    }

    fun startHosting() {
        ensureKey()
        persist()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("被控端") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { HostRuntime.clearLogs() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "清空日志")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RunningBanner(
                running = hostState.running,
                listening = hostState.listening,
                error = hostState.error,
            )

            SectionCard(title = "连接密钥", icon = Icons.Default.Key) {
                LabeledField(
                    value = keyInput,
                    onValueChange = { keyInput = Crypto.normalizeKey(it).chunked(4).joinToString("-") },
                    label = "密钥（对方要填一模一样的）",
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailing = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (keyVisible) "隐藏" else "显示",
                            )
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        keyInput = Crypto.randomKey()
                        keyVisible = true
                        persist()
                    }) {
                        Text("随机生成")
                    }
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(Crypto.normalizeKey(keyInput)))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("复制")
                    }
                }
                Text(
                    text = "16 位随机字符 ≈ 80 bit 熵。密钥不会在网络上传输，只用来做挑战应答校验。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "监听端口", icon = Icons.Default.Link) {
                LabeledField(
                    value = portInput,
                    onValueChange = { portInput = it.filter { c -> c.isDigit() }.take(5) },
                    label = "端口（1024 - 65535）",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Number,
                    placeholder = "27100",
                )
                val address = "${localIp ?: "本机内网 IP"}:${hostState.port}"
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("主控端填这个地址", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = address,
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(address)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制地址")
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!hostState.running) {
                    Button(
                        onClick = { startHosting() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("开始共享")
                    }
                } else {
                    Button(
                        onClick = {
                            HostService.apply(
                                context,
                                portInput.toIntOrNull()?.takeIf { it in 1024..65535 } ?: 27100,
                                Crypto.normalizeKey(keyInput),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("应用新配置")
                    }
                    OutlinedButton(
                        onClick = { HostService.stop(context) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("停止")
                    }
                }
            }

            if (hostState.running || hostState.listening) {
                SectionCard(title = "运行状态", icon = Icons.Default.Terminal) {
                    val clientText = hostState.client ?: "无"
                    Text(
                        text = "主控端：$clientText",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatPill(
                            label = "本机分辨率",
                            value = if (hostState.realWidth > 0) {
                                "${hostState.realWidth}×${hostState.realHeight}"
                            } else {
                                "—"
                            },
                        )
                        StatPill(
                            label = "传输画面",
                            value = if (hostState.captureWidth > 0) {
                                "${hostState.captureWidth}×${hostState.captureHeight}"
                            } else {
                                "—"
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatPill(label = "编码帧率", value = "%.1f fps".format(hostState.fps))
                        StatPill(label = "已发送", value = "${hostState.frames} 帧")
                        StatPill(
                            label = "画质",
                            value = "q${com.screenlink.app.capture.LiveQuality.jpegQuality}",
                        )
                    }
                    StatusLine(
                        color = when (shizukuState) {
                            ShizukuState.AUTHORIZED -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                        text = "注入方式：${injectorStatus}",
                    )
                    injectorError?.let { error ->
                        Text(
                            text = "最近一次注入错误：$error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    FilledTonalButton(onClick = { InputInjector.selfTest() }) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("注入自检")
                    }
                }
            }

            SectionCard(title = "日志", icon = Icons.Default.Terminal) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 230.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "还没有日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        logs.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("被控端注意事项", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "· 需要 Shizuku 授权，否则只能看画面，点不动。\n" +
                            "· 系统会弹一次「开始投放或录制」，必须同意。\n" +
                            "· 手机息屏后系统可能暂停投放，建议保持亮屏或充着电。\n" +
                            "· 有主控端连接时，通知栏会一直显示，点通知里的「停止」可以随时掐断。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RunningBanner(running: Boolean, listening: Boolean, error: String?) {
    val (color, title, subtitle) = when {
        error != null -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            "出错了",
            error,
        )

        running && listening -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            "正在等待连接",
            "屏幕采集已开启，Shizuku 注入已就绪。",
        )

        running -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            "采集已开启，端口没起来",
            "检查端口是否被占用。",
        )

        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            "未共享",
            "设置好密钥后点「开始共享」。",
        )
    }

    val onColor = if (error != null) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        color = color,
        contentColor = onColor,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (running) Color(0xFF34C759) else Color(0xFF9E9E9E)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
