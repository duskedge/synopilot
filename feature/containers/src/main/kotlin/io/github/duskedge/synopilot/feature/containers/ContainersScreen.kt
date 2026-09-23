package io.github.duskedge.synopilot.feature.containers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.designsystem.SpFormat
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.SpBanner
import io.github.duskedge.synopilot.designsystem.component.SpBannerTone
import io.github.duskedge.synopilot.designsystem.component.SpButton
import io.github.duskedge.synopilot.designsystem.component.SpButtonSize
import io.github.duskedge.synopilot.designsystem.component.SpButtonVariant
import io.github.duskedge.synopilot.designsystem.component.SpCard
import io.github.duskedge.synopilot.designsystem.component.SpChipRow
import io.github.duskedge.synopilot.designsystem.component.SpIconButton
import io.github.duskedge.synopilot.designsystem.component.SpIconButtonStyle
import io.github.duskedge.synopilot.designsystem.component.SpKeyValue
import io.github.duskedge.synopilot.designsystem.component.SpMessage
import io.github.duskedge.synopilot.designsystem.component.SpMessageHost
import io.github.duskedge.synopilot.designsystem.component.SpMeter
import io.github.duskedge.synopilot.designsystem.component.SpPlaceholder
import io.github.duskedge.synopilot.designsystem.component.SpSegmented
import io.github.duskedge.synopilot.designsystem.component.SpSheet
import io.github.duskedge.synopilot.designsystem.component.SpStatus
import io.github.duskedge.synopilot.designsystem.component.SpSwitch
import io.github.duskedge.synopilot.designsystem.component.SpTabs
import io.github.duskedge.synopilot.designsystem.component.SpTag
import io.github.duskedge.synopilot.designsystem.component.SpTextField
import io.github.duskedge.synopilot.designsystem.component.meterColorFor
import io.github.duskedge.synopilot.network.ComposeProject
import io.github.duskedge.synopilot.network.Container
import io.github.duskedge.synopilot.network.ContainerLogLine
import io.github.duskedge.synopilot.network.ContainerState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import org.koin.compose.viewmodel.koinViewModel
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ContainersScreen(viewModel: ContainersViewModel = koinViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    var confirmBuild by remember { mutableStateOf<ComposeProject?>(null) }
    val localMessages = remember { Channel<SpMessage>(Channel.BUFFERED) }
    val messages = remember { merge(viewModel.messages, localMessages.receiveAsFlow()) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "head") { Header(ui, viewModel) }
            when {
                ui.connection !is ConnectionState.Connected && ui.data.containers.isEmpty() -> item(key = "offline") { NotConnected(ui.connection) }
                !ui.data.loaded -> item(key = "loading") { Loading() }
                ui.data.unavailable != null -> item(key = "na") {
                    SpPlaceholder("容器不可用", ui.data.unavailable!!, Icons.Outlined.ViewInAr)
                }
                ui.view == ContainersView.Containers -> {
                    val list = ui.visible
                    if (list.isEmpty()) {
                        item(key = "empty") { Empty(if (ui.data.containers.isEmpty()) "这台 NAS 上还没有容器" else "没有符合条件的容器") }
                    }
                    items(list, key = { it.name }) { c ->
                        ContainerCard(
                            c,
                            busy = c.name in ui.busy,
                            onToggle = { viewModel.toggle(c) },
                            onOpen = { viewModel.openDetail(c.name) },
                            onRestart = { if (c.state == ContainerState.Running) viewModel.restart(c.name) else viewModel.start(c.name) },
                        )
                    }
                }
                else -> {
                    if (ui.data.projects.isEmpty()) item(key = "empty-p") { Empty("没有 Compose 项目") }
                    items(ui.data.projects, key = { "p:" + it.id }) { p ->
                        ProjectCard(
                            p,
                            containers = ui.data.containers,
                            busy = "project:${p.id}" in ui.busy,
                            onOpen = viewModel::openDetail,
                            onStart = { viewModel.projectAction(p, "start") },
                            onStop = { viewModel.projectAction(p, "stop") },
                            onBuild = { confirmBuild = p },
                        )
                    }
                }
            }
        }
        SpMessageHost(messages)
    }

    detail?.let { d ->
        val container = ui.data.containers.firstOrNull { it.name == d.name }
        ContainerSheet(
            d,
            container,
            busy = d.name in ui.busy,
            onDismiss = viewModel::closeDetail,
            onFollow = viewModel::setFollowing,
            onStart = { viewModel.start(d.name) },
            onStop = { viewModel.stop(d.name); viewModel.closeDetail() },
            onRestart = { viewModel.restart(d.name) },
            onCopied = { localMessages.trySend(SpMessage("已复制日志")) },
        )
    }
    confirmBuild?.let { p ->
        SpSheet(title = "更新 ${p.name}？", subtitle = "会拉取最新镜像并重建项目里的容器，期间服务会短暂中断", onDismiss = { confirmBuild = null }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpButton("取消", onClick = { confirmBuild = null }, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f))
                SpButton("更新并重建", onClick = { viewModel.projectAction(p, "build"); confirmBuild = null }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Header(ui: ContainersUi, vm: ContainersViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SpSegmented(
            options = listOf(ContainersView.Containers to "容器", ContainersView.Projects to "Compose 项目"),
            selected = ui.view,
            onSelect = vm::setView,
        )
        if (ui.view == ContainersView.Containers && ui.data.containers.isNotEmpty()) {
            SpChipRow(
                options = listOf(
                    ContainerFilter.All to "全部 ${ui.count(ContainerFilter.All)}",
                    ContainerFilter.Running to "运行中 ${ui.count(ContainerFilter.Running)}",
                    ContainerFilter.Exited to "已退出 ${ui.count(ContainerFilter.Exited)}",
                    ContainerFilter.Stopped to "已停止 ${ui.count(ContainerFilter.Stopped)}",
                ),
                selected = ui.filter,
                onSelect = vm::setFilter,
            )
            val exited = ui.count(ContainerFilter.Exited)
            if (exited > 0) {
                SpBanner(
                    "$exited 个容器异常退出",
                    icon = Icons.Outlined.ErrorOutline,
                    tone = SpBannerTone.Error,
                    actions = {
                        SpButton(
                            if ("__exited" in ui.busy) "正在启动…" else "全部重新启动",
                            onClick = vm::restartExited,
                            size = SpButtonSize.Small,
                            variant = SpButtonVariant.Danger,
                            enabled = "__exited" !in ui.busy,
                        )
                    },
                )
            }
        }
        ui.data.error?.let { SpBanner("刷新失败", message = it, icon = Icons.Outlined.ErrorOutline, tone = SpBannerTone.Warning) }
    }
}

@Composable
private fun NotConnected(state: ConnectionState) {
    val msg = when (state) {
        is ConnectionState.Connecting -> "正在连接 ${state.device.name}…"
        is ConnectionState.Failed -> state.reason.message
        else -> "还没有连接到 NAS"
    }
    SpBanner("未连接", message = msg, icon = Icons.Outlined.CloudOff, tone = SpBannerTone.Warning)
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = SpTheme.colors.primary)
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        style = SpTheme.type.bodySmall,
        color = SpTheme.colors.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        textAlign = TextAlign.Center,
    )
}

