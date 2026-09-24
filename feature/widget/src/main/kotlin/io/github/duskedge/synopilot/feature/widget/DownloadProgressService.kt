package io.github.duskedge.synopilot.feature.widget

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.duskedge.synopilot.data.AppLinks
import io.github.duskedge.synopilot.data.DownloadsRepository
import io.github.duskedge.synopilot.data.NasSnapshot
import io.github.duskedge.synopilot.data.SnapshotStore
import io.github.duskedge.synopilot.data.withDownloads
import io.github.duskedge.synopilot.designsystem.SpFormat
import io.github.duskedge.synopilot.network.download.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 有进行中的下载时显示常驻通知（进度、速度、「全部暂停」）。
 * 没有下载后自动结束。
 */
class DownloadProgressService : Service(), KoinComponent {
    private val downloads: DownloadsRepository by inject()
    private val store: SnapshotStore by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // ServiceCompat 在 Android 10 以下会忽略前台服务类型，常量内联没有问题
    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(store.snapshot.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE_ALL) scope.launch { pauseAll() }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        var idle = 0
        while (scope.isActive) {
            val data = runCatching { downloads.pollOnce() }.getOrNull()
            if (data != null) store.update { it.withDownloads(data) }
            val active = if (data == null) 0 else store.snapshot.value.activeDownloads ?: 0
            idle = if (active == 0) idle + 1 else 0
            if (idle >= 2) {
                stopSelf()
                return
            }
            notify(store.snapshot.value)
            delay(3_000)
        }
    }

    private suspend fun pauseAll() {
        val data = runCatching { downloads.pollOnce() }.getOrNull() ?: return
        val tasks = data.tasks.filter { it.state == TaskState.Downloading || it.state == TaskState.Checking || it.state == TaskState.Queued }
        runCatching { downloads.pause(tasks) }
    }

    private fun notify(s: NasSnapshot) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(s))
    }

    private fun build(s: NasSnapshot): android.app.Notification {
        val active = s.downloads.filterNot { it.paused }
        val progress = if (active.isEmpty()) 0 else (active.sumOf { it.progress.toDouble() } / active.size * 100).toInt()
        val open = AppLinks.open(this, AppLinks.DOWNLOADS)?.let {
            PendingIntent.getActivity(this, 3, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val pause = PendingIntent.getService(
            this, 4, Intent(this, DownloadProgressService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val count = s.activeDownloads ?: active.size
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (count > 0) "正在下载 $count 个任务" else "下载")
            .setContentText(listOfNotNull(s.downloadSpeed?.let { "↓ ${SpFormat.speed(it)}" }, s.uploadSpeed?.takeIf { it > 0 }?.let { "↑ ${SpFormat.speed(it)}" }).joinToString("  "))
            .setSubText(s.deviceName.takeIf { it.isNotBlank() })
            .setProgress(100, progress, count > 0 && active.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "全部暂停", pause)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Android 15：dataSync 前台服务每天最多运行 6 小时，超时后结束 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "download_progress"
        private const val NOTIFICATION_ID = 3001
        private const val ACTION_PAUSE_ALL = "io.github.duskedge.synopilot.PAUSE_ALL"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "下载进度", NotificationManager.IMPORTANCE_LOW).apply {
                description = "有下载任务进行中时显示进度"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** App 在前台时调用；系统不允许启动时忽略 */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadProgressService::class.java)) }
        }
    }
}
