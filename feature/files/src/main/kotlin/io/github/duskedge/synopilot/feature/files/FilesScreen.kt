package io.github.duskedge.synopilot.feature.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.FilesRepository
import io.github.duskedge.synopilot.data.Transfer
import io.github.duskedge.synopilot.data.TransferDirection
import io.github.duskedge.synopilot.data.TransferStatus
import io.github.duskedge.synopilot.designsystem.SpFormat
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.SpBanner
import io.github.duskedge.synopilot.designsystem.component.SpBannerTone
import io.github.duskedge.synopilot.designsystem.component.SpButton
import io.github.duskedge.synopilot.designsystem.component.SpButtonSize
import io.github.duskedge.synopilot.designsystem.component.SpButtonVariant
import io.github.duskedge.synopilot.designsystem.component.SpCard
import io.github.duskedge.synopilot.designsystem.component.SpChip
import io.github.duskedge.synopilot.designsystem.component.SpIconButton
import io.github.duskedge.synopilot.designsystem.component.SpIconButtonStyle
import io.github.duskedge.synopilot.designsystem.component.SpKeyValue
import io.github.duskedge.synopilot.designsystem.component.SpListGroup
import io.github.duskedge.synopilot.designsystem.component.SpListItem
import io.github.duskedge.synopilot.designsystem.component.SpMessage
import io.github.duskedge.synopilot.designsystem.component.SpMessageHost
import io.github.duskedge.synopilot.designsystem.component.SpMeter
import io.github.duskedge.synopilot.designsystem.component.SpSegmented
import io.github.duskedge.synopilot.designsystem.component.SpSheet
import io.github.duskedge.synopilot.designsystem.component.SpSwitch
import io.github.duskedge.synopilot.designsystem.component.SpTextField
import io.github.duskedge.synopilot.network.RemoteFile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

private sealed interface FileDialog {
    data class Actions(val file: RemoteFile) : FileDialog
    data class Rename(val file: RemoteFile) : FileDialog
    data class Delete(val file: RemoteFile) : FileDialog
    data object NewFolder : FileDialog
    data object Add : FileDialog
}