/** 头像：镜像名首字母 + 按名字固定的颜色 */
@Composable
private fun Avatar(name: String, image: String, dim: Boolean) {
    val palette = listOf(0xFF0B7EE0, 0xFF0E9F6E, 0xFF7A5AF8, 0xFFD9480F, 0xFF0891B2, 0xFFC026D3, 0xFF4B5563, 0xFF65A30D)
    val base = image.substringAfterLast('/').substringBefore(':').ifBlank { name }
    val color = Color(palette[(base.hashCode() and 0x7fffffff) % palette.size])
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (dim) SpTheme.colors.surfaceHigh else color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            base.take(1).uppercase(),
            style = SpTheme.type.num.copy(fontSize = 16.sp),
            color = if (dim) SpTheme.colors.onSurfaceVariant else Color.White,
        )
    }
}

@Composable
private fun StateTag(c: Container) {
    when (c.state) {
        ContainerState.Running -> SpTag("运行中", status = SpStatus.Ok)
        ContainerState.Restarting -> SpTag("重启中", status = SpStatus.Warning)
        ContainerState.Paused -> SpTag("已暂停", status = SpStatus.Warning)
        ContainerState.Exited -> SpTag("退出 ${c.exitCode ?: ""}".trim(), status = SpStatus.Error)
        ContainerState.Stopped -> SpTag("已停止", status = SpStatus.Neutral)
        ContainerState.Unknown -> SpTag("未知", status = SpStatus.Neutral)
    }
}

