package io.github.duskedge.synopilot.updater

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** 每天在后台检查一次更新（需要联网）。 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val manager: UpdateManager by inject()

    override suspend fun doWork(): Result = try {
        manager.backgroundCheck()
        Result.success()
    } catch (e: UpdateException) {
        if (runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "update-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class UpdateNotifier(private val context: Context, private val config: UpdaterConfig) {

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "有新版本时提醒"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notifyAvailable(manifest: UpdateManifest, forced: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = Intent(context, config.launchActivity)
            .putExtra(EXTRA_OPEN_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("SynoPilot ${manifest.versionName} 可以更新了")
            .setContentText(if (forced) "当前版本已不再支持，请尽快更新" else manifest.notes.lineSequence().firstOrNull()?.removePrefix("- ") ?: "点击查看更新内容")
            .setStyle(NotificationCompat.BigTextStyle().bigText(manifest.notes.ifBlank { "点击查看更新内容" }))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "app_update"
        const val EXTRA_OPEN_UPDATE = "io.github.duskedge.synopilot.OPEN_UPDATE"
        private const val NOTIFICATION_ID = 1001
    }
}