@Composable
fun FilesScreen(viewModel: FilesViewModel = koinViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val share by viewModel.share.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    val context = LocalContext.current
    val localMessages = remember { Channel<SpMessage>(Channel.BUFFERED) }
    val messages = remember { merge(viewModel.messages, localMessages.receiveAsFlow()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> viewModel.upload(uris) }
    val b = ui.browse

    BackHandler(enabled = b.tab == FilesTab.Browse && (b.path != FilesRepository.ROOT || b.searchResults != null)) { viewModel.up() }

    // 上传完成后刷新当前目录
    val finishedUploads = ui.transfers.count { it.direction == TransferDirection.Upload && it.status == TransferStatus.Done }
    LaunchedEffect(finishedUploads) { if (finishedUploads > 0) viewModel.onTransferFinished() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "tabs") {
                SpSegmented(
                    options = listOf(
                        FilesTab.Browse to "浏览",
                        FilesTab.Transfers to if (ui.activeTransfers > 0) "传输 ${ui.activeTransfers}" else "传输",
                    ),
                    selected = b.tab,
                    onSelect = viewModel::setTab,
                )
            }
            if (b.tab == FilesTab.Browse) {
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
                item(key = "crumbs") { Breadcrumbs(b.path, onOpen = viewModel::open, onRefresh = viewModel::refresh) }
                item(key = "search") { SearchRow(b, viewModel) }
                b.error?.let { e -> item(key = "error") { SpBanner("加载失败", message = e, tone = SpBannerTone.Warning) } }
                browseItems(b, viewModel, onMore = { dialog = FileDialog.Actions(it) })
            } else {
                transferItems(ui.transfers, viewModel, onOpen = { t -> openLocal(context, t) { localMessages.trySend(SpMessage(it)) } })
            }
        }
        if (b.tab == FilesTab.Browse && b.path != FilesRepository.ROOT && b.searchResults == null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .shadow(4.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(SpTheme.colors.primary)
                    .clickable { dialog = FileDialog.Add },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "上传或新建", tint = SpTheme.colors.onPrimary, modifier = Modifier.size(22.dp))
            }
        }
        SpMessageHost(messages)
    }

    when (val d = dialog) {
        is FileDialog.Actions -> ActionsSheet(
            d.file,
            onDismiss = { dialog = null },
            onPreview = { dialog = null; viewModel.openPreview(d.file) },
            onDownload = { dialog = null; viewModel.download(d.file) },
            onShare = { dialog = null; viewModel.openShare(d.file) },
            onRename = { dialog = FileDialog.Rename(d.file) },
            onDelete = { dialog = FileDialog.Delete(d.file) },
        )
        is FileDialog.Rename -> NameSheet("重命名", d.file.name, "保存", onDismiss = { dialog = null }) { name ->
            dialog = null
            viewModel.rename(d.file, name)
        }
        FileDialog.NewFolder -> NameSheet("新建文件夹", "", "新建", onDismiss = { dialog = null }) { name ->
            dialog = null
            viewModel.createFolder(name)
        }
        is FileDialog.Delete -> SpSheet(
            title = "删除 ${d.file.name}？",
            subtitle = if (d.file.isDir) "文件夹里的所有内容都会被删除。开启了回收站的共享文件夹可以在回收站里找回。" else "开启了回收站的共享文件夹可以在回收站里找回。",
            onDismiss = { dialog = null },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpButton("取消", onClick = { dialog = null }, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f))
                SpButton("删除", onClick = { dialog = null; viewModel.delete(d.file) }, variant = SpButtonVariant.Danger, modifier = Modifier.weight(1f))
            }
        }
        FileDialog.Add -> SpSheet(title = "添加到 ${b.path.substringAfterLast('/')}", onDismiss = { dialog = null }) {
            SpListGroup {
                SpListItem("上传文件", icon = Icons.Outlined.Upload, subtitle = "从手机选择一个或多个文件", onClick = {
                    dialog = null
                    picker.launch(arrayOf("*/*"))
                })
                SpListItem("新建文件夹", icon = Icons.Outlined.CreateNewFolder, divider = true, onClick = { dialog = FileDialog.NewFolder })
            }
        }
        null -> Unit
    }

    preview?.let { p ->
        PreviewSheet(p, viewModel, onDownload = { viewModel.closePreview(); viewModel.download(p.file) })
    }
    share?.let { s -> ShareSheet(s, viewModel, onCopied = { localMessages.trySend(SpMessage("已复制链接")) }) }
}

@Composable
private fun Breadcrumbs(path: String, onOpen: (String) -> Unit, onRefresh: () -> Unit) {
    val c = SpTheme.colors
    val crumbs = FileKinds.breadcrumbs(path)
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            crumbs.forEachIndexed { i, (name, p) ->
                if (i > 0) Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.outline, modifier = Modifier.size(16.dp))
                val last = i == crumbs.lastIndex
                Text(
                    name,
                    style = if (last) SpTheme.type.label else SpTheme.type.bodySmall,
                    color = if (last) c.onSurface else c.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !last) { onOpen(p) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
        SpIconButton(Icons.Outlined.Refresh, "刷新", onRefresh)
    }
}

@Composable
private fun SearchRow(b: BrowseState, vm: FilesViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpTextField(
            b.query,
            vm::setQuery,
            label = if (b.path == FilesRepository.ROOT) "搜索所有共享文件夹" else "筛选，回车搜索子文件夹",
            leadingIcon = Icons.Outlined.Search,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.deepSearch() }),
            trailing = if (b.query.isNotEmpty()) {
                { SpIconButton(Icons.Outlined.Close, "清除", { vm.setQuery("") }) }
            } else {
                null
            },
        )
    }
    if (b.searching) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = SpTheme.colors.primary)
            Text("正在搜索子文件夹…", style = SpTheme.type.caption, color = SpTheme.colors.onSurfaceVariant)
        }
    } else if (b.searchResults != null) {
        Text(
            "在子文件夹里找到 ${b.searchResults.size} 个结果",
            style = SpTheme.type.caption,
            color = SpTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}

private fun LazyListScope.browseItems(b: BrowseState, vm: FilesViewModel, onMore: (RemoteFile) -> Unit) {
    val list = b.visible
    when {
        b.loading && b.files.isEmpty() -> item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = SpTheme.colors.primary)
            }
        }
        list.isEmpty() && b.error == null -> item(key = "empty") {
            Text(
                when {
                    b.searchResults != null -> "没有找到匹配的文件"
                    b.query.isNotBlank() -> "当前文件夹里没有匹配的文件，按回车搜索子文件夹"
                    else -> "空文件夹"
                },
                style = SpTheme.type.bodySmall,
                color = SpTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
            )
        }
        list.isNotEmpty() -> item(key = "list:" + b.path + (b.searchResults?.size ?: -1)) {
            SpListGroup {
                list.forEachIndexed { i, f ->
                    FileRow(
                        f,
                        showPath = b.searchResults != null,
                        isShare = b.path == FilesRepository.ROOT && b.searchResults == null,
                        divider = i > 0,
                        vm = vm,
                        onClick = { if (f.isDir) vm.open(f.path) else vm.openPreview(f) },
                        onMore = { onMore(f) },
                    )
                }
            }
        }
    }
}

private fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.Folder -> Icons.Outlined.Folder
    FileKind.Image -> Icons.Outlined.Image
    FileKind.Video -> Icons.Outlined.Movie
    FileKind.Audio -> Icons.Outlined.MusicNote
    FileKind.Archive -> Icons.Outlined.FolderZip
    FileKind.Pdf -> Icons.Outlined.PictureAsPdf
    FileKind.Text -> Icons.Outlined.Description
    FileKind.Other -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
private fun FileRow(
    f: RemoteFile,
    showPath: Boolean,
    isShare: Boolean,
    divider: Boolean,
    vm: FilesViewModel,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val c = SpTheme.colors
    val kind = FileKinds.of(f)
    val thumb by produceState<ImageBitmap?>(null, f.path) {
        if (kind == FileKind.Image) value = vm.thumbnail(f.path)?.let { decode(it) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind {
                if (divider) drawLine(c.line, Offset(62.dp.toPx(), 0f), Offset(size.width, 0f), 1.dp.toPx())
            }
            .heightIn(min = 56.dp)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(if (kind == FileKind.Folder) c.primaryContainer else c.card2),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) {
                Image(t, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(kindIcon(kind), contentDescription = null, tint = if (kind == FileKind.Folder) c.primary else c.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(f.name, style = SpTheme.type.body.copy(fontSize = 14.sp), color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                showPath -> FilesRepository.parent(f.path)
                isShare -> f.realPath ?: ""
                f.isDir -> FileKinds.time(f.modified)
                else -> listOf(SpFormat.bytes(f.size), FileKinds.time(f.modified)).filter { it.isNotBlank() }.joinToString(" · ")
            }
            if (sub.isNotBlank()) Text(sub, style = SpTheme.type.caption, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SpIconButton(Icons.Outlined.MoreVert, "更多操作", onMore)
    }
}

private fun decode(bytes: ByteArray): ImageBitmap? = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

@Composable
private fun ActionsSheet(
    f: RemoteFile,
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val sub = if (f.isDir) f.path else "${SpFormat.bytes(f.size)} · ${FileKinds.time(f.modified)}"
    SpSheet(title = f.name, subtitle = sub, onDismiss = onDismiss) {
        SpListGroup {
            var first = true
            fun div() = (!first).also { first = false }
            if (!f.isDir) {
                SpListItem("预览", icon = Icons.Outlined.Visibility, divider = div(), onClick = onPreview)
                SpListItem("下载到手机", icon = Icons.Outlined.Download, subtitle = "保存到「下载/SynoPilot」", divider = div(), onClick = onDownload)
            }
            SpListItem("分享链接", icon = Icons.Outlined.Link, divider = div(), onClick = onShare)
            SpListItem("重命名", icon = Icons.Outlined.DriveFileRenameOutline, divider = div(), onClick = onRename)
            SpListItem("删除", icon = Icons.Outlined.Delete, titleColor = SpTheme.colors.error, divider = div(), onClick = onDelete)
        }
    }
}

@Composable
private fun NameSheet(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val error = if (name == initial && initial.isNotEmpty()) null else FileKinds.invalidName(name)
    SpSheet(title = title, onDismiss = onDismiss) {
        SpTextField(name, { name = it }, label = "名称", isError = name.isNotEmpty() && error != null, supportingText = error.takeIf { name.isNotEmpty() })
        SpButton(confirm, onClick = { onConfirm(name) }, size = SpButtonSize.Block, modifier = Modifier.fillMaxWidth(), enabled = error == null && name != initial)
    }
}

@Composable
private fun PreviewSheet(p: PreviewUi, vm: FilesViewModel, onDownload: () -> Unit) {
    val c = SpTheme.colors
    SpSheet(title = p.file.name, subtitle = "${SpFormat.bytes(p.file.size)} · ${FileKinds.time(p.file.modified)}", onDismiss = vm::closePreview) {
        when {
            p.loading -> Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.primary)
            }
            p.error != null -> SpBanner("无法预览", message = p.error, tone = SpBannerTone.Warning)
            p.image != null -> {
                val bmp = remember(p.image) { decode(p.image) }
                if (bmp != null) {
                    Image(
                        bmp,
                        contentDescription = p.file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    SpBanner("无法预览", message = "图片格式不支持", tone = SpBannerTone.Warning)
                }
            }
            p.text != null -> Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.card)
                    .border(1.dp, c.line, RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(p.text.ifEmpty { "（空文件）" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = c.onSurface, softWrap = false)
            }
            else -> SpKeyValue(
                listOfNotNull(
                    "类型" to (p.file.extension.uppercase().ifBlank { "文件" }),
                    "大小" to SpFormat.bytes(p.file.size),
                    "修改时间" to FileKinds.time(p.file.modified),
                    "位置" to FilesRepository.parent(p.file.path),
                ),
                monoValues = false,
            )
        }
        if (p.text != null && p.text.length >= 256 * 1024 - 16) {
            Text("只显示了文件开头的 256 KB", style = SpTheme.type.caption, color = c.onSurfaceVariant)
        }
        SpButton("下载到手机", onClick = onDownload, icon = Icons.Outlined.Download, variant = SpButtonVariant.Tonal, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ShareSheet(s: ShareUi, vm: FilesViewModel, onCopied: () -> Unit) {
    val c = SpTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var days by rememberSaveable { mutableStateOf<Int?>(7) }
    var usePassword by rememberSaveable { mutableStateOf(false) }
    var password by rememberSaveable { mutableStateOf(randomCode()) }
    SpSheet(title = "分享 ${s.file.name}", onDismiss = vm::closeShare) {
        val link = s.link
        if (link == null) {
            Text("有效期", style = SpTheme.type.section, color = c.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "1 天", 7 to "7 天", 30 to "30 天", null to "永久").forEach { (d, label) ->
                    SpChip(label, selected = days == d, onClick = { days = d })
                }
            }
            SpListGroup {
                SpListItem("提取码", subtitle = "打开链接时需要输入", trailing = { SpSwitch(usePassword, { usePassword = it }) })
            }
            if (usePassword) SpTextField(password, { password = it }, label = "提取码")
            s.error?.let { SpBanner("创建失败", message = it, tone = SpBannerTone.Warning) }
            SpButton(
                if (s.creating) "正在创建…" else "创建链接",
                onClick = { vm.createShare(password.takeIf { usePassword }, days) },
                size = SpButtonSize.Block,
                modifier = Modifier.fillMaxWidth(),
                enabled = !s.creating && (!usePassword || password.isNotBlank()),
            )
        } else {
            SpCard {
                Text(link.url, style = SpTheme.type.bodySmall.copy(fontFamily = FontFamily.Monospace), color = c.onSurface)
                val meta = listOfNotNull(
                    link.expires?.let { "$it 到期" } ?: "永久有效",
                    if (link.hasPassword && usePassword) "提取码 $password" else null,
                ).joinToString(" · ")
                Text(meta, style = SpTheme.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            qrBitmap(link.qrCode)?.let { qr ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(qr, contentDescription = "二维码", modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)))
                }
            }
            val text = if (link.hasPassword && usePassword) "${link.url}\n提取码：$password" else link.url
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpButton("复制", icon = Icons.Outlined.ContentCopy, variant = SpButtonVariant.Tonal, modifier = Modifier.weight(1f), onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("分享链接", text))) }
                    onCopied()
                })
                SpButton("发送", icon = Icons.Outlined.Share, modifier = Modifier.weight(1f), onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "分享链接"))
                })
            }
        }
    }
}

