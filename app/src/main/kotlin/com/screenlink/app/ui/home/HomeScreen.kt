package com.screenlink.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.screenlink.app.input.ShizukuBridge
import com.screenlink.app.input.ShizukuState
import com.screenlink.app.ui.components.StatusLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onHost: () -> Unit,
    onController: () -> Unit,
    onSettings: () -> Unit,
) {
    val shizukuState by ShizukuBridge.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ScreenLink") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
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
            Text(
                text = "把一台手机的屏幕和操作，交给另一台手机",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ShizukuCard(state = shizukuState)

            RoleCard(
                icon = Icons.Default.PhoneAndroid,
                title = "作为被控端",
                description = "把本机屏幕共享出去，并接受主控端的点击、滑动、按键。",
                onClick = onHost,
            )

            RoleCard(
                icon = Icons.Default.SettingsRemote,
                title = "作为主控端",
                description = "填入被控端的 IP、端口和密钥，远程看屏幕、远程操作。",
                onClick = onController,
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("连接怎么走", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "被控端开一个 TCP 端口等连接，主控端填 IP + 端口 + 密钥。\n" +
                            "同一个 Wi-Fi 下直接填内网 IP；跨网络需要自己做端口映射或用 VPN。\n" +
                            "密钥只在本地校验，走的是挑战应答 + AES-256-GCM 加密，不在网络上明文传输。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ShizukuCard(state: ShizukuState) {
    val context = LocalContext.current
    val authorized = state == ShizukuState.AUTHORIZED

    Surface(
        color = if (authorized) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        contentColor = if (authorized) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (authorized) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Shizuku 授权",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                StatusLine(
                    color = if (authorized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    text = state.label,
                )
            }

            Text(
                text = when (state) {
                    ShizukuState.AUTHORIZED -> "远程注入用 Shizuku 的 shell 身份执行，可以做到实时跟手拖动。"
                    ShizukuState.DENIED -> "点下面的按钮，在弹出的 Shizuku 窗口里允许本应用，然后回来。"
                    ShizukuState.NOT_RUNNING -> "Shizuku 已安装但没在跑。先打开 Shizuku 应用启动服务（Android 11+ 可用无线调试启动）。"
                    ShizukuState.NOT_INSTALLED -> "没检测到 Shizuku。装好并启动后，被控端才能把点击注入进系统。"
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state == ShizukuState.DENIED) {
                    Button(onClick = { ShizukuBridge.requestPermission() }) {
                        Text("申请授权")
                    }
                }
                if (state == ShizukuState.NOT_RUNNING || state == ShizukuState.NOT_INSTALLED) {
                    FilledTonalButton(
                        onClick = {
                            val intent = context.packageManager
                                .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            if (intent != null) runCatching { context.startActivity(intent) }
                        },
                    ) {
                        Text(if (state == ShizukuState.NOT_INSTALLED) "去安装" else "打开 Shizuku")
                    }
                }
                FilledTonalButton(onClick = { ShizukuBridge.refresh() }) {
                    Text("刷新状态")
                }
            }
        }
    }
}

@Composable
private fun RoleCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(24.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
