package io.github.duskedge.synopilot.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.duskedge.synopilot.network.BackupResult
import io.github.duskedge.synopilot.network.ContainerState
import io.github.duskedge.synopilot.network.DockerApi
import io.github.duskedge.synopilot.network.DsmParsers
import io.github.duskedge.synopilot.network.Health
import io.github.duskedge.synopilot.network.SystemApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class AlertRule(val label: String, val description: String) {
    ContainerExited("容器异常退出", "退出码不为 0 的容器"),
    DiskHealth("硬盘健康", "硬盘状态变为警告或损毁"),
    VolumeUsage("存储空间不足", "存储空间使用超过 90%"),
    UpsOnBattery("UPS 电池供电", "市电中断，UPS 开始用电池供电"),
    BackupFailed("备份失败", "Hyper Backup 任务失败"),
    CertExpiring("证书即将到期", "HTTPS 证书 14 天内到期"),
    DsmUpdate("DSM 更新", "有新的 DSM 版本可以安装"),
}

enum class AlertLevel { Info, Warning, Error }

@Serializable
data class Alert(
    val id: String,
    /** 同一个问题的唯一标识，比如 container:nginx；问题持续期间不重复提醒 */
    val key: String,
    val deviceId: String,
    val deviceName: String,
    val rule: AlertRule,
    val level: AlertLevel,
    val title: String,
    val body: String,
    val time: Long,
    val read: Boolean = false,
)

/** 当前存在的问题（检查一次得到的结果） */
data class AlertCondition(val key: String, val rule: AlertRule, val level: AlertLevel, val title: String, val body: String)

@Serializable
private data class AlertState(
    val alerts: List<Alert> = emptyList(),
    /** 设备 id → 当前仍存在的问题 key */
    val active: Map<String, Set<String>> = emptyMap(),
    /** 设备 id → 上次检查 DSM 更新的时间 */
    val updateCheckedAt: Map<String, Long> = emptyMap(),
)

private val Context.alertStore by preferencesDataStore("alerts")

/** 通知中心的数据：最多保留 100 条。 */
class AlertStore(context: Context) {
    private val store = context.alertStore
    private val key = stringPreferencesKey("state")
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(raw: String?): AlertState = raw?.let { runCatching { json.decodeFromString<AlertState>(it) }.getOrNull() } ?: AlertState()

    val alerts: Flow<List<Alert>> = store.data.map { decode(it[key]).alerts }
    val unread: Flow<Int> = alerts.map { list -> list.count { !it.read } }

    private suspend fun update(transform: (AlertState) -> AlertState): AlertState {
        var result = AlertState()
        store.edit { p ->
            result = transform(decode(p[key]))
            p[key] = json.encodeToString(AlertState.serializer(), result)
        }
        return result
    }

    /**
     * 记录这次检查的结果，返回新出现的告警。
     * 已经存在的问题不会重复提醒；问题消失后再出现会再提醒。
     */
    suspend fun apply(device: Device, conditions: List<AlertCondition>, now: Long = System.currentTimeMillis()): List<Alert> {
        var raised = emptyList<Alert>()
        update { state ->
            val previous = state.active[device.id].orEmpty()
            raised = conditions.filter { it.key !in previous }.map {
                Alert(UUID.randomUUID().toString(), it.key, device.id, device.name, it.rule, it.level, it.title, it.body, now)
            }
            state.copy(
                alerts = (raised + state.alerts).take(MAX_ALERTS),
                active = state.active + (device.id to conditions.map { it.key }.toSet()),
            )
        }
        return raised
    }

    suspend fun shouldCheckUpdate(deviceId: String, now: Long = System.currentTimeMillis()): Boolean {
        var due = false
        update { s ->
            due = now - (s.updateCheckedAt[deviceId] ?: 0) > UPDATE_INTERVAL_MS
            if (due) s.copy(updateCheckedAt = s.updateCheckedAt + (deviceId to now)) else s
        }
        return due
    }

    suspend fun markAllRead() {
        update { s -> s.copy(alerts = s.alerts.map { it.copy(read = true) }) }
    }

    suspend fun clear() {
        update { s -> s.copy(alerts = emptyList()) }
    }

    companion object {
        const val MAX_ALERTS = 100
        private const val UPDATE_INTERVAL_MS = 12 * 3600_000L
    }
}