@Composable
private fun ContainerCard(c: Container, busy: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, onRestart: () -> Unit) {
    val colors = SpTheme.colors
    val on = c.state == ContainerState.Running || c.state == ContainerState.Restarting
    SpCard(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(c.name, c.image, dim = !on)
            Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
                Text(c.name, style = SpTheme.type.title.copy(fontSize = 15.sp), color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.image, style = SpTheme.type.caption, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primary)
            } else {
                SpSwitch(checked = on, onCheckedChange = { onToggle() }, enabled = c.state != ContainerState.Restarting)
            }
        }
        if (on) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val cpu = c.cpuPercent
                Metric("CPU", cpu?.let { "%.1f%%".format(it) } ?: "—", ((cpu ?: 0.0) / 100).toFloat(), Modifier.weight(1f))
                val mem = c.memoryBytes
                val limit = c.memoryLimitBytes
                val memText = when {
                    mem == null -> "—"
                    limit != null -> "${SpFormat.bytes(mem)} / ${SpFormat.bytes(limit)}"
                    else -> SpFormat.bytes(mem)
                }
                val memFraction = when {
                    mem != null && limit != null -> mem.toFloat() / limit
                    else -> ((c.memoryPercent ?: 0.0) / 100).toFloat()
                }
                Metric("内存", memText, memFraction, Modifier.weight(1f))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateTag(c)
                Text(ports(c).ifBlank { c.statusText }, style = SpTheme.type.caption, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SpButton("日志", onClick = onOpen, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
            if (c.state == ContainerState.Exited || c.state == ContainerState.Running) {
                SpButton(if (c.state == ContainerState.Exited) "重新启动" else "重启", onClick = onRestart, variant = SpButtonVariant.Text, size = SpButtonSize.Small, enabled = !busy)
            }
        }
    }
}

private fun ports(c: Container): String =
    c.ports.filter { it.hostPort != null }.distinctBy { it.hostPort }.take(3).joinToString(" · ") { ":${it.hostPort}" }

@Composable
private fun Metric(label: String, value: String, fraction: Float, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = SpTheme.type.caption, color = SpTheme.colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = SpTheme.type.caption.copy(fontFamily = SpTheme.type.num.fontFamily), color = SpTheme.colors.onSurface, maxLines = 1)
        }
        SpMeter(fraction, cells = 14, cellHeight = 5, color = meterColorFor(fraction))
    }
}

@Composable
private fun ProjectCard(
    p: ComposeProject,
    containers: List<Container>,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBuild: () -> Unit,
) {
    val colors = SpTheme.colors
    val members = p.containerNames.map { n -> n to containers.firstOrNull { it.name == n } }
    val running = members.count { it.second?.state == ContainerState.Running }
    val anyExited = members.any { it.second?.state == ContainerState.Exited }
    SpCard(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Layers, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(p.name, style = SpTheme.type.title.copy(fontSize = 15.sp), color = colors.onSurface)
                Text(p.path, style = SpTheme.type.caption, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primary)
                anyExited -> SpTag("有容器退出", status = SpStatus.Error)
                members.isNotEmpty() && running == members.size -> SpTag("全部运行", status = SpStatus.Ok)
                running > 0 -> SpTag("部分运行", status = SpStatus.Warning)
                else -> SpTag(if (p.status.equals("running", true)) "运行中" else "已停止")
            }
        }
        if (members.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp)) {
                members.forEach { (name, c) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpen(name) }
                            .heightIn(min = 36.dp)
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, style = SpTheme.type.bodySmall, color = colors.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val (text, color) = when (c?.state) {
                            ContainerState.Running -> (c.cpuPercent?.let { "%.1f%%".format(it) + (c.memoryBytes?.let { m -> " · " + SpFormat.bytes(m) } ?: "") } ?: "运行中") to colors.onSurfaceVariant
                            ContainerState.Exited -> "已退出" to colors.error
                            null -> "未创建" to colors.onSurfaceVariant
                            else -> "已停止" to colors.onSurfaceVariant
                        }
                        Text(text, style = SpTheme.type.caption, color = color)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            if (running < members.size || members.isEmpty()) SpButton("启动", onClick = onStart, variant = SpButtonVariant.Text, size = SpButtonSize.Small, enabled = !busy)
            if (running > 0) SpButton("停止", onClick = onStop, variant = SpButtonVariant.Text, size = SpButtonSize.Small, enabled = !busy)
            SpButton("更新镜像", onClick = onBuild, variant = SpButtonVariant.Text, size = SpButtonSize.Small, enabled = !busy)
        }
    }
}

private enum class DetailTab { Logs, Env, Volumes, Network }

