package io.github.duskedge.synopilot.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.EngineStatus
import io.github.duskedge.synopilot.designsystem.SpFormat
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.SpBanner
import io.github.duskedge.synopilot.designsystem.component.SpBannerTone
import io.github.duskedge.synopilot.designsystem.component.SpButton
import io.github.duskedge.synopilot.designsystem.component.SpButtonSize
import io.github.duskedge.synopilot.designsystem.component.SpButtonVariant
import io.github.duskedge.synopilot.designsystem.component.SpCard
import io.github.duskedge.synopilot.designsystem.component.SpChip
import io.github.duskedge.synopilot.designsystem.component.SpChipRow
import io.github.duskedge.synopilot.designsystem.component.SpIconButton
import io.github.duskedge.synopilot.designsystem.component.SpIconButtonStyle
import io.github.duskedge.synopilot.designsystem.component.SpKeyValue
import io.github.duskedge.synopilot.designsystem.component.SpMessageHost
import io.github.duskedge.synopilot.designsystem.component.SpMeter
import io.github.duskedge.synopilot.designsystem.component.SpPlaceholder
import io.github.duskedge.synopilot.designsystem.component.SpSheet
import io.github.duskedge.synopilot.designsystem.component.SpTabs
import io.github.duskedge.synopilot.designsystem.component.SpTextField
import io.github.duskedge.synopilot.network.download.Category
import io.github.duskedge.synopilot.network.download.DownloadTask
import io.github.duskedge.synopilot.network.download.EngineKind
import io.github.duskedge.synopilot.network.download.TaskState
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DownloadsScreen(onOpenDownloaders: () -> Unit, viewModel: DownloadsViewModel = koinViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf<String?>(null) }
    var limiting by remember { mutableStateOf(false) }
    var openTask by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        scope.launch {
            val text = runCatching { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull()
            viewModel.onClipboard(text)
        }
        onPauseOrDispose { }
    }

    val hasEngines = ui.data.engines.isNotEmpty()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.connection !is ConnectionState.Connected) {
                item(key = "offline") {
                    val msg = when (val s = ui.connection) {
                        is ConnectionState.Connecting -> "正在连接 ${s.device.name}…"
                        is ConnectionState.Failed -> s.reason.message
                        else -> "还没有连接到 NAS"
                    }
                    SpBanner("未连接", message = msg, icon = Icons.Outlined.CloudOff, tone = SpBannerTone.Warning)
                }
            }
            if (!hasEngines) {
                if (ui.data.loaded) {
                    item(key = "none") {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SpPlaceholder("还没有下载器", "可以添加 Download Station，或者 NAS 上以容器运行的 qBittorrent、Transmission", Icons.Outlined.Download)
                            SpButton("添加下载器", onClick = onOpenDownloaders, icon = Icons.Outlined.Add)
                        }
                    }
                }
            } else {
                item(key = "speed") { SpeedCard(ui, onLimit = { limiting = true }) }
                ui.clipboardLink?.let { link ->
                    item(key = "clip") { ClipboardCard(link, onIgnore = viewModel::dismissClipboard, onAdd = { adding = link }) }
                }
                ui.data.engines.filter { it.problem != null && ui.connection is ConnectionState.Connected }.forEach { e ->
                    item(key = "problem-" + e.config.id) { ProblemBanner(e, onOpenDownloaders) }
                }
                if (ui.data.engines.size > 1) {
                    item(key = "engines") {
                        SpChipRow(
                            options = listOf<Pair<String?, String>>(null to "全部下载器") + ui.data.engines.map { it.config.id to it.config.name },
                            selected = ui.engine,
                            onSelect = viewModel::setEngine,
                        )
                    }
                }
                item(key = "tabs") {
                    SpTabs(
                        options = listOf(
                            Triple(TaskTab.Active, "下载中", ui.count(TaskTab.Active)),
                            Triple(TaskTab.Queued, "等待", ui.count(TaskTab.Queued)),
                            Triple(TaskTab.Seeding, "做种", ui.count(TaskTab.Seeding)),
                            Triple(TaskTab.Done, "已完成", ui.count(TaskTab.Done)),
                        ),
                        selected = ui.tab,
                        onSelect = viewModel::setTab,
                    )
                }
                val list = ui.visible
                if (list.isEmpty()) {
                    item(key = "empty") {
                        val loading = ui.data.engines.any { !it.loaded }
                        Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                            if (loading) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = SpTheme.colors.primary)
                            } else {
                                Text("这里还没有任务", style = SpTheme.type.bodySmall, color = SpTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                val stale = ui.data.engines.filter { it.problem != null }.map { it.config.id }.toSet()
                items(list, key = { it.key }) { t ->
                    TaskCard(
                        t,
                        stale = t.engineId in stale,
                        engineName = if (ui.data.engines.size > 1) ui.engineName(t.engineId) else null,
                        onClick = { openTask = t.key },
                        onPause = { viewModel.pause(t) },
                        onResume = { viewModel.resume(t) },
                    )
                }
                if (ui.tab == TaskTab.Done && list.isNotEmpty()) {
                    item(key = "clear") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            SpButton("清除记录（不删除文件）", onClick = viewModel::clearDone, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
                        }
                    }
                }
            }
        }
        if (hasEngines) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .shadow(4.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(SpTheme.colors.primary)
                    .clickable { adding = "" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "新建下载任务", tint = SpTheme.colors.onPrimary, modifier = Modifier.size(22.dp))
            }
        }
        SpMessageHost(viewModel.messages)
    }

    adding?.let { initial ->
        AddTaskSheet(ui, initial, viewModel, onDismiss = { adding = null })
    }
    if (limiting) {
        LimitSheet(ui, onDismiss = { limiting = false }, onSave = { id, down, up -> viewModel.setLimits(id, down, up) { limiting = false } })
    }
    openTask?.let { key ->
        val t = ui.data.tasks.firstOrNull { it.key == key }
        if (t == null) {
            openTask = null
        } else {
            val kind = ui.data.engines.firstOrNull { it.config.id == t.engineId }?.config?.kind
            TaskSheet(
                t,
                engineName = ui.engineName(t.engineId),
                canDeleteFiles = kind != EngineKind.DownloadStation,
                onDismiss = { openTask = null },
                onPause = { viewModel.pause(t) },
                onResume = { viewModel.resume(t) },
                onRemove = { files -> viewModel.remove(t, files); openTask = null },
            )
        }
    }
}