/** 检查当前连接的 NAS 有没有需要提醒的问题。 */
class AlertChecker(
    private val connection: ConnectionManager,
    private val repository: DeviceRepository,
    private val store: AlertStore,
    private val notifier: AlertNotifier,
) {
    /** 返回新出现的告警数量 */
    suspend fun check(notify: Boolean = true): Int {
        val connected = connection.state.value as? ConnectionState.Connected ?: return 0
        val device = connected.device
        val disabled = repository.appSettings.first().disabledAlertRules
        val conditions = collect(device).filter { it.rule.name !in disabled }
        val raised = store.apply(device, conditions)
        if (notify && raised.isNotEmpty()) notifier.notify(raised)
        return raised.size
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = runCatching { block() }.getOrNull()

    private suspend fun collect(device: Device): List<AlertCondition> = buildList {
        optional {
            connection.request { api, s -> if (api.supports(DockerApi.CONTAINER)) DockerApi.containers(api, s) else emptyList() }
        }?.filter { it.state == ContainerState.Exited }?.forEach {
            add(AlertCondition("container:${it.name}", AlertRule.ContainerExited, AlertLevel.Error, "容器 ${it.name} 异常退出", "退出码 ${it.exitCode ?: "未知"} · ${it.image}"))
        }
        optional { connection.request { api, s -> DsmParsers.storage(api.call("SYNO.Storage.CGI.Storage", "load_info", 1, session = s)) } }?.let { st ->
            st.disks.filter { it.health == Health.Warning || it.health == Health.Error }.forEach {
                add(
                    AlertCondition(
                        "disk:${it.id}:${it.health}", AlertRule.DiskHealth,
                        if (it.health == Health.Error) AlertLevel.Error else AlertLevel.Warning,
                        "${it.name} ${if (it.health == Health.Error) "已损毁" else "需要关注"}",
                        "${it.vendor} ${it.model}".trim(),
                    ),
                )
            }
            st.volumes.filter { it.totalBytes > 0 && it.usedBytes * 100 / it.totalBytes >= 90 }.forEach {
                val pct = it.usedBytes * 100 / it.totalBytes
                add(AlertCondition("volume:${it.id}", AlertRule.VolumeUsage, AlertLevel.Warning, "${it.path} 空间不足", "已使用 $pct%"))
            }
        }
        optional { connection.request { api, s -> DsmParsers.ups(api.call("SYNO.Core.ExternalDevice.UPS", "get", 1, session = s)) } }
            ?.takeIf { it.enabled && it.onBattery }?.let {
                add(AlertCondition("ups:battery", AlertRule.UpsOnBattery, AlertLevel.Error, "UPS 正在用电池供电", "电量 ${it.chargePercent ?: "?"}%，市电可能中断"))
            }
        optional { connection.request { api, s -> if (api.supports(SystemApi.BACKUP)) SystemApi.backups(api, s) else emptyList() } }
            ?.filter { it.result == BackupResult.Failed }?.forEach {
                add(AlertCondition("backup:${it.id}:${it.lastTime}", AlertRule.BackupFailed, AlertLevel.Error, "备份任务「${it.name}」失败", "请在 Hyper Backup 里查看原因"))
            }
        val now = System.currentTimeMillis() / 1000
        optional { connection.request { api, s -> SystemApi.certificates(api, s) } }
            ?.mapNotNull { c -> c.validTill?.let { c to it } }
            ?.filter { (_, till) -> till - now < 14 * 86_400 }?.forEach { (c, till) ->
                val days = ((till - now) / 86_400).coerceAtLeast(0)
                add(AlertCondition("cert:${c.id}", AlertRule.CertExpiring, AlertLevel.Warning, "证书 ${c.subject} ${if (days == 0L) "今天" else "$days 天后"}到期", "颁发者 ${c.issuer}"))
            }
        if (store.shouldCheckUpdate(device.id)) {
            optional { connection.request { api, s -> SystemApi.checkUpdate(api, s) } }?.takeIf { it.available }?.let {
                add(AlertCondition("update:${it.version}", AlertRule.DsmUpdate, AlertLevel.Info, "${it.version ?: "新的 DSM 版本"} 可以安装", "在 DSM 的「控制面板 → 更新和还原」里安装"))
            }
        } else {
            // 没到检查时间时保留之前的更新提醒，避免被当作「已解决」
            store.alerts.first().firstOrNull { it.deviceId == device.id && it.rule == AlertRule.DsmUpdate }?.let {
                add(AlertCondition(it.key, it.rule, it.level, it.title, it.body))
            }
        }
    }
}

class AlertNotifier(private val context: Context) {
    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "NAS 告警", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "容器退出、硬盘、空间、UPS、备份等问题"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notify(alerts: List<Alert>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return)
            .putExtra(EXTRA_OPEN_ALERTS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentIntent(pending)
            .setAutoCancel(true)
        val notification = if (alerts.size == 1) {
            val a = alerts.single()
            builder.setContentTitle(a.title).setContentText("${a.deviceName} · ${a.body}").build()
        } else {
            val style = NotificationCompat.InboxStyle()
            alerts.take(6).forEach { style.addLine(it.title) }
            builder.setContentTitle("${alerts.first().deviceName} 有 ${alerts.size} 条新告警")
                .setContentText(alerts.joinToString("、") { it.title })
                .setStyle(style)
                .build()
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "nas_alerts"
        const val EXTRA_OPEN_ALERTS = "io.github.duskedge.synopilot.OPEN_ALERTS"
        private const val NOTIFICATION_ID = 2001
    }
}

/** 每 15 分钟在后台检查一次（系统允许的最短间隔）。 */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val repository: DeviceRepository by inject()
    private val connection: ConnectionManager by inject()
    private val checker: AlertChecker by inject()

    override suspend fun doWork(): Result {
        if (!repository.appSettings.first().alertsEnabled) return Result.success()
        if (repository.currentDevice.first() == null) return Result.success()
        // App 冷启动时连接层刚开始连，最多等 45 秒
        withTimeoutOrNull(45_000) { connection.state.first { it is ConnectionState.Connected || it is ConnectionState.Failed } }
        runCatching { checker.check() }.onFailure { Log.w(TAG, "告警检查失败", it) }
        return Result.success()
    }

    companion object {
        private const val TAG = "AlertWorker"
        private const val WORK_NAME = "nas-alerts"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