@Composable
private fun ContainerSheet(
    d: DetailUi,
    container: Container?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onFollow: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onCopied: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(DetailTab.Logs) }
    var query by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    SpSheet(title = d.name, subtitle = container?.image, onDismiss = onDismiss) {
        if (container != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateTag(container)
                Text(container.statusText, style = SpTheme.type.caption, color = SpTheme.colors.onSurfaceVariant)
            }
        }
        SpTabs(
            options = listOf(
                Triple(DetailTab.Logs, "日志", null),
                Triple(DetailTab.Env, "环境变量", null),
                Triple(DetailTab.Volumes, "存储", null),
                Triple(DetailTab.Network, "网络", null),
            ),
            selected = tab,
            onSelect = { tab = it },
        )
        when {
            d.loading -> Loading()
            tab == DetailTab.Logs -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpTextField(query, { query = it }, label = "筛选日志", leadingIcon = Icons.Outlined.Search, modifier = Modifier.weight(1f))
                    SpIconButton(
                        Icons.Outlined.VerticalAlignBottom,
                        contentDescription = if (d.following) "停止跟随" else "跟随新日志",
                        onClick = { onFollow(!d.following) },
                        style = if (d.following) SpIconButtonStyle.Filled else SpIconButtonStyle.Tonal,
                    )
                    SpIconButton(Icons.Outlined.ContentCopy, contentDescription = "复制日志", onClick = {
                        clipboard.setText(AnnotatedString(d.logs.joinToString("\n") { "${it.time} ${it.text}" }))
                        onCopied()
                    }, style = SpIconButtonStyle.Tonal)
                }
                val lines = if (query.isBlank()) d.logs else d.logs.filter { it.text.contains(query, ignoreCase = true) }
                Terminal(lines, d.error.takeIf { d.logs.isEmpty() }, follow = d.following)
            }
            tab == DetailTab.Env -> {
                val env = d.detail?.env.orEmpty()
                if (env.isEmpty()) Empty(d.error ?: "没有环境变量") else SpKeyValue(env.map { (k, v) -> k to mask(k, v) })
            }
            tab == DetailTab.Volumes -> {
                val vols = d.detail?.volumes.orEmpty()
                if (vols.isEmpty()) Empty("没有挂载目录") else SpKeyValue(vols.map { it.mountPoint to (it.hostPath + if (it.readOnly) "（只读）" else "") })
            }
            else -> {
                val det = d.detail
                val rows = buildList {
                    det?.networks?.forEach { (n, ip) -> add(n to ip.ifBlank { "—" }) }
                    (det?.ports ?: container?.ports).orEmpty().forEach { p -> add("端口" to "${p.hostPort ?: "—"} → ${p.containerPort ?: "—"}/${p.protocol}") }
                    det?.restartPolicy?.let { add("重启策略" to it) }
                }
                if (rows.isEmpty()) Empty("没有网络信息") else SpKeyValue(rows)
            }
        }
        if (container != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val running = container.state == ContainerState.Running
                SpButton(if (running) "重启" else "启动", onClick = if (running) onRestart else onStart, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f), enabled = !busy)
                if (running) SpButton("停止", onClick = onStop, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f), enabled = !busy)
            }
        }
    }
}

/** 名字像密钥的环境变量默认打码 */
private fun mask(key: String, value: String): String {
    val k = key.uppercase()
    val secret = listOf("PASS", "SECRET", "TOKEN", "KEY", "PWD").any { it in k }
    return if (secret && value.isNotEmpty()) "••••••••" else value
}

@Composable
private fun Terminal(lines: List<ContainerLogLine>, error: String?, follow: Boolean) {
    val bg = Color(0xFF111827)
    val state = rememberLazyListState()
    LaunchedEffect(lines.size, follow) {
        if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(12.dp)),
    ) {
        if (lines.isEmpty()) {
            Text(error ?: "没有日志", color = Color(0xFF9CA3AF), style = SpTheme.type.caption, modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                items(lines.size) { i ->
                    val l = lines[i]
                    val level = levelOf(l)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            logTime(l.time),
                            color = Color(0xFF6B7280),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            l.text,
                            color = when (level) {
                                2 -> Color(0xFFF87171)
                                1 -> Color(0xFFFBBF24)
                                else -> Color(0xFFE5E7EB)
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/** DSM 给的是 UTC 时间（2026-09-23T13:24:31.123Z），显示成本地时间 */
private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun logTime(raw: String): String = runCatching {
    OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).format(LOG_TIME)
}.getOrElse { raw.substringAfter('T').take(8) }

private fun levelOf(l: ContainerLogLine): Int {
    val t = l.text.take(40).uppercase()
    return when {
        "ERROR" in t || "FATAL" in t || "[ERR" in t || "PANIC" in t -> 2
        "WARN" in t || "[WRN" in t -> 1
        l.stream == "stderr" && "INFO" !in t && "[INF" !in t -> 1
        else -> 0
    }
}
