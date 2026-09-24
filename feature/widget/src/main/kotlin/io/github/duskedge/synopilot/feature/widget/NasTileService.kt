package io.github.duskedge.synopilot.feature.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.duskedge.synopilot.data.AppLinks
import io.github.duskedge.synopilot.data.DeviceRepository
import io.github.duskedge.synopilot.data.NasSnapshot
import io.github.duskedge.synopilot.data.SnapshotStore
import io.github.duskedge.synopilot.data.SystemRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 快捷设置磁贴：显示 NAS 是否在线；离线时点按网络唤醒，在线时打开 App。 */
class NasTileService : TileService(), KoinComponent {
    private val store: SnapshotStore by inject()
    private val system: SystemRepository by inject()
    private val devices: DeviceRepository by inject()
    private var scope: CoroutineScope? = null
    private var woke = false

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = MainScope().also { s -> s.launch { store.snapshot.collect { render(it) } } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        woke = false
        super.onStopListening()
    }

    private fun render(s: NasSnapshot) {
        val tile = qsTile ?: return
        tile.label = s.deviceName.ifBlank { "NAS" }
        val subtitle = when {
            s.deviceId.isEmpty() -> "未添加"
            s.online -> listOfNotNull("在线", s.cpu?.let { "CPU $it%" }).joinToString(" · ")
            woke -> "已发送唤醒信号"
            s.canWake -> "离线 · 点按唤醒"
            else -> "离线"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle else tile.label = "${tile.label} · $subtitle"
        tile.state = if (s.online) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val s = store.snapshot.value
        if (!s.online && s.canWake) {
            scope?.launch {
                val device = devices.currentDevice.first() ?: return@launch
                woke = runCatching { system.wake(device) }.getOrDefault(false)
                render(store.snapshot.value)
            }
            return
        }
        val intent = AppLinks.open(this, AppLinks.DASHBOARD) ?: return
        openApp(intent)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp(intent: android.content.Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