@Composable
private fun SpeedCard(ui: DownloadsUi, onLimit: () -> Unit) {
    val c = SpTheme.colors
    val (num, unit) = SpFormat.speedParts(ui.downloadSpeed)
    val limited = ui.data.engines.filter { ui.engine == null || it.config.id == ui.engine }
        .mapNotNull { it.transfer?.downloadLimit }
    SpCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("下载速度", style = SpTheme.type.caption, color = c.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(num, style = SpTheme.type.numLarge, color = c.onSurface)
                    Text(" $unit", style = SpTheme.type.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
                }
                val engines = ui.engine?.let { ui.engineName(it) } ?: "${ui.data.engines.size} 个下载器"
                Text("上传 ${SpFormat.speed(ui.uploadSpeed)} · $engines", style = SpTheme.type.caption, color = c.onSurfaceVariant)
            }
            SpChip(
                text = if (limited.isEmpty()) "不限速" else "限速 ${SpFormat.speed(limited.max())}",
                selected = false,
                onClick = onLimit,
                icon = Icons.Outlined.Speed,
            )
        }
    }
}

@Composable
private fun ClipboardCard(link: String, onIgnore: () -> Unit, onAdd: () -> Unit) {
    val c = SpTheme.colors
    SpCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(c.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(if (link.startsWith("magnet:", true)) "剪贴板里有磁力链接" else "剪贴板里有下载链接", style = SpTheme.type.label, color = c.onSurface)
                Text(linkTitle(link), style = SpTheme.type.caption, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
            SpButton("忽略", onClick = onIgnore, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
            SpButton("添加下载", onClick = onAdd, size = SpButtonSize.Small)
        }
    }
}

/** 磁力链接优先显示 dn（名字） */
internal fun linkTitle(link: String): String {
    val dn = Regex("[?&]dn=([^&]+)").find(link)?.groupValues?.get(1)
    return dn?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) } ?: link
}

