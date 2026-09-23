package io.github.duskedge.synopilot.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duskedge.synopilot.data.AddressRecord
import io.github.duskedge.synopilot.data.Addresses
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.PreferRoute
import io.github.duskedge.synopilot.designsystem.SpFormat
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.CertificateDialog
import io.github.duskedge.synopilot.designsystem.component.SpBanner
import io.github.duskedge.synopilot.designsystem.component.SpBannerTone
import io.github.duskedge.synopilot.designsystem.component.SpButton
import io.github.duskedge.synopilot.designsystem.component.SpButtonSize
import io.github.duskedge.synopilot.designsystem.component.SpButtonVariant
import io.github.duskedge.synopilot.designsystem.component.SpCard
import io.github.duskedge.synopilot.designsystem.component.SpListGroup
import io.github.duskedge.synopilot.designsystem.component.SpListItem
import io.github.duskedge.synopilot.designsystem.component.SpSheet
import io.github.duskedge.synopilot.designsystem.component.SpStatus
import io.github.duskedge.synopilot.designsystem.component.SpStatusText
import io.github.duskedge.synopilot.designsystem.component.SpSwitch
import io.github.duskedge.synopilot.designsystem.component.SpTag
import io.github.duskedge.synopilot.designsystem.component.SpTextField
import org.koin.compose.viewmodel.koinViewModel