private fun randomCode(): String {
    val chars = "abcdefghjkmnpqrstuvwxyz23456789"
    return (1..4).map { chars.random() }.joinToString("")
}

/** DSM 返回的二维码是 data:image/png;base64,… */
private fun qrBitmap(data: String?): ImageBitmap? {
    val b64 = data?.substringAfter("base64,", "")?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()?.let(::decode)
}

private fun LazyListScope.transferItems(
    transfers: List<Transfer>,
    vm: FilesViewModel,
    onOpen: (Transfer) -> Unit,
) {
    if (transfers.isEmpty()) {
        item(key = "t-empty") {
            Text(
                "没有传输任务。在文件的「更多」里下载，或在文件夹里点 + 上传。",
                style = SpTheme.type.bodySmall,
                color = SpTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp),
            )
        }
        return
    }
    items(transfers, key = { it.id }) { t -> TransferCard(t, vm, onOpen) }
    if (transfers.any { it.finished }) {
        item(key = "t-clear") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SpButton("清除已结束的记录", onClick = vm::clearFinished, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
            }
        }
    }
}

@Composable
private fun TransferCard(t: Transfer, vm: FilesViewModel, onOpen: (Transfer) -> Unit) {
    val c = SpTheme.colors
    val up = t.direction == TransferDirection.Upload
    SpCard(contentPadding = PaddingValues(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(
                    when (t.status) {
                        TransferStatus.Failed -> c.errorContainer
                        TransferStatus.Done -> c.successContainer
                        else -> c.primaryContainer
                    },
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (up) Icons.Outlined.Upload else Icons.Outlined.Download,
                    contentDescription = null,
                    tint = when (t.status) {
                        TransferStatus.Failed -> c.error
                        TransferStatus.Done -> c.success
                        else -> c.primary
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(t.name, style = SpTheme.type.label.copy(fontSize = 14.sp), color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val where = if (up) "上传到 ${t.remotePath}" else "下载到 下载/SynoPilot"
                Text(where, style = SpTheme.type.caption, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (t.status) {
                TransferStatus.Queued, TransferStatus.Running -> SpIconButton(Icons.Outlined.Close, "取消", { vm.cancelTransfer(t.id) })
                TransferStatus.Failed, TransferStatus.Cancelled -> SpIconButton(Icons.Outlined.Refresh, "重试", { vm.retryTransfer(t.id) }, style = SpIconButtonStyle.Tonal)
                TransferStatus.Done -> if (!up && t.result != null) SpIconButton(Icons.AutoMirrored.Outlined.OpenInNew, "打开", { onOpen(t) })
            }
        }
        if (t.status == TransferStatus.Running || t.status == TransferStatus.Queued) {
            SpMeter(t.progress, modifier = Modifier.padding(top = 10.dp, end = 4.dp), cells = 32, cellHeight = 5)
        }
        val (left, right) = when (t.status) {
            TransferStatus.Queued -> (t.size?.let { SpFormat.bytes(it) } ?: "") to "等待中"
            TransferStatus.Running -> "${SpFormat.bytes(t.done)} / ${t.size?.let { SpFormat.bytes(it) } ?: "?"}" to SpFormat.speed(t.bytesPerSec)
            TransferStatus.Done -> (t.size?.let { SpFormat.bytes(it) } ?: "") to "已完成"
            TransferStatus.Failed -> "" to (t.error ?: "失败")
            TransferStatus.Cancelled -> "" to "已取消"
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(left, style = SpTheme.type.caption.copy(fontFamily = SpTheme.type.num.fontFamily), color = c.onSurfaceVariant, maxLines = 1)
            Text(
                right,
                style = SpTheme.type.caption,
                color = if (t.status == TransferStatus.Failed) c.error else c.onSurfaceVariant,
                maxLines = 2,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun openLocal(context: Context, t: Transfer, onError: (String) -> Unit) {
    val result = t.result ?: return
    if (!result.startsWith("content://")) {
        onError("文件已保存到 $result")
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(result.toUri(), context.contentResolver.getType(result.toUri()) ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        onError("没有能打开这个文件的应用")
    }
}
