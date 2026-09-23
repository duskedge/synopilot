package io.github.duskedge.synopilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.SpCard
import io.github.duskedge.synopilot.designsystem.component.SpMeter
import io.github.duskedge.synopilot.designsystem.component.SpPlaceholder
import io.github.duskedge.synopilot.designsystem.component.SpStatus
import io.github.duskedge.synopilot.designsystem.component.SpStatusText
import io.github.duskedge.synopilot.designsystem.component.meterColorFor

@Composable
internal fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun DashboardPlaceholder() = ScreenColumn {
    SpPlaceholder("总览", "连接 NAS 后显示设备状态、网络和存储 · M1", Icons.Outlined.SpaceDashboard)
    // 设计系统样例：确认格栅计量条、状态点在真机上的效果
    SpCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("组件预览", style = SpTheme.type.title, color = SpTheme.colors.onSurface)
            SpStatusText(SpStatus.Ok, "运行正常")
        }
        listOf("CPU" to 0.24f, "内存" to 0.63f, "存储" to 0.86f).forEach { (label, v) ->
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = SpTheme.type.caption, color = SpTheme.colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("${(v * 100).toInt()}%", style = SpTheme.type.num, color = SpTheme.colors.onSurface)
            }
            SpMeter(fraction = v, cells = 24, color = meterColorFor(v), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun ContainersPlaceholder() = ScreenColumn {
    SpPlaceholder("容器", "Container Manager 与 Compose 项目 · M2", Icons.Outlined.ViewInAr)
}

@Composable
fun DownloadsPlaceholder() = ScreenColumn {
    SpPlaceholder("下载", "Download Station / qBittorrent / Transmission · M2", Icons.Outlined.Download)
}

@Composable
fun FilesPlaceholder() = ScreenColumn {
    SpPlaceholder("文件", "File Station、分享链接与传输队列 · M3", Icons.Outlined.Folder)
}
