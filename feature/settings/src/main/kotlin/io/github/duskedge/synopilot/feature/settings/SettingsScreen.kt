package io.github.duskedge.synopilot.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duskedge.synopilot.data.AddressKind
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.Device
import io.github.duskedge.synopilot.data.Slot
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.SpButton
import io.github.duskedge.synopilot.designsystem.component.SpButtonSize
import io.github.duskedge.synopilot.designsystem.component.SpButtonVariant
import io.github.duskedge.synopilot.designsystem.component.SpListGroup
import io.github.duskedge.synopilot.designsystem.component.SpListItem
import io.github.duskedge.synopilot.designsystem.component.SpSectionHeader
import io.github.duskedge.synopilot.designsystem.component.SpSheet
import io.github.duskedge.synopilot.designsystem.component.SpSwitch
import io.github.duskedge.synopilot.designsystem.component.SpTag
import io.github.duskedge.synopilot.updater.UpdateManager
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

internal fun AddressKind.icon(): ImageVector = when (this) {
    AddressKind.Lan -> Icons.Outlined.Lan
    AddressKind.Tailscale -> Icons.Outlined.VpnLock
    AddressKind.Domain -> Icons.Outlined.Public
    AddressKind.QuickConnect -> Icons.Outlined.Cloud
}

internal fun AddressKind.label(): String = when (this) {
    AddressKind.Lan -> "局域网"
    AddressKind.Tailscale -> "Tailscale"
    AddressKind.Domain -> "外网域名"
    AddressKind.QuickConnect -> "QuickConnect"
}

internal fun Slot.label(): String = when (this) {
    Slot.Primary -> "主地址"
    Slot.Backup -> "备用地址"
    Slot.QuickConnect -> "QuickConnect"
}

/** 当前连接状态的一句话描述，例如「正在使用备用地址 · 局域网 · 3 ms」 */
fun connectionSummary(state: ConnectionState): String = when (state) {
    ConnectionState.NoDevice -> "未添加设备"
    is ConnectionState.Connecting -> "正在连接…"
    is ConnectionState.Connected -> "正在使用${state.route.slot.label()} · ${state.route.kind.label()} · ${state.route.latencyMs} ms"
    is ConnectionState.Failed -> state.reason.message
}

