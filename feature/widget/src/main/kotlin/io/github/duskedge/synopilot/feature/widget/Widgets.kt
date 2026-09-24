package io.github.duskedge.synopilot.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.duskedge.synopilot.data.AppLinks
import io.github.duskedge.synopilot.data.DeviceRepository
import io.github.duskedge.synopilot.data.NasSnapshot
import io.github.duskedge.synopilot.data.SnapshotStore
import io.github.duskedge.synopilot.data.SnapshotUpdater
import io.github.duskedge.synopilot.data.SystemRepository
import io.github.duskedge.synopilot.designsystem.SpFormat
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/** 小部件配色：沿用 App 的 DSM 蓝，深色模式自动切换 */
private object WColors {
    val bg = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1C1F24))
    val text = ColorProvider(day = Color(0xFF1A1C1E), night = Color(0xFFE3E5E8))
    val secondary = ColorProvider(day = Color(0xFF6B7280), night = Color(0xFF9AA1AB))
    val primary = ColorProvider(day = Color(0xFF0B7EE0), night = Color(0xFF3D9BFF))
    val ok = ColorProvider(day = Color(0xFF16A34A), night = Color(0xFF34C66A))
    val warn = ColorProvider(day = Color(0xFFD97706), night = Color(0xFFF2A93B))
    val error = ColorProvider(day = Color(0xFFDC2626), night = Color(0xFFF26B6B))
    val track = ColorProvider(day = Color(0xFFE5EAF0), night = Color(0xFF2C3138))
    val offline = ColorProvider(day = Color(0xFF9CA3AF), night = Color(0xFF6B7280))
}

private fun koin() = GlobalContext.get()

private fun style(size: TextUnit, color: ColorProvider = WColors.text, bold: Boolean = false) =
    TextStyle(fontSize = size, color = color, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal)

/** CPU / 内存 60/80 变色；存储 80/90 变色（和 App 一致） */
private fun levelColor(percent: Int?, warn: Int, danger: Int) = when {
    percent == null -> WColors.primary
    percent >= danger -> WColors.error
    percent >= warn -> WColors.warn
    else -> WColors.primary
}

/** 手动刷新：拉一次数据并立即更新 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        koin().get<SnapshotUpdater>().refresh()
        koin().get<SnapshotStore>().flush()
    }
}

/** 离线时网络唤醒 */
class WakeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val device = koin().get<DeviceRepository>().currentDevice.first() ?: return
        runCatching { koin().get<SystemRepository>().wake(device) }
    }
}

@Composable
private fun Header(s: NasSnapshot, title: String, trailing: String?) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.size(8.dp).cornerRadius(4.dp).background(if (s.online) WColors.ok else WColors.offline)) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(title, style = style(14.sp, bold = true), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        if (trailing != null) Text(trailing, style = style(11.sp, WColors.secondary), maxLines = 1)
        Spacer(GlanceModifier.width(6.dp))
        Image(
            ImageProvider(R.drawable.ic_widget_refresh),
            contentDescription = "刷新",
            colorFilter = ColorFilter.tint(WColors.secondary),
            modifier = GlanceModifier.size(18.dp).clickable(actionRunCallback<RefreshAction>()),
        )
    }
}

private fun ago(s: NasSnapshot): String? = s.updatedAt.takeIf { it > 0 }?.let { SpFormat.ago(it) }

// ---- NAS 状态 ---------------------------------------------------------------

class StatusWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = koin().get<SnapshotStore>()
        provideContent {
            val s by store.snapshot.collectAsState()
            StatusContent(context, s)
        }
    }

    companion object {
        val SMALL = DpSize(140.dp, 60.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)
    }
}

