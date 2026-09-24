package io.github.duskedge.synopilot.feature.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duskedge.synopilot.data.Alert
import io.github.duskedge.synopilot.data.AlertLevel
import io.github.duskedge.synopilot.data.AlertRule
import io.github.duskedge.synopilot.designsystem.SpFormat
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.SpBanner
import io.github.duskedge.synopilot.designsystem.component.SpBannerTone
import io.github.duskedge.synopilot.designsystem.component.SpButton
import io.github.duskedge.synopilot.designsystem.component.SpButtonSize
import io.github.duskedge.synopilot.designsystem.component.SpButtonVariant
import io.github.duskedge.synopilot.designsystem.component.SpCard
import io.github.duskedge.synopilot.designsystem.component.SpChip
import io.github.duskedge.synopilot.designsystem.component.SpListGroup
import io.github.duskedge.synopilot.designsystem.component.SpListItem
import io.github.duskedge.synopilot.designsystem.component.SpMessage
import io.github.duskedge.synopilot.designsystem.component.SpMessageBus
import io.github.duskedge.synopilot.designsystem.component.SpMeter
import io.github.duskedge.synopilot.designsystem.component.SpSectionHeader
import io.github.duskedge.synopilot.designsystem.component.SpSheet
import io.github.duskedge.synopilot.designsystem.component.SpStatus
import io.github.duskedge.synopilot.designsystem.component.SpSwitch
import io.github.duskedge.synopilot.designsystem.component.SpTag
import io.github.duskedge.synopilot.designsystem.component.SpTextField
import io.github.duskedge.synopilot.designsystem.component.meterColorFor
import io.github.duskedge.synopilot.network.BackupResult
import io.github.duskedge.synopilot.network.BlockedIp
import io.github.duskedge.synopilot.network.PowerTask
import kotlinx.coroutines.flow.Flow
import org.koin.compose.viewmodel.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 系统子页面的入口，由 App 的导航调用 */
@Composable
fun SystemPageScreen(page: SystemPage) {
    when (page) {
        SystemPage.Schedule -> ScheduleScreen()
        SystemPage.Backup -> BackupScreen()
        SystemPage.Monitor -> MonitorScreen()
        SystemPage.Blocked -> BlockedScreen()
        SystemPage.Sessions -> SessionsScreen()
        SystemPage.Certificates -> CertificatesScreen()
        SystemPage.Notifications -> NotificationsScreen()
    }
}

@Composable
private fun Page(messages: Flow<SpMessage>? = null, content: @Composable ColumnScope.() -> Unit) {
    if (messages != null) LaunchedEffect(messages) { messages.collect(SpMessageBus::post) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun <T> LoadBox(load: Load<T>, content: @Composable (T) -> Unit) {
    when (load) {
        Load.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = SpTheme.colors.primary)
        }
        is Load.Failed -> SpBanner("读取失败", message = load.message, tone = SpBannerTone.Warning)
        is Load.Ok -> content(load.value)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = SpTheme.type.caption, color = SpTheme.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun Empty(text: String) {
    Text(text, style = SpTheme.type.bodySmall, color = SpTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp))
}

private val DATE_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val DATE = DateTimeFormatter.ofPattern("yyyy年M月d日")

private fun dateTime(epochSeconds: Long): String =
    if (epochSeconds <= 0) "—" else Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(DATE_TIME)

// ---- 定时开关机 -------------------------------------------------------------

@Composable
private fun ScheduleScreen(vm: ScheduleViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Triple<Boolean, Int?, PowerTask>?>(null) }
    Page(vm.messages) {
        LoadBox(state) { s ->
            listOf(false to s.powerOff, true to s.powerOn).forEach { (on, tasks) ->
                SpSectionHeader(if (on) "开机" else "关机")
                SpListGroup {
                    tasks.forEachIndexed { i, t ->
                        SpListItem(
                            title = "%02d:%02d".format(t.hour, t.minute),
                            subtitle = weekdaysText(t.weekdays),
                            icon = Icons.Outlined.PowerSettingsNew,
                            divider = i > 0,
                            onClick = { editing = Triple(on, i, t) },
                            trailing = { SpSwitch(t.enabled, { vm.toggle(on, i, it) }, enabled = !saving) },
                        )
                    }
                    SpListItem(
                        if (on) "添加开机时间" else "添加关机时间",
                        icon = Icons.Outlined.Add,
                        divider = tasks.isNotEmpty(),
                        onClick = { editing = Triple(on, null, PowerTask(true, if (on) 8 else 1, if (on) 0 else 30, (0..6).toSet())) },
                    )
                }
            }
            Hint("关机前 DSM 会先停止服务。定时开机需要 NAS 支持并且接着电源。")
        }
    }
    editing?.let { (on, index, task) ->
        TaskSheet(on, index, task, saving, onDismiss = { editing = null }, onSave = { vm.put(on, index, it) { editing = null } }, onDelete = {
            index?.let { vm.remove(on, it) { editing = null } }
        })
    }
}