@Composable
fun SettingsScreen(
    onOpenServerAddress: () -> Unit,
    onAddDevice: () -> Unit,
    onOpenDownloaders: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    updateManager: UpdateManager = koinInject(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    var pickingPoll by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }
    val current = state.deviceOrNull

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        if (current != null) {
            SpSectionHeader("连接")
            SpListGroup {
                val route = (state as? ConnectionState.Connected)?.route
                SpListItem(
                    title = "服务端地址",
                    icon = route?.kind?.icon() ?: if (state is ConnectionState.Failed) Icons.Outlined.CloudOff else Icons.Outlined.Public,
                    subtitle = connectionSummary(state),
                    onClick = onOpenServerAddress,
                    trailing = { Chevron() },
                )
                val downloaders = current.downloaders
                SpListItem(
                    title = "下载器",
                    icon = Icons.Outlined.CloudDownload,
                    subtitle = if (downloaders.isEmpty()) "还没有添加" else downloaders.joinToString(" · ") { it.name },
                    divider = true,
                    onClick = onOpenDownloaders,
                    trailing = { Chevron() },
                )
            }
        }

        SpSectionHeader("设备")
        SpListGroup {
            devices.forEachIndexed { i, d ->
                SpListItem(
                    title = d.name,
                    icon = Icons.Outlined.Dns,
                    subtitle = listOf(d.model, d.dsmVersion.removePrefix("DSM "), d.account).filter { it.isNotBlank() }.joinToString(" · "),
                    divider = i > 0,
                    onClick = { viewModel.switchTo(d.id) },
                    trailing = { if (d.id == current?.id) Icon(Icons.Outlined.Check, null, tint = SpTheme.colors.primary, modifier = Modifier.size(20.dp)) },
                )
            }
            SpListItem(
                title = "添加 NAS",
                icon = Icons.Outlined.Add,
                divider = devices.isNotEmpty(),
                onClick = onAddDevice,
            )
        }

        SpSectionHeader("应用")
        SpListGroup {
            SpListItem(
                title = "指纹解锁",
                icon = Icons.Outlined.Fingerprint,
                subtitle = "打开 App 时验证身份",
                trailing = { SpSwitch(checked = settings.biometricLock, onCheckedChange = viewModel::setBiometricLock) },
            )
            SpListItem(
                title = "刷新间隔",
                icon = Icons.Outlined.Sync,
                subtitle = "${settings.pollSeconds} 秒 · App 在后台时暂停",
                divider = true,
                onClick = { pickingPoll = true },
                trailing = { Chevron() },
            )
        }

        AboutSection(updateManager)

        if (current != null) {
            Spacer(Modifier.height(12.dp))
            SpListGroup {
                SpListItem(
                    title = "退出并移除 ${current.name}",
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    titleColor = SpTheme.colors.error,
                    onClick = { confirmRemove = current.id },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickingPoll) {
        SpSheet(title = "刷新间隔", subtitle = "总览页数据的刷新频率", onDismiss = { pickingPoll = false }) {
            SpListGroup {
                listOf(1, 3, 10, 30).forEachIndexed { i, s ->
                    SpListItem(
                        title = "$s 秒",
                        subtitle = if (s == 3) "推荐" else null,
                        divider = i > 0,
                        onClick = {
                            viewModel.setPollSeconds(s)
                            pickingPoll = false
                        },
                        trailing = {
                            RadioButton(
                                selected = settings.pollSeconds == s,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = SpTheme.colors.primary),
                            )
                        },
                    )
                }
            }
        }
    }

    confirmRemove?.let { id ->
        val device = devices.firstOrNull { it.id == id }
        if (device != null) {
            RemoveDeviceDialog(
                device = device,
                onConfirm = {
                    viewModel.remove(device)
                    confirmRemove = null
                },
                onDismiss = { confirmRemove = null },
            )
        }
    }
}

@Composable
private fun RemoveDeviceDialog(device: Device, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = SpTheme.colors.card,
        title = { Text("退出并移除 ${device.name}？", style = SpTheme.type.title) },
        text = {
            Text(
                "会注销这台手机在 DSM 上的登录，并删除保存的地址和密码。NAS 上的数据不受影响。",
                style = SpTheme.type.bodySmall,
                color = SpTheme.colors.onSurfaceVariant,
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SpButton("取消", onClick = onDismiss, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
                SpButton("退出并移除", onClick = onConfirm, variant = SpButtonVariant.Danger, size = SpButtonSize.Small)
            }
        },
    )
}

@Composable
internal fun Chevron() {
    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = SpTheme.colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
}

/** 顶栏点设备名时弹出的设备切换面板 */
@Composable
fun DeviceSwitcherSheet(
    onDismiss: () -> Unit,
    onAddDevice: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    SpSheet(title = "切换设备", onDismiss = onDismiss) {
        SpListGroup {
            devices.forEachIndexed { i, d ->
                val isCurrent = d.id == state.deviceOrNull?.id
                SpListItem(
                    title = d.name,
                    icon = Icons.Outlined.Dns,
                    subtitle = if (isCurrent) connectionSummary(state) else listOf(d.model, d.account).filter { it.isNotBlank() }.joinToString(" · "),
                    divider = i > 0,
                    onClick = {
                        viewModel.switchTo(d.id)
                        onDismiss()
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isCurrent) SpTag("当前")
                        }
                    },
                )
            }
        }
        SpButton(
            "添加 NAS",
            icon = Icons.Outlined.Add,
            onClick = {
                onDismiss()
                onAddDevice()
            },
            variant = SpButtonVariant.Tonal,
            size = SpButtonSize.Block,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
