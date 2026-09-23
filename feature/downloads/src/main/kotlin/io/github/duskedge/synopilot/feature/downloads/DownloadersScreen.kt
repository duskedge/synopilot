package io.github.duskedge.synopilot.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duskedge.synopilot.data.DownloaderConfig
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.SpBanner
import io.github.duskedge.synopilot.designsystem.component.SpBannerTone
import io.github.duskedge.synopilot.designsystem.component.SpButton
import io.github.duskedge.synopilot.designsystem.component.SpButtonSize
import io.github.duskedge.synopilot.designsystem.component.SpButtonVariant
import io.github.duskedge.synopilot.designsystem.component.SpListGroup
import io.github.duskedge.synopilot.designsystem.component.SpListItem
import io.github.duskedge.synopilot.designsystem.component.SpMessageHost
import io.github.duskedge.synopilot.designsystem.component.SpSectionHeader
import io.github.duskedge.synopilot.designsystem.component.SpSheet
import io.github.duskedge.synopilot.designsystem.component.SpStatus
import io.github.duskedge.synopilot.designsystem.component.SpSwitch
import io.github.duskedge.synopilot.designsystem.component.SpTag
import io.github.duskedge.synopilot.designsystem.component.SpTextField
import io.github.duskedge.synopilot.network.download.EngineKind
import org.koin.compose.viewmodel.koinViewModel

private fun EngineKind.icon(): ImageVector = when (this) {
    EngineKind.DownloadStation -> Icons.Outlined.CloudDownload
    else -> Icons.Outlined.ViewInAr
}

private fun EngineKind.label() = when (this) {
    EngineKind.DownloadStation -> "Download Station"
    EngineKind.QBittorrent -> "qBittorrent"
    EngineKind.Transmission -> "Transmission"
}

private fun summary(cfg: DownloaderConfig): String = when (cfg.kind) {
    EngineKind.DownloadStation -> "使用 NAS 登录会话"
    else -> listOfNotNull(
        cfg.lanUrl.takeIf { it.isNotBlank() }?.removePrefix("http://"),
        if (cfg.remoteUrl.isNotBlank()) "外网 ${cfg.remoteUrl.substringAfter("://")}" else "仅局域网 / Tailscale",
    ).joinToString(" · ")
}