@Composable
private fun TaskSheet(on: Boolean, index: Int?, initial: PowerTask, saving: Boolean, onDismiss: () -> Unit, onSave: (PowerTask) -> Unit, onDelete: () -> Unit) {
    var hour by remember { mutableStateOf("%02d".format(initial.hour)) }
    var minute by remember { mutableStateOf("%02d".format(initial.minute)) }
    var days by remember { mutableStateOf(initial.weekdays) }
    val h = hour.toIntOrNull()?.takeIf { it in 0..23 }
    val m = minute.toIntOrNull()?.takeIf { it in 0..59 }
    SpSheet(title = if (index == null) (if (on) "添加开机时间" else "添加关机时间") else (if (on) "开机时间" else "关机时间"), onDismiss = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpTextField(hour, { hour = it.take(2) }, label = "时", modifier = Modifier.weight(1f), isError = h == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            SpTextField(minute, { minute = it.take(2) }, label = "分", modifier = Modifier.weight(1f), isError = m == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1, 2, 3, 4, 5, 6, 0).forEach { d ->
                SpChip(listOf("日", "一", "二", "三", "四", "五", "六")[d], selected = d in days, onClick = { days = if (d in days) days - d else days + d })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SpButton("每天", onClick = { days = (0..6).toSet() }, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
            SpButton("工作日", onClick = { days = setOf(1, 2, 3, 4, 5) }, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
            SpButton("周末", onClick = { days = setOf(0, 6) }, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
        }
        SpButton(
            "保存",
            onClick = { onSave(initial.copy(hour = h!!, minute = m!!, weekdays = days)) },
            size = SpButtonSize.Block,
            modifier = Modifier.fillMaxWidth(),
            enabled = h != null && m != null && days.isNotEmpty() && !saving,
        )
        if (index != null) SpButton("删除", onClick = onDelete, variant = SpButtonVariant.Text, modifier = Modifier.fillMaxWidth(), enabled = !saving)
    }
}

// ---- 备份 ------------------------------------------------------------------

@Composable
private fun BackupScreen(vm: BackupViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Page(vm.messages) {
        LoadBox(state) { tasks ->
            if (tasks.isEmpty()) Empty("没有 Hyper Backup 任务")
            tasks.forEach { t ->
                SpCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Restore, contentDescription = null, tint = SpTheme.colors.primary, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = SpTheme.type.label.copy(fontSize = SpTheme.type.body.fontSize), color = SpTheme.colors.onSurface)
                            Text(targetName(t.target), style = SpTheme.type.caption, color = SpTheme.colors.onSurfaceVariant)
                        }
                        when (t.result) {
                            BackupResult.Success -> SpTag("成功", status = SpStatus.Ok)
                            BackupResult.Failed -> SpTag("失败", status = SpStatus.Error)
                            BackupResult.Running -> SpTag("备份中")
                            BackupResult.Partial -> SpTag("部分完成", status = SpStatus.Warning)
                            BackupResult.Never -> SpTag("未备份", status = SpStatus.Neutral)
                            BackupResult.Unknown -> Unit
                        }
                    }
                    val progress = t.progress
                    if (t.result == BackupResult.Running && progress != null) {
                        SpMeter(progress / 100f, modifier = Modifier.padding(top = 10.dp), cells = 32, cellHeight = 5)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "上次 ${dateTime(t.lastTime)} · 下次 ${dateTime(t.nextTime)}",
                            style = SpTheme.type.caption,
                            color = SpTheme.colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        SpButton("立即备份", onClick = { vm.backupNow(t) }, variant = SpButtonVariant.Text, size = SpButtonSize.Small, enabled = t.result != BackupResult.Running)
                    }
                }
            }
            Hint("恢复、完整性检查等操作请在 DSM 的 Hyper Backup 里进行。")
        }
    }
}