@Composable
private fun ProblemBanner(e: EngineStatus, onOpenDownloaders: () -> Unit) {
    SpBanner(
        "${e.config.name} 不可用",
        message = e.problem,
        icon = if (e.authFailed) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudOff,
        tone = if (e.authFailed) SpBannerTone.Error else SpBannerTone.Warning,
        actions = { SpButton("下载器设置", onClick = onOpenDownloaders, variant = SpButtonVariant.Tonal, size = SpButtonSize.Small) },
    )
}

private fun stateIcon(s: TaskState): ImageVector = when (s) {
    TaskState.Downloading -> Icons.Outlined.Download
    TaskState.Queued -> Icons.Outlined.Schedule
    TaskState.Paused -> Icons.Outlined.Pause
    TaskState.Seeding -> Icons.Outlined.Upload
    TaskState.Completed -> Icons.Outlined.CheckCircle
    TaskState.Checking -> Icons.Outlined.Sync
    TaskState.Error -> Icons.Outlined.ErrorOutline
}

internal fun eta(seconds: Long): String = when {
    seconds >= 86_400 -> "${seconds / 86_400} 天 ${seconds % 86_400 / 3600} 小时"
    seconds >= 3600 -> "%.1f 小时".format(seconds / 3600.0)
    seconds >= 60 -> "${seconds / 60} 分钟"
    else -> "$seconds 秒"
}

