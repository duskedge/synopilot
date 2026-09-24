package io.github.duskedge.synopilot.data

import android.content.Context
import io.github.duskedge.synopilot.network.ContainerState
import io.github.duskedge.synopilot.network.DockerApi
import io.github.duskedge.synopilot.network.DsmParsers
import io.github.duskedge.synopilot.network.download.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DownloadBrief(val name: String, val progress: Float, val speed: Long, val paused: Boolean)

/** 桌面小部件、快捷设置磁贴、下载通知共用的「当前 NAS 概况」。 */
@Serializable
data class NasSnapshot(
    val deviceId: String = "",
    val deviceName: String = "",
    val model: String = "",
    val online: Boolean = false,
    val cpu: Int? = null,
    val memory: Int? = null,
    val storagePercent: Int? = null,
    val storageFreeBytes: Long? = null,
    val temperature: Int? = null,
    val runningContainers: Int? = null,
    val exitedContainers: Int? = null,
    val downloadSpeed: Long? = null,
    val uploadSpeed: Long? = null,
    val activeDownloads: Int? = null,
    val downloads: List<DownloadBrief> = emptyList(),
    val canWake: Boolean = false,
    /** 毫秒 */
    val updatedAt: Long = 0,
)

/**
 * 保存在文件里，进程重启后小部件也能显示。
 * 写文件和通知监听者都做了合并（数据变化很频繁）。
 */
class SnapshotStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "snapshot.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _snapshot = MutableStateFlow(runCatching { json.decodeFromString<NasSnapshot>(file.readText()) }.getOrDefault(NasSnapshot()))
    val snapshot: StateFlow<NasSnapshot> = _snapshot.asStateFlow()

    /** 由小部件模块设置：数据变化后刷新桌面小部件、磁贴 */
    @Volatile var onChanged: (suspend (NasSnapshot) -> Unit)? = null

    private var pending: Job? = null
    private var lastNotified = 0L

    fun update(transform: (NasSnapshot) -> NasSnapshot) {
        val before = _snapshot.value
        _snapshot.update { transform(it).copy(updatedAt = System.currentTimeMillis()) }
        if (_snapshot.value.copy(updatedAt = 0) == before.copy(updatedAt = 0)) return
        schedule()
    }

    /** 立即写入并通知（后台任务结束前调用，避免进程被回收时丢失） */
    suspend fun flush() {
        pending?.cancel()
        persistAndNotify()
    }

    private fun schedule() {
        if (pending?.isActive == true) return
        pending = scope.launch {
            // 小部件没必要秒级刷新：最多每 20 秒一次
            val wait = (MIN_INTERVAL_MS - (System.currentTimeMillis() - lastNotified)).coerceAtLeast(1_000)
            delay(wait)
            persistAndNotify()
        }
    }

    private suspend fun persistAndNotify() {
        val s = _snapshot.value
        lastNotified = System.currentTimeMillis()
        withContext(Dispatchers.IO) { runCatching { file.writeText(json.encodeToString(NasSnapshot.serializer(), s)) } }
        runCatching { onChanged?.invoke(s) }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 20_000L
    }
}

/** 连接状态变化时更新概况；后台任务里用 [refresh] 主动拉一次数据。 */
class SnapshotUpdater(
    private val connection: ConnectionManager,
    private val downloads: DownloadsRepository,
    private val store: SnapshotStore,
) {
    fun start(scope: CoroutineScope) {
        // 每次连上后拉一次完整数据（小部件、下载通知用）
        scope.launch {
            connection.state.collect { s -> if (s is ConnectionState.Connected) scope.launch { refresh() } }
        }
        scope.launch {
            connection.state.collect { s ->
                val device = s.deviceOrNull
                store.update { old ->
                    if (device == null) {
                        NasSnapshot()
                    } else {
                        val same = old.deviceId == device.id
                        val base = if (same) old else NasSnapshot()
                        base.copy(
                            deviceId = device.id,
                            deviceName = device.name,
                            model = device.model,
                            online = s is ConnectionState.Connected,
                            canWake = device.macAddresses.isNotEmpty(),
                        )
                    }
                }
            }
        }
    }

    @Volatile private var refreshedAt = 0L

    /** App 回到前台时调用；30 秒内刷新过就跳过 */
    suspend fun refreshIfStale() {
        if (System.currentTimeMillis() - refreshedAt > 30_000) refresh()
    }

    /** 拉一次负载、存储、容器和下载（后台任务用） */
    suspend fun refresh() {
        if (connection.state.value !is ConnectionState.Connected) return
        refreshedAt = System.currentTimeMillis()
        runCatching {
            val u = connection.request { api, s -> DsmParsers.utilization(api.call("SYNO.Core.System.Utilization", "get", 1, session = s)) }
            store.update { it.copy(cpu = u.cpuPercent, memory = u.memoryPercent) }
        }
        runCatching {
            val st = connection.request { api, s -> DsmParsers.storage(api.call("SYNO.Storage.CGI.Storage", "load_info", 1, session = s)) }
            store.update { it.withStorage(st.totalBytes, st.usedBytes) }
        }
        runCatching {
            val list = connection.request { api, s -> if (api.supports(DockerApi.CONTAINER)) DockerApi.containers(api, s) else null }
            if (list != null) store.update { it.withContainers(list.count { c -> c.state == ContainerState.Running }, list.count { c -> c.state == ContainerState.Exited }) }
        }
        runCatching { downloads.pollOnce()?.let { store.update { s -> s.withDownloads(it) } } }
    }
}

fun NasSnapshot.withStorage(total: Long, used: Long) =
    if (total <= 0) this else copy(storagePercent = (used * 100 / total).toInt(), storageFreeBytes = total - used)

fun NasSnapshot.withContainers(running: Int, exited: Int) = copy(runningContainers = running, exitedContainers = exited)

fun NasSnapshot.withDownloads(data: DownloadsData): NasSnapshot {
    val live = data.engines.filter { it.problem == null }
    if (live.isEmpty() && data.engines.isNotEmpty()) return this
    val tasks = live.flatMap { it.tasks }
    val active = tasks.filter { it.state == TaskState.Downloading || it.state == TaskState.Checking }
    val paused = tasks.filter { it.state == TaskState.Paused }
    return copy(
        downloadSpeed = live.sumOf { e -> e.transfer?.downloadSpeed ?: e.tasks.sumOf { it.downloadSpeed } },
        uploadSpeed = live.sumOf { e -> e.transfer?.uploadSpeed ?: e.tasks.sumOf { it.uploadSpeed } },
        activeDownloads = active.size,
        downloads = (active.sortedByDescending { it.downloadSpeed } + paused).take(4)
            .map { DownloadBrief(it.name, it.progress.toFloat(), it.downloadSpeed, it.state == TaskState.Paused) },
    )
}