@Composable
fun DownloadersScreen(viewModel: DownloadersViewModel = koinViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val discover by viewModel.discover.collectAsStateWithLifecycle()
    var choosingKind by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.routeKind == "QuickConnect" && ui.downloaders.any { it.kind != EngineKind.DownloadStation && it.remoteUrl.isBlank() }) {
                SpBanner(
                    "当前通过 QuickConnect 连接",
                    message = "QuickConnect 只转发 DSM，访问不到容器端口。qBittorrent / Transmission 需要配置外网地址（反向代理或端口转发）才能在外网使用。",
                    tone = SpBannerTone.Warning,
                )
            }
            if (ui.downloaders.isEmpty()) {
                Text(
                    "添加 Download Station，或 NAS 上以容器方式运行的 qBittorrent、Transmission。可以先试试自动发现。",
                    style = SpTheme.type.bodySmall,
                    color = SpTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            } else {
                SpListGroup {
                    ui.downloaders.forEachIndexed { i, cfg ->
                        SpListItem(
                            title = cfg.name,
                            subtitle = summary(cfg),
                            icon = cfg.kind.icon(),
                            divider = i > 0,
                            onClick = { viewModel.edit(cfg) },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (cfg.id == ui.defaultId) SpTag("默认")
                                    SpSwitch(cfg.enabled, { viewModel.setEnabled(cfg, it) })
                                }
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpButton(
                    if (discover.running) "正在查找…" else "自动发现",
                    onClick = viewModel::runDiscovery,
                    icon = Icons.Outlined.TravelExplore,
                    variant = SpButtonVariant.Tonal,
                    enabled = ui.connected && !discover.running,
                    modifier = Modifier.weight(1f),
                )
                SpButton("手动添加", onClick = { choosingKind = true }, icon = Icons.Outlined.Add, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f))
            }
            discover.found?.takeIf { it.isNotEmpty() }?.let { found ->
                SpSectionHeader("发现的下载器")
                SpListGroup {
                    found.forEachIndexed { i, d ->
                        SpListItem(
                            title = d.config.name,
                            subtitle = listOf(d.source, d.config.lanUrl.removePrefix("http://")).filter { it.isNotBlank() }.joinToString(" · "),
                            icon = d.config.kind.icon(),
                            divider = i > 0,
                            onClick = { viewModel.fromDiscovered(d) },
                            trailing = { SpButton("添加", onClick = { viewModel.fromDiscovered(d) }, size = SpButtonSize.Small, variant = SpButtonVariant.Text) },
                        )
                    }
                }
            }
            Text(
                "qBittorrent 和 Transmission 跑在容器里，只能通过端口访问：局域网直接连；Tailscale 下自动换成 Tailscale 地址；" +
                    "通过域名或 QuickConnect 连接 NAS 时，需要单独配置外网地址。",
                style = SpTheme.type.caption,
                color = SpTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
        SpMessageHost(viewModel.messages)
    }

    if (choosingKind) {
        SpSheet(title = "添加下载器", onDismiss = { choosingKind = false }) {
            SpListGroup {
                EngineKind.entries.forEachIndexed { i, k ->
                    SpListItem(
                        title = k.label(),
                        subtitle = when (k) {
                            EngineKind.DownloadStation -> "群晖套件，复用 NAS 登录"
                            EngineKind.QBittorrent -> "WebUI 地址，如 http://192.168.1.10:8080"
                            EngineKind.Transmission -> "RPC 地址，如 http://192.168.1.10:9091"
                        },
                        icon = k.icon(),
                        divider = i > 0,
                        onClick = {
                            choosingKind = false
                            viewModel.newDraft(k)
                        },
                    )
                }
            }
        }
    }

    draft?.let { d -> DraftSheet(d, viewModel) }
}

@Composable
private fun DraftSheet(d: DownloaderDraft, vm: DownloadersViewModel) {
    val cfg = d.config
    val isDs = cfg.kind == EngineKind.DownloadStation
    var confirmDelete by remember { mutableStateOf(false) }
    SpSheet(title = if (d.isNew) "添加 ${cfg.kind.label()}" else cfg.name, subtitle = if (d.isNew) null else cfg.kind.label(), onDismiss = vm::closeDraft) {
        SpTextField(cfg.name, { v -> vm.update { it.copy(config = it.config.copy(name = v)) } }, label = "名称")
        if (!isDs) {
            SpTextField(
                cfg.lanUrl, { v -> vm.update { it.copy(config = it.config.copy(lanUrl = v)) } },
                label = "局域网地址",
                placeholder = if (cfg.kind == EngineKind.QBittorrent) "http://192.168.1.10:8080" else "http://192.168.1.10:9091",
                leadingIcon = Icons.Outlined.Dns,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            SpTextField(
                cfg.remoteUrl, { v -> vm.update { it.copy(config = it.config.copy(remoteUrl = v)) } },
                label = "外网地址（可选）",
                placeholder = "https://qb.example.com",
                supportingText = "通过域名或 QuickConnect 连接 NAS 时使用",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            SpTextField(cfg.username, { v -> vm.update { it.copy(config = it.config.copy(username = v)) } }, label = "用户名")
            SpTextField(
                d.password, { v -> vm.update { it.copy(password = v) } },
                label = "密码",
                placeholder = if (d.isNew) null else "不修改请留空",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }
        SpListGroup {
            SpListItem("启用", trailing = { SpSwitch(cfg.enabled, { v -> vm.update { it.copy(config = it.config.copy(enabled = v)) } }) })
            SpListItem("设为默认下载器", subtitle = "新建任务时默认选中", divider = true, trailing = { SpSwitch(d.isDefault, { v -> vm.update { it.copy(isDefault = v) } }) })
        }
        d.testResult?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpTag(if (d.testOk == true) "成功" else "失败", status = if (d.testOk == true) SpStatus.Ok else SpStatus.Error)
                Text(r, style = SpTheme.type.bodySmall, color = SpTheme.colors.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SpButton(
                if (d.testing) "测试中…" else "测试连接",
                onClick = vm::test,
                variant = SpButtonVariant.Tonal,
                enabled = !d.testing,
                modifier = Modifier.weight(1f),
            )
            SpButton("保存", onClick = vm::save, enabled = cfg.name.isNotBlank() && (isDs || cfg.lanUrl.isNotBlank() || cfg.remoteUrl.isNotBlank()), modifier = Modifier.weight(1f))
        }
        if (d.testing) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SpTheme.colors.primary)
            }
        }
        if (!d.isNew) {
            if (!confirmDelete) {
                SpButton("删除这个下载器", onClick = { confirmDelete = true }, variant = SpButtonVariant.Text, modifier = Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpButton("取消", onClick = { confirmDelete = false }, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f))
                    SpButton("确认删除", onClick = vm::delete, variant = SpButtonVariant.Danger, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