@Composable
private fun TaskCard(t: DownloadTask, stale: Boolean, engineName: String?, onClick: () -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    val c = SpTheme.colors
    val pct = (t.progress * 100).toInt()
    val sizeDone = "${SpFormat.bytes((t.sizeBytes * t.progress).toLong())} / ${SpFormat.bytes(t.sizeBytes)} · $pct%"
    val (left, right) = if (stale) "${SpFormat.bytes(t.sizeBytes)} · ${(t.progress * 100).toInt()}%" to "无法连接，显示的是缓存" else when (t.state) {
        TaskState.Downloading -> sizeDone to (SpFormat.speed(t.downloadSpeed) + (t.etaSec?.let { " · 剩余 ${eta(it)}" } ?: ""))
        TaskState.Paused -> sizeDone to "已暂停"
        TaskState.Checking -> sizeDone to "校验中"
        TaskState.Queued -> SpFormat.bytes(t.sizeBytes) to "等待中"
        TaskState.Seeding -> "${SpFormat.bytes(t.sizeBytes)}${t.ratio?.let { " · 分享率 %.2f".format(it) } ?: ""}" to "上传 ${SpFormat.speed(t.uploadSpeed)}"
        TaskState.Completed -> SpFormat.bytes(t.sizeBytes) to t.savePath
        TaskState.Error -> sizeDone to (t.error ?: "出错")
    }
    val dim = t.state == TaskState.Paused || t.state == TaskState.Queued
    SpCard(modifier = Modifier.alpha(if (stale) 0.55f else 1f), onClick = onClick, contentPadding = PaddingValues(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val (bg, fg) = when (t.state) {
                TaskState.Error -> c.errorContainer to c.error
                TaskState.Completed -> c.successContainer to c.success
                TaskState.Paused, TaskState.Queued -> c.surfaceHigh to c.onSurfaceVariant
                else -> c.primaryContainer to c.primary
            }
            Box(Modifier.size(34.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                Icon(stateIcon(t.state), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(t.name, style = SpTheme.type.label.copy(fontSize = 14.sp), color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(engineName, t.category).joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = SpTheme.type.caption, color = c.onSurfaceVariant, maxLines = 1)
            }
            if (stale) {
                Icon(Icons.Outlined.CloudOff, contentDescription = "无法连接", tint = c.onSurfaceVariant, modifier = Modifier.padding(end = 7.dp).size(18.dp))
            } else when (t.state) {
                TaskState.Downloading, TaskState.Checking -> SpIconButton(Icons.Outlined.Pause, "暂停", onPause)
                TaskState.Paused, TaskState.Error -> SpIconButton(Icons.Outlined.PlayArrow, "继续", onResume, style = SpIconButtonStyle.Tonal)
                TaskState.Queued -> SpIconButton(Icons.Outlined.PlayArrow, "开始", onResume)
                TaskState.Seeding -> SpIconButton(Icons.Outlined.Stop, "停止做种", onPause)
                TaskState.Completed -> Unit
            }
        }
        if (t.state != TaskState.Completed) {
            SpMeter(
                t.progress.toFloat(),
                modifier = Modifier.padding(top = 10.dp, end = 4.dp),
                cells = 32,
                cellHeight = 5,
                color = when (t.state) {
                    TaskState.Error -> c.error
                    TaskState.Seeding -> c.success
                    else -> if (dim) c.outline else c.primary
                },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(left, style = SpTheme.type.caption.copy(fontFamily = SpTheme.type.num.fontFamily), color = c.onSurfaceVariant, maxLines = 1)
            Text(
                right,
                style = SpTheme.type.caption.copy(fontFamily = if (t.state == TaskState.Downloading && !stale) SpTheme.type.num.fontFamily else null),
                color = if (t.state == TaskState.Error) c.error else c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AddTaskSheet(ui: DownloadsUi, initial: String, vm: DownloadsViewModel, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val usable = ui.data.engines.filter { it.problem == null }
    val preferred = ui.engine ?: ui.data.defaultEngineId
    var engine by rememberSaveable { mutableStateOf((usable.firstOrNull { it.config.id == preferred } ?: usable.firstOrNull() ?: ui.data.engines.firstOrNull())?.config?.id) }
    var savePath by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var submitting by remember { mutableStateOf(false) }
    LaunchedEffect(engine) {
        category = null
        categories = engine?.let { vm.categories(it) }.orEmpty()
    }
    val urls = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    SpSheet(title = "新建下载任务", onDismiss = onDismiss) {
        SpTextField(text, { text = it }, label = "链接", placeholder = "磁力链接或网址，每行一个", singleLine = false)
        Text("下载器", style = SpTheme.type.section, color = SpTheme.colors.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.data.engines.forEach { e ->
                SpChip(e.config.name + if (e.problem != null) "（不可用）" else "", selected = engine == e.config.id, onClick = { if (e.problem == null) engine = e.config.id })
            }
        }
        if (categories.isNotEmpty()) {
            Text("分类", style = SpTheme.type.section, color = SpTheme.colors.onSurface)
            SpChipRow(
                options = listOf<Pair<String?, String>>(null to "不分类") + categories.map { it.name to it.name },
                selected = category,
                onSelect = { category = it },
            )
        }
        val kind = ui.data.engines.firstOrNull { it.config.id == engine }?.config?.kind
        SpTextField(
            savePath, { savePath = it },
            label = "保存位置（可选）",
            placeholder = if (kind == EngineKind.DownloadStation) "共享文件夹，如 downloads/movies" else "容器内路径，如 /downloads/movies",
            supportingText = when {
                category != null -> "留空时使用分类的目录"
                else -> "留空时使用下载器的默认目录"
            },
        )
        SpButton(
            if (submitting) "正在添加…" else "添加",
            onClick = {
                val id = engine ?: return@SpButton
                submitting = true
                vm.add(id, urls, savePath.trim().ifBlank { null }, category) { ok ->
                    submitting = false
                    if (ok) onDismiss()
                }
            },
            size = SpButtonSize.Block,
            modifier = Modifier.fillMaxWidth(),
            enabled = urls.isNotEmpty() && engine != null && !submitting,
        )
    }
}

@Composable
private fun LimitSheet(ui: DownloadsUi, onDismiss: () -> Unit, onSave: (String, Long?, Long?) -> Unit) {
    val engines = ui.data.engines.filter { it.problem == null }
    var engine by rememberSaveable { mutableStateOf((engines.firstOrNull { it.config.id == ui.engine } ?: engines.firstOrNull())?.config?.id) }
    val current = engines.firstOrNull { it.config.id == engine }?.transfer
    fun mb(v: Long?) = v?.let { "%.1f".format(it / 1_048_576.0).removeSuffix(".0") }.orEmpty()
    var down by remember(engine) { mutableStateOf(mb(current?.downloadLimit)) }
    var up by remember(engine) { mutableStateOf(mb(current?.uploadLimit)) }
    fun parse(s: String): Long? = s.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1_048_576).toLong() }
    SpSheet(title = "限速", subtitle = "单位 MB/s，留空表示不限", onDismiss = onDismiss) {
        if (engines.isEmpty()) {
            Text("没有可用的下载器", style = SpTheme.type.bodySmall, color = SpTheme.colors.onSurfaceVariant)
            return@SpSheet
        }
        if (engines.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                engines.forEach { e -> SpChip(e.config.name, selected = engine == e.config.id, onClick = { engine = e.config.id }) }
            }
        }
        SpTextField(down, { down = it }, label = "下载", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        SpTextField(up, { up = it }, label = "上传", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpButton("不限速", onClick = { engine?.let { onSave(it, null, null) } }, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f))
            SpButton("保存", onClick = { engine?.let { onSave(it, parse(down), parse(up)) } }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TaskSheet(
    t: DownloadTask,
    engineName: String,
    canDeleteFiles: Boolean,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: (deleteFiles: Boolean) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val stateText = when (t.state) {
        TaskState.Downloading -> "下载中"
        TaskState.Queued -> "等待中"
        TaskState.Paused -> "已暂停"
        TaskState.Seeding -> "做种中"
        TaskState.Completed -> "已完成"
        TaskState.Checking -> "校验中"
        TaskState.Error -> "出错"
    }
    SpSheet(title = linkTitle(t.name), subtitle = engineName, onDismiss = onDismiss) {
        SpKeyValue(
            buildList {
                add("状态" to stateText)
                add("大小" to SpFormat.bytes(t.sizeBytes))
                add("进度" to "%.1f%%".format(t.progress * 100))
                if (t.downloadSpeed > 0) add("下载" to SpFormat.speed(t.downloadSpeed))
                if (t.uploadSpeed > 0) add("上传" to SpFormat.speed(t.uploadSpeed))
                t.ratio?.let { add("分享率" to "%.2f".format(it)) }
                if (t.seeds != null || t.peers != null) add("做种 / 用户" to "${t.seeds ?: "—"} / ${t.peers ?: "—"}")
                t.category?.let { add("分类" to it) }
                if (t.savePath.isNotBlank()) add("保存位置" to t.savePath)
                t.error?.let { add("错误" to it) }
            },
            monoValues = false,
        )
        if (!confirming) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (t.state) {
                    TaskState.Downloading, TaskState.Checking, TaskState.Seeding ->
                        SpButton(if (t.state == TaskState.Seeding) "停止做种" else "暂停", onClick = onPause, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f))
                    TaskState.Paused, TaskState.Queued, TaskState.Error ->
                        SpButton("继续", onClick = onResume, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f))
                    TaskState.Completed -> Unit
                }
                SpButton("删除", onClick = { confirming = true }, variant = SpButtonVariant.Outlined, modifier = Modifier.weight(1f))
            }
        } else {
            Text(
                if (canDeleteFiles) "只删除任务，还是连同已下载的文件一起删除？" else "删除任务记录，已下载到共享文件夹的文件会保留。",
                style = SpTheme.type.bodySmall,
                color = SpTheme.colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpButton("删除任务", onClick = { onRemove(false) }, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f))
                if (canDeleteFiles) SpButton("同时删除文件", onClick = { onRemove(true) }, variant = SpButtonVariant.Danger, modifier = Modifier.weight(1f))
            }
        }
    }
}
