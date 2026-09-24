package io.github.duskedge.synopilot.feature.widget

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.glance.appwidget.updateAll
import io.github.duskedge.synopilot.data.SnapshotStore

/** 概况变化时刷新桌面小部件和快捷设置磁贴 */
object WidgetIntegration {
    fun install(context: Context, store: SnapshotStore) {
        val app = context.applicationContext
        DownloadProgressService.createChannel(app)
        store.onChanged = {
            runCatching { StatusWidget().updateAll(app) }
            runCatching { DownloadsWidget().updateAll(app) }
            runCatching { TileService.requestListeningState(app, ComponentName(app, NasTileService::class.java)) }
        }
    }
}