@Composable
private fun StatusContent(context: Context, s: NasSnapshot) {
    val open = AppLinks.open(context, AppLinks.DASHBOARD)
    val root = GlanceModifier.fillMaxSize().background(WColors.bg).cornerRadius(16.dp).padding(12.dp)
        .let { if (open != null) it.clickable(actionStartActivity(open)) else it }
    Column(root) {
        if (s.deviceId.isEmpty()) {
            Text("SynoPilot", style = style(14.sp, bold = true))
            Text("打开 App 添加 NAS", style = style(12.sp, WColors.secondary))
            return@Column
        }
        val small = LocalSize.current.height < StatusWidget.MEDIUM.height
        Header(s, s.deviceName, if (small) null else ago(s)?.let { if (s.online) it else "离线 · $it" } ?: if (s.online) null else "离线")
        if (!s.online) {
            Spacer(GlanceModifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("无法连接", style = style(12.sp, WColors.secondary), modifier = GlanceModifier.defaultWeight())
                if (s.canWake) {
                    Text(
                        "唤醒",
                        style = style(12.sp, WColors.primary, bold = true),
                        modifier = GlanceModifier.cornerRadius(8.dp).background(WColors.track).padding(horizontal = 10.dp, vertical = 4.dp)
                            .clickable(actionRunCallback<WakeAction>()),
                    )
                }
            }
            if (small) return@Column
        }
        if (small) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                listOfNotNull(s.cpu?.let { "CPU $it%" }, s.memory?.let { "内存 $it%" }).joinToString(" · ").ifBlank { "—" },
                style = style(12.sp, WColors.secondary),
                maxLines = 1,
            )
            return@Column
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth()) {
            Metric("CPU", s.cpu, 60, 80, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(10.dp))
            Metric("内存", s.memory, 60, 80, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(10.dp))
            Metric("存储", s.storagePercent, 80, 90, GlanceModifier.defaultWeight())
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val running = s.runningContainers
            if (running != null) {
                Text("容器 $running 运行", style = style(12.sp, WColors.secondary), maxLines = 1)
                val exited = s.exitedContainers ?: 0
                if (exited > 0) Text(" · $exited 异常", style = style(12.sp, WColors.error), maxLines = 1)
            }
            Spacer(GlanceModifier.defaultWeight())
            s.downloadSpeed?.let { Text("↓ ${SpFormat.speed(it)}", style = style(12.sp, WColors.secondary), maxLines = 1) }
        }
    }
}

@Composable
private fun Metric(label: String, percent: Int?, warn: Int, danger: Int, modifier: GlanceModifier) {
    Column(modifier) {
        Row(GlanceModifier.fillMaxWidth()) {
            Text(label, style = style(11.sp, WColors.secondary), modifier = GlanceModifier.defaultWeight())
            Text(percent?.let { "$it%" } ?: "—", style = style(12.sp, bold = true))
        }
        Spacer(GlanceModifier.height(4.dp))
        LinearProgressIndicator(
            progress = (percent ?: 0) / 100f,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
            color = levelColor(percent, warn, danger),
            backgroundColor = WColors.track,
        )
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}

// ---- 下载进度 ---------------------------------------------------------------

class DownloadsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = koin().get<SnapshotStore>()
        provideContent {
            val s by store.snapshot.collectAsState()
            DownloadsContent(context, s)
        }
    }
}

@Composable
private fun DownloadsContent(context: Context, s: NasSnapshot) {
    val open = AppLinks.open(context, AppLinks.DOWNLOADS)
    val root = GlanceModifier.fillMaxSize().background(WColors.bg).cornerRadius(16.dp).padding(12.dp)
        .let { if (open != null) it.clickable(actionStartActivity(open)) else it }
    Column(root) {
        val speed = s.downloadSpeed?.let { "↓ ${SpFormat.speed(it)}" + (s.uploadSpeed?.takeIf { u -> u > 0 }?.let { u -> " ↑ ${SpFormat.speed(u)}" } ?: "") }
        Header(s, "下载", if (s.online) speed else "离线")
        Spacer(GlanceModifier.height(8.dp))
        val rows = ((LocalSize.current.height.value - 50) / 44).toInt().coerceIn(1, 4)
        when {
            s.deviceId.isEmpty() -> Text("打开 App 添加 NAS", style = style(12.sp, WColors.secondary))
            s.downloads.isEmpty() -> Text(
                if (s.activeDownloads == null) "打开 App 的下载页后显示" else "没有进行中的下载",
                style = style(12.sp, WColors.secondary),
            )
            else -> s.downloads.take(rows).forEach { d ->
                Column(GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(d.name, style = style(12.sp), maxLines = 1)
                    Spacer(GlanceModifier.height(3.dp))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = d.progress,
                            modifier = GlanceModifier.defaultWeight().height(4.dp),
                            color = if (d.paused || !s.online) WColors.offline else WColors.primary,
                            backgroundColor = WColors.track,
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        Text(
                            if (d.paused) "已暂停" else "${(d.progress * 100).toInt()}% · ${SpFormat.speed(d.speed)}",
                            style = style(11.sp, WColors.secondary),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

class DownloadsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DownloadsWidget()
}