private fun targetName(type: String) = when (type.lowercase()) {
    "cloud", "c2" -> "云端"
    "local", "usb", "local_usb" -> "本地 / USB 硬盘"
    "remote", "rsync", "hyperbackup_vault" -> "远程 NAS"
    "" -> "Hyper Backup"
    else -> type
}

// ---- 资源监控 ----------------------------------------------------------------

@Composable
private fun MonitorScreen(vm: MonitorViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Page {
        SpSectionHeader("磁盘读写")
        LoadBox(state.detail) { d ->
            SpCard {
                val rows = d.disks.ifEmpty { d.volumes }
                if (rows.isEmpty()) Text("没有数据", style = SpTheme.type.caption, color = SpTheme.colors.onSurfaceVariant)
                rows.forEachIndexed { i, io ->
                    Column(Modifier.padding(top = if (i > 0) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(io.name, style = SpTheme.type.bodySmall, color = SpTheme.colors.onSurface, modifier = Modifier.weight(1f))
                            Text(
                                "读 ${SpFormat.speed(io.readBytesPerSec)} · 写 ${SpFormat.speed(io.writeBytesPerSec)}",
                                style = SpTheme.type.caption.copy(fontFamily = SpTheme.type.num.fontFamily),
                                color = SpTheme.colors.onSurfaceVariant,
                            )
                        }
                        SpMeter(io.utilization / 100f, cells = 24, cellHeight = 5, color = meterColorFor(io.utilization / 100f))
                    }
                }
            }
        }
        SpSectionHeader("进程（按 CPU 排序）")
        LoadBox(state.processes) { list ->
            SpListGroup {
                list.forEachIndexed { i, p ->
                    SpListItem(
                        title = p.name.substringAfterLast('/').take(40),
                        subtitle = "PID ${p.pid} · 内存 ${SpFormat.bytes(p.memoryBytes)}",
                        divider = i > 0,
                        trailing = {
                            Text("%.1f%%".format(p.cpuPercent), style = SpTheme.type.label.copy(fontFamily = SpTheme.type.num.fontFamily), color = SpTheme.colors.onSurface)
                        },
                    )
                }
            }
        }
        Hint("每 3 秒刷新")
    }
}

// ---- 安全 ------------------------------------------------------------------

