package io.github.duskedge.synopilot.feature.system

import androidx.annotation.Keep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.SpBanner
import io.github.duskedge.synopilot.designsystem.component.SpBannerTone
import io.github.duskedge.synopilot.designsystem.component.SpButton
import io.github.duskedge.synopilot.designsystem.component.SpButtonSize
import io.github.duskedge.synopilot.designsystem.component.SpButtonVariant
import io.github.duskedge.synopilot.designsystem.component.SpKeyValue
import io.github.duskedge.synopilot.designsystem.component.SpListGroup
import io.github.duskedge.synopilot.designsystem.component.SpListItem
import io.github.duskedge.synopilot.designsystem.component.SpMessageBus
import io.github.duskedge.synopilot.designsystem.component.SpSectionHeader
import io.github.duskedge.synopilot.designsystem.component.SpSheet
import io.github.duskedge.synopilot.designsystem.component.SpStatus
import io.github.duskedge.synopilot.designsystem.component.SpTag
import io.github.duskedge.synopilot.network.BackupResult
import io.github.duskedge.synopilot.network.DsmUpdate
import io.github.duskedge.synopilot.network.PowerSchedule
import io.github.duskedge.synopilot.security.Identity
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** 用作导航参数，需要 @Keep 防止 R8 混淆枚举名 */
@Keep
enum class SystemPage(val title: String) {
    Schedule("定时开关机"),
    Backup("备份"),
    Monitor("资源监控"),
    Blocked("自动封锁"),
    Sessions("登录的设备"),
    Certificates("HTTPS 证书"),
    Notifications("通知"),
}

@Composable
private fun Chevron() = Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = SpTheme.colors.outline, modifier = Modifier.size(20.dp))

