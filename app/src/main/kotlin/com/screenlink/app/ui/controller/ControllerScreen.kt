package com.screenlink.app.ui.controller

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.screenlink.app.core.AppSettings
import com.screenlink.app.core.ServiceLocator
import com.screenlink.app.net.ControllerClient
import com.screenlink.app.net.TouchAction
import com.screenlink.app.ui.components.LabeledField
import com.screenlink.app.ui.components.SectionCard
import com.screenlink.app.ui.components.StatPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerScreen(
    onBack: () -> Unit,
    viewModel: ControllerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.client.state.collectAsState()
    val rawFrame by viewModel.client.frame.collectAsState()
    val bitmap = remember(rawFrame) { rawFrame?.asImageBitmap() }
    val logs by viewModel.logs.collectAsState()
    val settings by ServiceLocator.settings.settings.collectAsState(initial = AppSettings())

    var ipInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var immersive by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        if (!loaded) {
            ipInput = settings.lastHost
            portInput = settings.lastPort.toString()
            keyInput = settings.accessKey
            loaded = true
        }
    }

    Scaffold(
        topBar = {
            if (!immersive) {
                TopAppBar(
                    title = {
                        Text(
                            if (state.connected) "已连接" else "主控端",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (state.connected) {
                            IconButton(onClick = { viewModel.disconnect() }) {
                                Icon(Icons.Default.Close, contentDescription = "断开")
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.connecting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!state.connected && !state.connecting) {
                ConnectPanel(
                    ip = ipInput,
                    port = portInput,
                    key = keyInput,
                    error = state.error,
                    logs = logs,
                    onIpChange = { ipInput = it },
                    onPortChange = { portInput = it.filter { c -> c.isDigit() }.take(5) },
                    onKeyChange = { keyInput = it.uppercase().take(32) },
                    onConnect = {
                        viewModel.connect(
                            host = ipInput.trim(),
                            port = portInput.toIntOrNull() ?: 27100,
                            key = keyInput,
                            width = settings.captureWidth,
                            quality = settings.jpegQuality,
                            fps = settings.maxFps,
                        )
                    },
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                RemoteSurface(
                    frame = bitmap,
                    connected = state.connected,
                    onTouch = { action, x, y -> viewModel.sendTouch(action, x, y) },
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.connected) {
                    IconButton(
                        onClick = { immersive = !immersive },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000)),
                    ) {
                        Icon(
                            imageVector = if (immersive) {
                                Icons.Default.FullscreenExit
                            } else {
                                Icons.Default.Fullscreen
                            },
                            contentDescription = "沉浸模式",
                            tint = Color.White,
                        )
                    }
                }
            }

            if (state.connected) {
                if (!immersive) {
                    KeyBar(onKey = { viewModel.sendKey(it) })
                }
                StatusBar(state = state, immersive = immersive)
            }
        }
    }
}

@Composable
private fun ConnectPanel(
    ip: String,
    port: String,
    key: String,
    error: String?,
    logs: List<String>,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "连到被控端", icon = Icons.Default.Key) {
            LabeledField(
                value = ip,
                onValueChange = onIpChange,
                label = "被控端 IP",
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Decimal,
                placeholder = "192.168.1.23",
            )
            LabeledField(
                value = port,
                onValueChange = onPortChange,
                label = "端口",
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Number,
                placeholder = "27100",
            )
            LabeledField(
                value = key,
                onValueChange = onKeyChange,
                label = "密钥",
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (key.length > 0) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                placeholder = "XXXX-XXXX-XXXX-XXXX",
            )
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = ip.isNotBlank() && port.isNotBlank(),
            ) {
                Text("连接")
            }
        }

        if (error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        if (logs.isNotEmpty()) {
            Text(
                text = logs.first(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RemoteSurface(
    frame: ImageBitmap?,
    connected: Boolean,
    onTouch: (Int, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (frame == null) {
            Text(
                text = if (connected) "已连接，等待第一帧画面…" else "还没有画面",
                color = Color(0xFF9AA0A6),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            var boxSize by remember { mutableStateOf(IntSize.Zero) }
            val mapper = remember(frame, boxSize) {
                FitMapper(boxSize, frame.width, frame.height)
            }
            val currentMapper = rememberUpdatedState(mapper)
            val currentOnTouch = rememberUpdatedState(onTouch)

            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { boxSize = it }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val normalized = currentMapper.value.map(down.position)
                            currentOnTouch.value(TouchAction.DOWN, normalized.first, normalized.second)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val point = currentMapper.value.map(change.position)
                                if (!change.pressed) {
                                    currentOnTouch.value(TouchAction.UP, point.first, point.second)
                                    change.consume()
                                    break
                                }
                                currentOnTouch.value(TouchAction.MOVE, point.first, point.second)
                                change.consume()
                            }
                        }
                    },
            )
        }
    }
}

/** 把 ContentScale.Fit 之后的显示区域算出来，保证点到哪就传到哪 */
private class FitMapper(box: IntSize, imageWidth: Int, imageHeight: Int) {

    private val displayWidth: Float
    private val displayHeight: Float
    private val offsetX: Float
    private val offsetY: Float

    init {
        val boxWidth = box.width.toFloat().coerceAtLeast(1f)
        val boxHeight = box.height.toFloat().coerceAtLeast(1f)
        val sourceWidth = imageWidth.toFloat().coerceAtLeast(1f)
        val sourceHeight = imageHeight.toFloat().coerceAtLeast(1f)
        val scale = minOf(boxWidth / sourceWidth, boxHeight / sourceHeight)
        displayWidth = sourceWidth * scale
        displayHeight = sourceHeight * scale
        offsetX = (boxWidth - displayWidth) / 2f
        offsetY = (boxHeight - displayHeight) / 2f
    }

    fun map(position: Offset): Pair<Float, Float> {
        val x = ((position.x - offsetX) / displayWidth).coerceIn(0f, 1f)
        val y = ((position.y - offsetY) / displayHeight).coerceIn(0f, 1f)
        return x to y
    }
}

@Composable
private fun KeyBar(onKey: (Int) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", KeyEvent.KEYCODE_BACK, onKey)
            KeyButton(Icons.Default.Home, "主页", KeyEvent.KEYCODE_HOME, onKey)
            KeyButton(Icons.Default.Apps, "最近任务", KeyEvent.KEYCODE_APP_SWITCH, onKey)
            KeyButton(Icons.Default.WbSunny, "唤醒", KeyEvent.KEYCODE_WAKEUP, onKey)
            KeyButton(Icons.Default.PowerSettingsNew, "电源", KeyEvent.KEYCODE_POWER, onKey)
            KeyButton(Icons.Default.VolumeDown, "音量-", KeyEvent.KEYCODE_VOLUME_DOWN, onKey)
            KeyButton(Icons.Default.VolumeUp, "音量+", KeyEvent.KEYCODE_VOLUME_UP, onKey)
            KeyButton(Icons.Default.KeyboardReturn, "回车", KeyEvent.KEYCODE_ENTER, onKey)
            KeyButton(Icons.Default.PhotoCamera, "截图", KeyEvent.KEYCODE_SYSRQ, onKey)
        }
    }
}

@Composable
private fun KeyButton(
    icon: ImageVector,
    label: String,
    keyCode: Int,
    onKey: (Int) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        IconButton(onClick = { onKey(keyCode) }) {
            Icon(icon, contentDescription = label)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusBar(state: ControllerClient.State, immersive: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(
                    label = "分辨率",
                    value = if (state.captureWidth > 0) {
                        "${state.captureWidth}×${state.captureHeight}"
                    } else {
                        "—"
                    },
                )
                StatPill(label = "帧率", value = "%.1f fps".format(state.fps))
                StatPill(
                    label = "延迟",
                    value = if (state.latencyMs >= 0) "${state.latencyMs} ms" else "—",
                )
                StatPill(label = "码率", value = "${state.kbps} KB/s")
            }
            if (state.error != null && !immersive) {
                Text(
                    text = state.error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
