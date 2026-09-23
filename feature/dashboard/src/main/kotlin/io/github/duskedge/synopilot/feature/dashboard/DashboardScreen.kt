package io.github.duskedge.synopilot.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.DashboardData
import io.github.duskedge.synopilot.data.FailureReason
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
import io.github.duskedge.synopilot.designsystem.component.SpMeter
import io.github.duskedge.synopilot.designsystem.component.SpNetworkChart
import io.github.duskedge.synopilot.designsystem.component.SpSheet
import io.github.duskedge.synopilot.designsystem.component.SpStatus
import io.github.duskedge.synopilot.designsystem.component.SpStatusBars
import io.github.duskedge.synopilot.designsystem.component.SpStatusText
import io.github.duskedge.synopilot.designsystem.component.meterColorFor
import io.github.duskedge.synopilot.network.Disk
import io.github.duskedge.synopilot.network.Health
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DashboardScreen(
    onRelogin: (deviceId: String) -> Unit,
    onOpenServerAddress: () -> Unit,
    viewModel: DashboardViewModel = koinViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showDisks by rememberSaveable { mutableStateOf(false) }
    val data = ui.data

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ConnectionBanner(ui.connection, data, onRetry = viewModel::retry, onRelogin = onRelogin, onOpenServerAddress = onOpenServerAddress)

        if (data?.info == null && data?.utilization == null) {
            if (ui.connection is ConnectionState.Connecting || ui.connection is ConnectionState.Connected) {
                Row(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = SpTheme.colors.primary)
                }
            }
        } else {
            DeviceCard(data)
            NetworkCard(data)
            StorageCard(data, onDisks = { showDisks = true })
            data.ups?.takeIf { it.enabled }?.let { ups ->
                SpListGroup {
                    SpListItem(
                        title = "UPS",
                        icon = Icons.Outlined.BatteryChargingFull,
                        subtitle = buildString {
                            append(if (ups.onBattery) "电池供电" else "市电供电")
                            ups.chargePercent?.let { append(" · 电量 $it%") }
                            ups.runtimeSec?.let { append(" · 可续航 ${it / 60} 分钟") }
                        },
                        trailing = { if (ups.onBattery) SpStatusText(SpStatus.Warning, "断电中") },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    (ui.connection as? ConnectionState.Failed)?.let { failed ->
        val reason = failed.reason
        if (reason is FailureReason.UntrustedCertificate) {
            CertificateDialog(
                host = reason.url.substringAfter("://").substringBefore('/'),
                fingerprint = reason.certificate.fingerprint,
                subject = reason.certificate.subject,
                issuer = reason.certificate.issuer,
                notAfter = reason.certificate.notAfter,
                changed = failed.device.pinnedCerts.isNotEmpty(),
                onTrust = { viewModel.trustCertificate(reason) },
                onCancel = { },
            )
        }
    }

    if (showDisks) {
        data?.storage?.let { storage ->
            SpSheet(title = "硬盘", subtitle = "${storage.disks.size} 块硬盘", onDismiss = { showDisks = false }) {
                SpListGroup {
                    storage.disks.forEachIndexed { i, d ->
                        SpListItem(
                            title = d.name,
                            icon = if (d.isCache) Icons.Outlined.Memory else Icons.Outlined.Storage,
                            subtitle = listOfNotNull(
                                listOf(d.vendor, d.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
                                d.sizeBytes.takeIf { it > 0 }?.let(SpFormat::bytes),
                                d.temperatureC?.let { "$it°C" },
                            ).joinToString(" · "),
                            divider = i > 0,
                            trailing = { SpStatusText(d.health.toStatus(), d.health.label()) },
                        )
                    }
                }
                Text("状态分级和 DSM 存储管理器一致：良好 / 警告 / 危险。", style = SpTheme.type.caption, color = SpTheme.colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ConnectionBanner(
    state: ConnectionState,
    data: DashboardData?,
    onRetry: () -> Unit,
    onRelogin: (String) -> Unit,
    onOpenServerAddress: () -> Unit,
) {
    val cacheNote = data?.takeIf { it.fromCache && it.updatedAt > 0 }?.let {
        val ago = SpFormat.ago(it.updatedAt)
        if (ago == "刚刚") "显示的是刚刚缓存的数据" else "显示的是 ${ago}的数据"
    }
    when (state) {
        is ConnectionState.Connecting -> if (data?.fromCache == true) {
            SpBanner("正在连接 ${state.device.name}…", message = cacheNote)
        }
        is ConnectionState.Failed -> when (val r = state.reason) {
            is FailureReason.NeedsLogin -> SpBanner(
                "需要重新登录", message = r.message, icon = Icons.Outlined.ErrorOutline, tone = SpBannerTone.Warning,
                actions = { SpButton("重新登录", onClick = { onRelogin(state.device.id) }, size = SpButtonSize.Small) },
            )
            is FailureReason.WrongDevice -> SpBanner(
                "地址指向了另一台 NAS", message = r.message, icon = Icons.Outlined.ErrorOutline, tone = SpBannerTone.Error,
                actions = { SpButton("检查服务端地址", onClick = onOpenServerAddress, size = SpButtonSize.Small) },
            )
            is FailureReason.UntrustedCertificate -> SpBanner(
                "证书需要确认", message = r.message, icon = Icons.Outlined.ErrorOutline, tone = SpBannerTone.Warning,
                actions = { SpButton("重试", onClick = onRetry, variant = SpButtonVariant.Tonal, size = SpButtonSize.Small) },
            )
            is FailureReason.Offline -> SpBanner(
                "无法连接到 ${state.device.name}", message = listOfNotNull(cacheNote, r.message).joinToString("\n"),
                icon = Icons.Outlined.CloudOff, tone = SpBannerTone.Warning,
                actions = {
                    SpButton("重试", onClick = onRetry, size = SpButtonSize.Small)
                    SpButton("服务端地址", onClick = onOpenServerAddress, variant = SpButtonVariant.Tonal, size = SpButtonSize.Small)
                },
            )
        }
        else -> Unit
    }
}

@Composable
private fun DeviceCard(data: DashboardData) {
    val c = SpTheme.colors
    val info = data.info
    val util = data.utilization
    val warnDisks = data.storage?.disks?.count { it.health == Health.Warning || it.health == Health.Error } ?: 0
    val attention = warnDisks > 0 || info?.temperatureWarn == true || data.storage?.pools?.any { it.health == Health.Error || it.health == Health.Warning } == true
    SpCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(info?.model?.let { "Synology $it" } ?: "Synology", style = SpTheme.type.title.copy(fontSize = SpTheme.type.num.fontSize * 0.85f), color = c.onSurface)
                Text(
                    listOfNotNull(
                        info?.versionString?.removePrefix("DSM ")?.let { "DSM $it" },
                        info?.uptimeSec?.takeIf { it > 0 }?.let { "已运行 ${SpFormat.uptime(it)}" },
                    ).joinToString(" · "),
                    style = SpTheme.type.caption,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SpStatusText(if (attention) SpStatus.Warning else SpStatus.Ok, if (attention) "需要关注" else "运行正常")
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val cpu = util?.cpuPercent
            Kpi("CPU", cpu?.toString() ?: "–", "%", Modifier.weight(1f), fraction = cpu?.div(100f))
            val mem = util?.memoryPercent
            Kpi("内存", mem?.toString() ?: "–", "%", Modifier.weight(1f), fraction = mem?.div(100f))
            Kpi(
                "温度", info?.temperatureC?.toString() ?: "–", "°C", Modifier.weight(1f),
                sub = if (info?.temperatureWarn == true) "温度过高" else "正常",
                subColor = if (info?.temperatureWarn == true) c.error else c.onSurfaceVariant,
            )
            val free = data.storage?.let { it.totalBytes - it.usedBytes }?.takeIf { it > 0 }
            val (freeNum, freeUnit) = free?.let(SpFormat::bytesParts) ?: ("–" to "")
            Kpi("可用", freeNum, freeUnit, Modifier.weight(1f), sub = "存储空间")
        }
    }
}

@Composable
private fun Kpi(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier,
    fraction: Float? = null,
    sub: String? = null,
    subColor: androidx.compose.ui.graphics.Color = SpTheme.colors.onSurfaceVariant,
) {
    val c = SpTheme.colors
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(c.card2)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(label, style = SpTheme.type.caption, color = c.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = SpTheme.type.num, color = c.onSurface, maxLines = 1)
            Text(unit, style = SpTheme.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 1.dp, bottom = 3.dp))
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(6.dp))
        if (fraction != null) {
            SpMeter(fraction, cells = 10, cellHeight = 4, color = meterColorFor(fraction))
        } else {
            Text(sub.orEmpty(), style = SpTheme.type.caption.copy(fontSize = SpTheme.type.caption.fontSize * 0.92f), color = subColor, maxLines = 1)
        }
    }
}

@Composable
private fun NetworkCard(data: DashboardData) {
    val c = SpTheme.colors
    val util = data.utilization
    SpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("网络", style = SpTheme.type.label, color = c.onSurface, modifier = Modifier.weight(1f))
            SpeedValue("↓", util?.rxBytesPerSec, c.primary)
            Spacer(Modifier.width(12.dp))
            SpeedValue("↑", util?.txBytesPerSec, c.chart2)
        }
        Spacer(Modifier.height(8.dp))
        SpNetworkChart(download = data.network.map { it.rx }, upload = data.network.map { it.tx })
    }
}

@Composable
private fun SpeedValue(arrow: String, bytes: Long?, color: androidx.compose.ui.graphics.Color) {
    val c = SpTheme.colors
    val (n, u) = bytes?.let(SpFormat::speedParts) ?: ("–" to "")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(" $arrow ", style = SpTheme.type.caption, color = c.onSurfaceVariant)
        Text(n, style = SpTheme.type.num.copy(fontSize = SpTheme.type.body.fontSize), color = c.onSurface)
        Text(" $u", style = SpTheme.type.caption, color = c.onSurfaceVariant)
    }
}

@Composable
private fun StorageCard(data: DashboardData, onDisks: () -> Unit) {
    val c = SpTheme.colors
    val storage = data.storage ?: return
    val total = storage.totalBytes
    val used = storage.usedBytes
    val fraction = if (total > 0) used.toFloat() / total else 0f
    val poolHealth = storage.pools.map { it.health }.let { hs ->
        when {
            Health.Error in hs -> Health.Error
            Health.Warning in hs -> Health.Warning
            else -> Health.Ok
        }
    }
    SpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(storage.pools.singleOrNull()?.name ?: "存储空间", style = SpTheme.type.label, color = c.onSurface, modifier = Modifier.weight(1f))
            SpStatusText(poolHealth.toStatus(), poolHealth.label())
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            val (usedN, usedU) = SpFormat.bytesParts(used)
            Text(usedN, style = SpTheme.type.num, color = c.onSurface)
            Text(" $usedU / ${SpFormat.bytes(total)}", style = SpTheme.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp).weight(1f))
            Text("已用 ${(fraction * 100).toInt()}%", style = SpTheme.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
        val storageColor = when {
            fraction >= 0.9f -> c.error
            fraction >= 0.8f -> c.warning
            else -> c.primary
        }
        SpMeter(fraction, cells = 30, cellHeight = 8, color = storageColor)
        if (storage.volumes.size > 1) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                storage.volumes.forEach { v ->
                    Text(
                        "${v.path} · ${v.fsType} · ${SpFormat.bytes(v.usedBytes)} / ${SpFormat.bytes(v.totalBytes)}",
                        style = SpTheme.type.caption,
                        color = c.onSurfaceVariant,
                    )
                }
            }
        }
        if (storage.disks.isNotEmpty()) {
            val warn = storage.disks.filter { it.health == Health.Warning || it.health == Health.Error }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDisks)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Storage, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text("硬盘", style = SpTheme.type.label, color = c.onSurface)
                Text(
                    if (warn.isEmpty()) "${storage.disks.size} 块全部良好" else "${warn.first().name} 需要关注",
                    style = SpTheme.type.caption,
                    color = if (warn.isEmpty()) c.onSurfaceVariant else c.warning,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SpStatusBars(storage.disks.map(Disk::health).map { it.toStatus() })
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun Health.toStatus(): SpStatus = when (this) {
    Health.Ok -> SpStatus.Ok
    Health.Warning -> SpStatus.Warning
    Health.Error -> SpStatus.Error
    Health.Unknown -> SpStatus.Neutral
}

private fun Health.label(): String = when (this) {
    Health.Ok -> "良好"
    Health.Warning -> "警告"
    Health.Error -> "危险"
    Health.Unknown -> "未使用"
}
