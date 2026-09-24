package com.screenlink.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.screenlink.app.core.AppSettings
import com.screenlink.app.core.QualityPreset
import com.screenlink.app.core.ServiceLocator
import com.screenlink.app.core.ThemeMode
import com.screenlink.app.ui.components.ChoiceRow
import com.screenlink.app.ui.components.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by ServiceLocator.settings.settings.collectAsState(initial = AppSettings())
    val repository = ServiceLocator.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            SectionCard(title = "外观", icon = Icons.Default.Palette) {
                Text("主题", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = ThemeMode.entries.toList(),
                    selected = settings.themeMode,
                    label = { mode ->
                        when (mode) {
                            ThemeMode.SYSTEM -> "跟随系统"
                            ThemeMode.LIGHT -> "浅色"
                            ThemeMode.DARK -> "深色"
                        }
                    },
                    onSelect = { mode -> scope.launch { repository.setThemeMode(mode) } },
                )
                SwitchRow(
                    title = "动态取色",
                    subtitle = "用系统壁纸的配色（Android 12+ 有效）",
                    checked = settings.dynamicColor,
                    onCheckedChange = { on -> scope.launch { repository.setDynamicColor(on) } },
                )
            }

            SectionCard(title = "画质", icon = Icons.Default.Tune) {
                Text("画面宽度", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = QualityPreset.widths,
                    selected = settings.captureWidth,
                    label = { "$it px" },
                    onSelect = { width -> scope.launch { repository.setCaptureWidth(width) } },
                )
                Text("JPEG 质量", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = QualityPreset.qualities,
                    selected = settings.jpegQuality,
                    label = { "q$it" },
                    onSelect = { quality -> scope.launch { repository.setJpegQuality(quality) } },
                )
                Text("最高帧率", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = QualityPreset.fpsList,
                    selected = settings.maxFps,
                    label = { "$it fps" },
                    onSelect = { fps -> scope.launch { repository.setMaxFps(fps) } },
                )
                Text(
                    text = "画质越低越省流量、越跟手；主控端连接时会把自己的设置推给被控端。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "被控端", icon = Icons.Default.Security) {
                SwitchRow(
                    title = "共享时保持屏幕常亮",
                    subtitle = "屏幕黑了系统可能暂停采集",
                    checked = settings.keepAwake,
                    onCheckedChange = { on -> scope.launch { repository.setKeepAwake(on) } },
                )
            }

            SectionCard(title = "关于", icon = Icons.Default.Info) {
                Text(
                    text = "ScreenLink v1.0.0\n" +
                        "· 被控端：MediaProjection 采集 + Shizuku 注入\n" +
                        "· 传输：TCP 自定义协议，AES-256-GCM 加密\n" +
                        "· 鉴权：HMAC-SHA256 挑战应答，密钥不上网\n" +
                        "· 端口默认 27100，密钥随机 16 位",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "只在自己拥有的设备上用。请勿用于未经授权的控制。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