/** 服务端地址配置：主地址 + 备用地址 + 优先使用 + QuickConnect 兜底 + 地址列表。 */
@Composable
fun ServerAddressScreen(viewModel: ServerAddressViewModel = koinViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val device = ui.device ?: return
    val c = SpTheme.colors
    var pickingPrefer by rememberSaveable { mutableStateOf(false) }
    var historyMenu by remember { mutableStateOf<AddressRecord?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val connected = state as? ConnectionState.Connected
        SpBanner(
            title = connected?.let { "正在使用${it.route.slot.label()}" } ?: connectionSummary(state),
            message = connected?.let { "${it.route.url} · ${it.route.latencyMs} ms" },
            icon = connected?.route?.kind?.icon() ?: Icons.Outlined.CloudOff,
            tone = if (state is ConnectionState.Failed) SpBannerTone.Warning else SpBannerTone.Info,
        )
        SpBanner(
            title = "两个地址必须指向同一台 NAS",
            message = "测试时会核对设备序列号，填成其他设备的地址会提示。",
            icon = Icons.Outlined.Info,
        )

        AddressCard(
            title = "主地址",
            hint = "通常填外网地址：DDNS、反向代理域名或 Tailscale 地址",
            saved = device.primaryUrl,
            slot = ui.primary,
            using = connected?.route?.url == device.primaryUrl && device.primaryUrl.isNotBlank(),
            onChange = { viewModel.setDraft(AddressSlot.Primary, it) },
            onTest = { viewModel.test(AddressSlot.Primary) },
            onSave = { viewModel.test(AddressSlot.Primary, thenSave = true) },
        )
        AddressCard(
            title = "备用地址",
            hint = "通常填局域网地址，在家时更快",
            saved = device.backupUrl,
            slot = ui.backup,
            using = connected?.route?.url == device.backupUrl && device.backupUrl.isNotBlank(),
            onChange = { viewModel.setDraft(AddressSlot.Backup, it) },
            onTest = { viewModel.test(AddressSlot.Backup) },
            onSave = { viewModel.test(AddressSlot.Backup, thenSave = true) },
        )

        SpListGroup {
            SpListItem(
                title = "优先使用",
                icon = Icons.Outlined.SwapVert,
                subtitle = "两个地址都能连上时，优先用哪个",
                onClick = { pickingPrefer = true },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(device.prefer.label(), style = SpTheme.type.bodySmall, color = c.onSurfaceVariant)
                        Chevron()
                    }
                },
            )
            SpListItem(
                title = "QuickConnect 兜底",
                icon = Icons.Outlined.Cloud,
                subtitle = "两个地址都连不上时，经群晖中继连接（较慢，访问不到下载器等容器端口）",
                divider = true,
                trailing = { SpSwitch(checked = device.quickConnectFallback, onCheckedChange = viewModel::setQuickConnectFallback) },
            )
        }
        if (device.quickConnectFallback) {
            SpCard {
                SpTextField(
                    value = ui.quickConnectDraft,
                    onValueChange = viewModel::setQuickConnectDraft,
                    label = "QuickConnect ID",
                    placeholder = "例如 homelab-923",
                    supportingText = "在 DSM「控制面板 → 外部访问 → QuickConnect」查看",
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    SpButton(
                        "保存",
                        onClick = viewModel::saveQuickConnect,
                        enabled = ui.quickConnectDraft != device.quickConnectId,
                        size = SpButtonSize.Small,
                    )
                }
            }
        }

        if (device.addressHistory.isNotEmpty()) {
            Text("地址列表", style = SpTheme.type.section, color = c.onSurface, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            SpListGroup {
                device.addressHistory.forEachIndexed { i, r ->
                    val role = when (r.url) {
                        device.primaryUrl -> "主地址"
                        device.backupUrl -> "备用地址"
                        else -> null
                    }
                    val other = r.serial.isNotBlank() && device.serial.isNotBlank() && r.serial != device.serial
                    SpListItem(
                        title = r.url,
                        icon = Addresses.kindOf(r.url).icon(),
                        subtitle = listOfNotNull(
                            role,
                            if (other) "另一台 NAS（${r.model.ifBlank { r.serial }}）" else Addresses.kindOf(r.url).label(),
                            SpFormat.ago(r.lastUsedAt),
                        ).joinToString(" · "),
                        divider = i > 0,
                        onClick = { historyMenu = r },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    SnackbarHost(snackbar)

    if (pickingPrefer) {
        SpSheet(title = "优先使用", onDismiss = { pickingPrefer = false }) {
            SpListGroup {
                listOf(
                    PreferRoute.Auto to "选延迟更低的那个（推荐）",
                    PreferRoute.Primary to device.primaryUrl.ifBlank { "未设置" },
                    PreferRoute.Backup to device.backupUrl.ifBlank { "未设置" },
                ).forEachIndexed { i, (p, sub) ->
                    SpListItem(
                        title = p.label(),
                        subtitle = sub,
                        divider = i > 0,
                        onClick = {
                            viewModel.setPrefer(p)
                            pickingPrefer = false
                        },
                        trailing = { RadioButton(selected = device.prefer == p, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.primary)) },
                    )
                }
            }
        }
    }

    historyMenu?.let { r ->
        SpSheet(title = r.url, subtitle = "上次使用 ${SpFormat.ago(r.lastUsedAt)}", onDismiss = { historyMenu = null }) {
            SpListGroup {
                SpListItem(title = "设为主地址", onClick = { viewModel.useHistory(r, AddressSlot.Primary); historyMenu = null })
                SpListItem(title = "设为备用地址", divider = true, onClick = { viewModel.useHistory(r, AddressSlot.Backup); historyMenu = null })
                SpListItem(title = "删除", titleColor = c.error, divider = true, onClick = { viewModel.removeHistory(r); historyMenu = null })
            }
        }
    }

    ui.pendingCertificate?.let { (url, cert) ->
        CertificateDialog(
            host = url.substringAfter("://").substringBefore('/'),
            fingerprint = cert.fingerprint,
            subject = cert.subject,
            issuer = cert.issuer,
            notAfter = cert.notAfter,
            changed = device.pinnedCerts.isNotEmpty(),
            onTrust = viewModel::trustPendingCertificate,
            onCancel = viewModel::dismissCertificate,
        )
    }
}

@Composable
private fun AddressCard(
    title: String,
    hint: String,
    saved: String,
    slot: AddressSlotUi,
    using: Boolean,
    onChange: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    val c = SpTheme.colors
    val dirty = DsmUrl.normalize(slot.draft) != saved
    SpCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = SpTheme.type.label, color = c.onSurface, modifier = Modifier.weight(1f))
            if (using) SpTag("正在使用")
            when {
                slot.testing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.primary)
                slot.result != null -> SpStatusText(
                    if (slot.result.ok) SpStatus.Ok else SpStatus.Error,
                    if (slot.result.ok) "可用${slot.result.latencyMs?.let { " · $it ms" } ?: ""}" else "不可用",
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SpTextField(
            value = slot.draft,
            onValueChange = onChange,
            label = title,
            placeholder = "https://",
            leadingIcon = Addresses.kindOf(slot.draft).icon(),
            isError = slot.result?.ok == false,
            supportingText = slot.result?.message ?: hint,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SpButton("测试$title", onClick = onTest, enabled = slot.draft.isNotBlank() && !slot.testing, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
            Spacer(Modifier.weight(1f))
            SpButton("保存", onClick = onSave, enabled = dirty && slot.draft.isNotBlank() && !slot.testing, size = SpButtonSize.Small)
        }
    }
}

private object DsmUrl {
    fun normalize(url: String) = if (url.isBlank()) "" else io.github.duskedge.synopilot.network.DsmApi.normalizeBaseUrl(url)
}

private fun PreferRoute.label(): String = when (this) {
    PreferRoute.Auto -> "自动"
    PreferRoute.Primary -> "主地址"
    PreferRoute.Backup -> "备用地址"
}