/** 设置页里的「电源 / 系统 / 安全 / 通知」几组入口。提示发到 [SpMessageBus]。 */
@Composable
fun SystemSettingsSections(
    onOpen: (SystemPage) -> Unit,
    viewModel: SystemViewModel = koinViewModel(),
) {
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val unread by viewModel.unreadAlerts.collectAsStateWithLifecycle()
    var power by remember { mutableStateOf<PowerAction?>(null) }
    var showUpdate by remember { mutableStateOf(false) }
    var showUps by remember { mutableStateOf(false) }
    val device = state.deviceOrNull ?: return
    val connected = state is ConnectionState.Connected
    LaunchedEffect(viewModel) { viewModel.messages.collect(SpMessageBus::post) }
    LaunchedEffect(Unit) { viewModel.refreshIfStale() }

    SpSectionHeader("电源")
    SpListGroup {
        SpListItem("重启", icon = Icons.Outlined.RestartAlt, subtitle = "一般需要 3–5 分钟", onClick = { power = PowerAction.Reboot }.takeIf { connected })
        SpListItem(
            "关机",
            icon = Icons.Outlined.PowerSettingsNew,
            titleColor = SpTheme.colors.error,
            subtitle = "关机后只能网络唤醒或按电源键开机",
            divider = true,
            onClick = { power = PowerAction.Shutdown }.takeIf { connected },
        )
        SpListItem(
            "定时开关机",
            icon = Icons.Outlined.Schedule,
            subtitle = summary.schedule.valueOrNull?.let { scheduleSummary(it) } ?: loadText(summary.schedule, connected),
            divider = true,
            onClick = { onOpen(SystemPage.Schedule) }.takeIf { connected },
            trailing = { Chevron() },
        )
        SpListItem(
            "网络唤醒",
            icon = Icons.Outlined.Bolt,
            subtitle = device.macAddresses.firstOrNull()?.let { "MAC $it · 需要和 NAS 在同一局域网" } ?: "连接成功一次后才能使用",
            divider = true,
            trailing = { SpButton("唤醒", onClick = viewModel::wake, size = SpButtonSize.Small, variant = SpButtonVariant.Tonal, enabled = device.macAddresses.isNotEmpty()) },
        )
    }

    SpSectionHeader("系统")
    SpListGroup {
        val update = summary.update
        SpListItem(
            "DSM 更新",
            icon = Icons.Outlined.SystemUpdate,
            subtitle = when (update) {
                null -> "点按检查"
                Load.Loading -> "正在检查…"
                is Load.Failed -> update.message
                is Load.Ok -> if (update.value.available) "${update.value.version ?: "新版本"} 可以安装" else "已是最新版本"
            },
            onClick = { showUpdate = true; if (summary.update == null) viewModel.checkUpdate() }.takeIf { connected },
            trailing = { if ((update as? Load.Ok)?.value?.available == true) SpTag("新") },
        )
        val backups = summary.backups.valueOrNull
        val failed = backups?.count { it.result == BackupResult.Failed } ?: 0
        SpListItem(
            "备份",
            icon = Icons.Outlined.Restore,
            subtitle = when {
                backups == null -> loadText(summary.backups, connected, "没有安装 Hyper Backup")
                backups.isEmpty() -> "没有备份任务"
                failed > 0 -> "Hyper Backup $failed 个任务失败"
                else -> "Hyper Backup ${backups.size} 个任务"
            },
            divider = true,
            onClick = { onOpen(SystemPage.Backup) }.takeIf { connected },
            trailing = { Row(verticalAlignment = Alignment.CenterVertically) { if (failed > 0) SpTag("$failed", status = SpStatus.Warning); Chevron() } },
        )
        SpListItem("资源监控", icon = Icons.Outlined.Monitor, subtitle = "进程、磁盘读写", divider = true, onClick = { onOpen(SystemPage.Monitor) }.takeIf { connected }, trailing = { Chevron() })
        val ups = summary.ups.valueOrNull
        SpListItem(
            "UPS",
            icon = Icons.Outlined.BatteryChargingFull,
            subtitle = when {
                ups == null -> loadText(summary.ups, connected, "没有连接 UPS")
                !ups.enabled -> "没有启用 UPS 支持"
                ups.onBattery -> "电池供电 · 电量 ${ups.chargePercent ?: "?"}%"
                else -> "${ups.model.ifBlank { "UPS" }} · 市电供电"
            },
            divider = true,
            onClick = { showUps = true }.takeIf { ups != null },
        )
    }

    SpSectionHeader("安全")
    SpListGroup {
        val blocked = summary.blocked.valueOrNull
        val today = blocked?.count { System.currentTimeMillis() / 1000 - it.recorded < 86_400 } ?: 0
        SpListItem(
            "自动封锁",
            icon = Icons.Outlined.Shield,
            subtitle = when {
                blocked == null -> loadText(summary.blocked, connected)
                blocked.isEmpty() -> "没有被封锁的 IP"
                today > 0 -> "今天封锁了 $today 个 IP，共 ${blocked.size} 个"
                else -> "共封锁 ${blocked.size} 个 IP"
            },
            onClick = { onOpen(SystemPage.Blocked) }.takeIf { connected },
            trailing = { Chevron() },
        )
        val sessions = summary.sessions.valueOrNull
        SpListItem(
            "登录的设备",
            icon = Icons.Outlined.Devices,
            subtitle = sessions?.let { "${it.size} 个连接" } ?: loadText(summary.sessions, connected),
            divider = true,
            onClick = { onOpen(SystemPage.Sessions) }.takeIf { connected },
            trailing = { Chevron() },
        )
        val cert = summary.certificates.valueOrNull?.let { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull() }
        val days = cert?.validTill?.let { (it - System.currentTimeMillis() / 1000) / 86_400 }
        SpListItem(
            "HTTPS 证书",
            icon = Icons.Outlined.Lock,
            subtitle = when {
                cert == null -> loadText(summary.certificates, connected, "没有证书")
                days == null -> cert.subject
                days < 0 -> "${cert.subject} · 已过期"
                else -> "${cert.subject} · $days 天后到期"
            },
            divider = true,
            onClick = { onOpen(SystemPage.Certificates) }.takeIf { connected },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (days != null && days < 14) SpTag(if (days < 0) "已过期" else "即将到期", status = SpStatus.Warning)
                    Chevron()
                }
            },
        )
    }

    SpSectionHeader("通知")
    SpListGroup {
        SpListItem(
            "告警通知",
            icon = Icons.Outlined.Notifications,
            subtitle = if (unread > 0) "$unread 条未读" else "容器退出、硬盘、空间、UPS、备份、证书",
            onClick = { onOpen(SystemPage.Notifications) },
            trailing = { Row(verticalAlignment = Alignment.CenterVertically) { if (unread > 0) SpTag("$unread", status = SpStatus.Error); Chevron() } },
        )
    }

    power?.let { action -> PowerSheet(action, viewModel, onDismiss = { power = null }) }
    if (showUpdate) UpdateSheet(summary.update, onCheck = viewModel::checkUpdate, onDismiss = { showUpdate = false })
    if (showUps) summary.ups.valueOrNull?.let { ups ->
        SpSheet(title = "UPS", subtitle = ups.model.ifBlank { null }, onDismiss = { showUps = false }) {
            SpKeyValue(
                listOfNotNull(
                    "状态" to if (ups.onBattery) "电池供电" else "市电供电",
                    ups.chargePercent?.let { "电量" to "$it%" },
                    ups.runtimeSec?.let { "可续航" to "${it / 60} 分钟" },
                ),
                monoValues = false,
            )
            Text("断电后的关机策略在 DSM「控制面板 → 硬件和电源 → UPS」里设置。", style = SpTheme.type.caption, color = SpTheme.colors.onSurfaceVariant)
        }
    }
}

