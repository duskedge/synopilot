package io.github.duskedge.synopilot.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.SpButton
import io.github.duskedge.synopilot.designsystem.component.SpButtonSize
import io.github.duskedge.synopilot.designsystem.component.SpButtonVariant
import io.github.duskedge.synopilot.designsystem.component.SpMeter
import io.github.duskedge.synopilot.updater.UpdateManager
import io.github.duskedge.synopilot.updater.UpdateManifest
import io.github.duskedge.synopilot.updater.UpdateState
import java.util.Locale

/**
 * 更新对话框：新版本说明 → 下载进度 → 安装。
 * 强制更新时不能关闭，也不能跳过。
 */
@Composable
fun UpdateDialog(manager: UpdateManager) {
    val visible by manager.dialogVisible.collectAsStateWithLifecycle()
    val state by manager.state.collectAsStateWithLifecycle()
    if (!visible) return

    val manifest: UpdateManifest
    val forced: Boolean
    when (val s = state) {
        is UpdateState.Available -> { manifest = s.manifest; forced = s.forced }
        is UpdateState.Downloading -> { manifest = s.manifest; forced = s.forced }
        is UpdateState.ReadyToInstall -> { manifest = s.manifest; forced = s.forced }
        is UpdateState.Installing -> { manifest = s.manifest; forced = s.forced }
        is UpdateState.Failed -> { manifest = s.manifest ?: return; forced = s.forced }
        else -> return
    }

    val context = LocalContext.current
    var needsPermission by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        needsPermission = manager.needsInstallPermission()
        onPauseOrDispose { }
    }

    val c = SpTheme.colors
    AlertDialog(
        onDismissRequest = { manager.dismissDialog() },
        properties = DialogProperties(dismissOnBackPress = !forced, dismissOnClickOutside = !forced),
        shape = RoundedCornerShape(20.dp),
        containerColor = c.card,
        title = {
            Column {
                Text(if (forced) "需要更新到 ${manifest.versionName}" else "发现新版本 ${manifest.versionName}", style = SpTheme.type.title, color = c.onSurface)
                Text(
                    listOfNotNull(formatSize(manifest.apk.size), manifest.publishedAt?.take(10)?.let { "发布于 $it" }).joinToString(" · "),
                    style = SpTheme.type.caption,
                    color = c.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (forced) {
                    Text("当前版本已不再支持，更新后才能继续使用。", style = SpTheme.type.bodySmall, color = c.warning)
                }
                if (manifest.notes.isNotBlank()) {
                    Text(
                        manifest.notes,
                        style = SpTheme.type.bodySmall,
                        color = c.onSurface,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                when (val s = state) {
                    is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SpMeter(fraction = s.fraction, cells = 24, cellHeight = 8)
                        Text(
                            "${formatSize(s.downloaded)} / ${formatSize(s.total)}",
                            style = SpTheme.type.caption,
                            color = c.onSurfaceVariant,
                        )
                    }
                    is UpdateState.ReadyToInstall -> if (needsPermission) {
                        Text("还需要允许 SynoPilot「安装未知应用」，授权后回到这里点「安装」。", style = SpTheme.type.bodySmall, color = c.warning)
                    } else {
                        Text("已下载并通过校验。", style = SpTheme.type.bodySmall, color = c.success)
                    }
                    is UpdateState.Installing -> Text("请在系统弹出的窗口中确认安装。", style = SpTheme.type.bodySmall, color = c.onSurfaceVariant)
                    is UpdateState.Failed -> Text(s.message, style = SpTheme.type.bodySmall, color = c.error)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, androidx.compose.ui.Alignment.End),
            ) {
                when (val s = state) {
                    is UpdateState.Available -> {
                        if (!forced) {
                            SpButton("跳过此版本", onClick = { manager.skipThisVersion() }, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
                            SpButton("稍后", onClick = { manager.dismissDialog() }, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
                        }
                        SpButton("立即更新", onClick = { manager.startDownload() }, size = SpButtonSize.Small)
                    }
                    is UpdateState.Downloading -> {
                        if (!forced) SpButton("后台下载", onClick = { manager.dismissDialog() }, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
                        SpButton("取消", onClick = { manager.cancelDownload() }, variant = SpButtonVariant.Tonal, size = SpButtonSize.Small)
                    }
                    is UpdateState.ReadyToInstall -> {
                        if (!forced) SpButton("稍后", onClick = { manager.dismissDialog() }, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
                        if (needsPermission) {
                            SpButton("去授权", onClick = { context.startActivity(manager.installPermissionIntent()) }, size = SpButtonSize.Small)
                        } else {
                            SpButton("安装", onClick = { manager.install() }, size = SpButtonSize.Small)
                        }
                    }
                    is UpdateState.Installing -> Unit
                    is UpdateState.Failed -> {
                        if (!forced) SpButton("稍后", onClick = { manager.dismissDialog() }, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
                        SpButton("重试", onClick = { manager.startDownload() }, size = SpButtonSize.Small)
                    }
                    else -> Unit
                }
            }
        },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