@Composable
private fun BlockedScreen(vm: SecurityViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<BlockedIp?>(null) }
    Page(vm.messages) {
        LoadBox(state.blocked) { list ->
            if (list.isEmpty()) {
                Empty("没有被封锁的 IP")
            } else {
                SpListGroup {
                    list.forEachIndexed { i, b ->
                        SpListItem(
                            title = b.ip,
                            subtitle = "封锁于 ${dateTime(b.recorded)}" + (b.expires?.let { " · ${dateTime(it)} 解除" } ?: " · 永久"),
                            icon = Icons.Outlined.Shield,
                            divider = i > 0,
                            trailing = { SpButton("解除", onClick = { confirm = b }, variant = SpButtonVariant.Text, size = SpButtonSize.Small) },
                        )
                    }
                }
            }
            Hint("多次登录失败的 IP 会被 DSM 自动封锁。封锁规则在 DSM「控制面板 → 安全性 → 保护」里设置。")
        }
    }
    confirm?.let { b ->
        SpSheet(title = "解除封锁 ${b.ip}？", subtitle = "如果不认识这个地址，建议保持封锁", onDismiss = { confirm = null }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpButton("取消", onClick = { confirm = null }, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f))
                SpButton("解除", onClick = { vm.unblock(b); confirm = null }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SessionsScreen(vm: SecurityViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Page(vm.messages) {
        LoadBox(state.sessions) { list ->
            if (list.isEmpty()) Empty("没有连接")
            SpListGroup {
                list.forEachIndexed { i, s ->
                    SpListItem(
                        title = "${s.user} · ${s.from}",
                        subtitle = listOf(s.description.ifBlank { s.type }, s.since).filter { it.isNotBlank() }.joinToString(" · "),
                        icon = Icons.Outlined.Devices,
                        divider = i > 0,
                        trailing = if (s.canKick) {
                            { SpButton("断开", onClick = { vm.kick(s) }, variant = SpButtonVariant.Text, size = SpButtonSize.Small) }
                        } else {
                            null
                        },
                    )
                }
            }
            Hint("包括网页、App、SMB 等连接。断开后对方需要重新登录。")
        }
    }
}

@Composable
private fun CertificatesScreen(vm: SecurityViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Page(vm.messages) {
        LoadBox(state.certificates) { list ->
            if (list.isEmpty()) Empty("没有证书")
            list.forEach { c ->
                val days = c.validTill?.let { (it - System.currentTimeMillis() / 1000) / 86_400 }
                SpCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = SpTheme.colors.primary, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.subject, style = SpTheme.type.label.copy(fontSize = SpTheme.type.body.fontSize), color = SpTheme.colors.onSurface)
                            Text("颁发者 ${c.issuer}", style = SpTheme.type.caption, color = SpTheme.colors.onSurfaceVariant)
                        }
                        if (c.isDefault) SpTag("默认")
                    }
                    val till = c.validTill?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(DATE) } ?: "未知"
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$till 到期", style = SpTheme.type.caption, color = SpTheme.colors.onSurfaceVariant)
                        when {
                            days == null -> Unit
                            days < 0 -> SpTag("已过期", status = SpStatus.Error)
                            days < 14 -> SpTag("$days 天后到期", status = SpStatus.Warning)
                            else -> SpTag("$days 天", status = SpStatus.Ok)
                        }
                    }
                    if (c.alternateNames.isNotEmpty()) {
                        Text(c.alternateNames.joinToString("、"), style = SpTheme.type.caption, color = SpTheme.colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Hint("Let's Encrypt 证书会在到期前自动续期。")
        }
    }
}

// ---- 通知中心 ----------------------------------------------------------------

@Composable
private fun NotificationsScreen(vm: NotificationsViewModel = koinViewModel()) {
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    // 离开页面时标记为已读
    DisposableEffect(Unit) { onDispose { vm.markAllRead() } }
    Page(vm.messages) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpButton(if (checking) "正在检查…" else "立即检查", onClick = vm::checkNow, variant = SpButtonVariant.Tonal, enabled = !checking, modifier = Modifier.weight(1f))
            SpButton("清空", onClick = vm::clear, variant = SpButtonVariant.Tonal, enabled = alerts.isNotEmpty(), modifier = Modifier.weight(1f))
        }
        if (alerts.isEmpty()) {
            Empty("没有告警")
        } else {
            SpListGroup {
                alerts.forEachIndexed { i, a -> AlertRow(a, divider = i > 0) }
            }
        }
        SpSectionHeader("告警规则")
        SpListGroup {
            SpListItem(
                "后台检查",
                subtitle = "每 15 分钟检查一次当前 NAS，有新问题时发通知",
                trailing = { SpSwitch(settings.alertsEnabled, vm::setEnabled) },
            )
            AlertRule.entries.forEach { rule ->
                SpListItem(
                    rule.label,
                    subtitle = rule.description,
                    divider = true,
                    trailing = { SpSwitch(rule.name !in settings.disabledAlertRules, { vm.setRule(rule, it) }, enabled = settings.alertsEnabled) },
                )
            }
        }
        Hint("同一个问题只提醒一次；问题解决后再次出现会重新提醒。")
    }
}

@Composable
private fun AlertRow(a: Alert, divider: Boolean) {
    val c = SpTheme.colors
    val (icon, tint) = when (a.level) {
        AlertLevel.Error -> Icons.Outlined.ErrorOutline to c.error
        AlertLevel.Warning -> Icons.Outlined.WarningAmber to c.warning
        AlertLevel.Info -> Icons.Outlined.Info to c.primary
    }
    SpListItem(
        title = a.title,
        subtitle = "${a.deviceName} · ${SpFormat.ago(a.time)} · ${a.body}",
        icon = icon,
        divider = divider,
        titleColor = if (a.read) c.onSurface else tint,
        trailing = if (!a.read) {
            { Box(Modifier.size(8.dp).background(c.error, CircleShape)) }
        } else {
            null
        },
    )
}
