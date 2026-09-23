package io.github.duskedge.synopilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.duskedge.synopilot.designsystem.component.SpPlaceholder

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