private fun loadText(load: Load<*>, connected: Boolean, failedHint: String? = null): String = when {
    !connected -> "未连接"
    load is Load.Failed -> failedHint ?: load.message
    else -> "正在读取…"
}

private val WEEK = listOf("日", "一", "二", "三", "四", "五", "六")

internal fun weekdaysText(days: Set<Int>): String = when {
    days.size == 7 -> "每天"
    days == setOf(1, 2, 3, 4, 5) -> "工作日"
    days == setOf(0, 6) -> "周末"
    days.isEmpty() -> "不重复"
    else -> "周" + days.sortedBy { (it + 6) % 7 }.joinToString("、") { WEEK[it] }
}

internal fun scheduleSummary(s: PowerSchedule): String {
    val on = s.powerOn.filter { it.enabled }
    val off = s.powerOff.filter { it.enabled }
    if (on.isEmpty() && off.isEmpty()) return "没有开启"
    return listOfNotNull(
        off.firstOrNull()?.let { "${weekdaysText(it.weekdays)} %02d:%02d 关机".format(it.hour, it.minute) },
        on.firstOrNull()?.let { "${weekdaysText(it.weekdays)} %02d:%02d 开机".format(it.hour, it.minute) },
    ).joinToString(" · ")
}

@Composable
private fun PowerSheet(action: PowerAction, vm: SystemViewModel, onDismiss: () -> Unit) {
    val impact by vm.impact.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(action) { vm.loadImpact() }
    val reboot = action == PowerAction.Reboot
    SpSheet(title = if (reboot) "重启 NAS？" else "关闭 NAS？", onDismiss = onDismiss) {
        val lines = buildList {
            impact?.runningContainers?.let { if (it > 0) add("$it 个正在运行的容器会停止") }
            impact?.sessions?.let { if (it > 0) add("$it 个连接（网页、SMB、App）会断开") }
            add("正在进行的下载、备份和文件传输会中断")
            if (!reboot) add("关机后只能网络唤醒或按电源键开机")
        }
        SpBanner(
            if (reboot) "重启期间 NAS 不可用，一般需要 3–5 分钟" else "关机后 NAS 上的所有服务都会停止",
            message = lines.joinToString("\n") { "· $it" },
            tone = if (reboot) SpBannerTone.Warning else SpBannerTone.Error,
        )
        if (impact?.runningContainers == null && impact?.sessions == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = SpTheme.colors.primary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpButton("取消", onClick = onDismiss, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f))
            SpButton(
                if (reboot) "验证并重启" else "验证并关机",
                variant = SpButtonVariant.Danger,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    busy = true
                    scope.launch {
                        val r = Identity.confirm(context, if (reboot) "确认重启 NAS" else "确认关闭 NAS", "验证身份后继续")
                        busy = false
                        if (r != Identity.Result.Cancelled) {
                            onDismiss()
                            vm.power(action)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun UpdateSheet(update: Load<DsmUpdate>?, onCheck: () -> Unit, onDismiss: () -> Unit) {
    SpSheet(title = "DSM 更新", onDismiss = onDismiss) {
        when (update) {
            null, Load.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = SpTheme.colors.primary)
                Text("正在向群晖服务器查询…", style = SpTheme.type.bodySmall, color = SpTheme.colors.onSurfaceVariant)
            }
            is Load.Failed -> SpBanner("检查失败", message = update.message, tone = SpBannerTone.Warning)
            is Load.Ok -> if (update.value.available) {
                SpBanner(
                    "${update.value.version ?: "新版本"} 可以安装",
                    message = "为了安全，更新请在 DSM 网页端「控制面板 → 更新和还原」里安装。" + if (update.value.needsReboot) "安装后需要重启。" else "",
                    icon = Icons.Outlined.SystemUpdate,
                )
            } else {
                SpBanner("已是最新版本", icon = Icons.Outlined.SystemUpdate)
            }
        }
        SpButton("重新检查", onClick = onCheck, variant = SpButtonVariant.Tonal, modifier = Modifier.fillMaxWidth(), enabled = update != Load.Loading)
    }
}
