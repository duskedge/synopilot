package io.github.duskedge.synopilot.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duskedge.synopilot.BuildConfig
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.SpButton
import io.github.duskedge.synopilot.designsystem.component.SpButtonSize
import io.github.duskedge.synopilot.designsystem.component.SpButtonVariant
import io.github.duskedge.synopilot.designsystem.component.SpListGroup
import io.github.duskedge.synopilot.designsystem.component.SpListItem
import io.github.duskedge.synopilot.designsystem.component.SpSectionHeader
import io.github.duskedge.synopilot.designsystem.component.SpStatus
import io.github.duskedge.synopilot.designsystem.component.SpSwitch
import io.github.duskedge.synopilot.designsystem.component.SpTag
import io.github.duskedge.synopilot.ui.ScreenColumn
import io.github.duskedge.synopilot.updater.MirrorUrl
import io.github.duskedge.synopilot.updater.UpdateManager
import io.github.duskedge.synopilot.updater.UpdateSettings
import io.github.duskedge.synopilot.updater.UpdateState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(updateManager: UpdateManager = koinInject()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by updateManager.state.collectAsStateWithLifecycle()
    val settings by updateManager.settings.collectAsStateWithLifecycle(initialValue = UpdateSettings())
    var needsInstallPermission by remember { mutableStateOf(false) }
    var editingMirror by rememberSaveable { mutableStateOf(false) }

    // 从系统设置返回后重新判断安装权限
    LifecycleResumeEffect(Unit) {
        needsInstallPermission = BuildConfig.UPDATE_INSTALL_ALLOWED && updateManager.needsInstallPermission()
        onPauseOrDispose { }
    }

    ScreenColumn {
        SpSectionHeader("关于")
        SpListGroup {
            SpListItem(
                title = "版本",
                icon = Icons.Outlined.Info,
                subtitle = "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                trailing = { if (settings.betaChannel) SpTag("测试版通道") },
            )
            SpListItem(
                title = "检查更新",
                icon = Icons.Outlined.SystemUpdate,
                subtitle = updateSubtitle(state, settings),
                divider = true,
                onClick = {
                    when (state) {
                        is UpdateState.Available, is UpdateState.Downloading, is UpdateState.ReadyToInstall -> updateManager.showDialog()
                        else -> updateManager.checkNow()
                    }
                },
                trailing = {
                    when (state) {
                        UpdateState.Checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SpTheme.colors.primary)
                        is UpdateState.Available, is UpdateState.ReadyToInstall -> SpTag("新版本", status = SpStatus.Error)
                        else -> Unit
                    }
                },
            )
            SpListItem(
                title = "接收测试版",
                icon = Icons.Outlined.Science,
                subtitle = "提前体验 beta / rc 版本，可能不稳定",
                divider = true,
                trailing = {
                    SpSwitch(checked = settings.betaChannel, onCheckedChange = { on ->
                        scope.launch { updateManager.setBetaChannel(on) }
                    })
                },
            )
            SpListItem(
                title = "更新下载地址",
                icon = Icons.Outlined.Public,
                subtitle = settings.mirrorPrefix.ifBlank { "默认（直接从 GitHub 下载）" },
                divider = true,
                onClick = { editingMirror = true },
            )
            if (needsInstallPermission) {
                SpListItem(
                    title = "允许安装更新",
                    icon = Icons.Outlined.GetApp,
                    subtitle = "需要在系统设置里允许 SynoPilot 安装应用",
                    titleColor = SpTheme.colors.warning,
                    divider = true,
                    onClick = { context.startActivity(updateManager.installPermissionIntent()) },
                )
            }
            SpListItem(
                title = "源代码",
                icon = Icons.Outlined.Code,
                subtitle = "github.com/${BuildConfig.UPDATE_REPO}",
                divider = true,
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://github.com/${BuildConfig.UPDATE_REPO}".toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = SpTheme.colors.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
            )
        }
        if (!BuildConfig.UPDATE_INSTALL_ALLOWED) {
            Text(
                "这是调试版：可以检查更新，但不能直接安装正式版（包名和签名不同）。",
                style = SpTheme.type.caption,
                color = SpTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (editingMirror) {
        MirrorDialog(
            initial = settings.mirrorPrefix,
            onDismiss = { editingMirror = false },
            onSave = { value ->
                scope.launch { updateManager.setMirrorPrefix(value) }
                editingMirror = false
            },
        )
    }
}

private fun updateSubtitle(state: UpdateState, settings: UpdateSettings): String {
    val last = if (settings.lastCheckAt > 0) {
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(settings.lastCheckAt))
    } else {
        null
    }
    return when (state) {
        UpdateState.Idle -> last?.let { "上次检查：$it" } ?: "点按检查新版本"
        UpdateState.Checking -> "正在检查…"
        is UpdateState.UpToDate -> "已是最新版本" + (last?.let { " · $it" } ?: "")
        is UpdateState.Available -> "发现新版本 ${state.manifest.versionName}" + if (state.forced) "（必须更新）" else ""
        is UpdateState.Downloading -> "正在下载 ${state.manifest.versionName}（${(state.fraction * 100).toInt()}%）"
        is UpdateState.ReadyToInstall -> "${state.manifest.versionName} 已下载，点按安装"
        is UpdateState.Installing -> "正在安装 ${state.manifest.versionName}"
        is UpdateState.Failed -> state.message
    }
}

@Composable
private fun MirrorDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val valid = MirrorUrl.isValidPrefix(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = SpTheme.colors.card,
        title = { Text("更新下载地址", style = SpTheme.type.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "GitHub 下载慢时，可以填一个镜像地址前缀，例如 https://mirror.example.com/。留空则直接从 GitHub 下载。\n" +
                        "无论从哪里下载，安装前都会校验文件指纹和签名，被篡改的安装包无法安装。",
                    style = SpTheme.type.bodySmall,
                    color = SpTheme.colors.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    isError = !valid,
                    placeholder = { Text("https://") },
                    supportingText = { if (!valid) Text("需要以 https:// 开头") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SpButton("取消", onClick = onDismiss, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
                SpButton("保存", onClick = { onSave(value.trim()) }, enabled = valid, size = SpButtonSize.Small)
            }
        },
    )
}
