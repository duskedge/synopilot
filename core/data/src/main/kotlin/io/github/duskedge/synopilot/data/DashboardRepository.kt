package io.github.duskedge.synopilot.data

import io.github.duskedge.synopilot.network.DsmException
import io.github.duskedge.synopilot.network.DsmParsers
import io.github.duskedge.synopilot.network.StorageInfo
import io.github.duskedge.synopilot.network.SystemInfo
import io.github.duskedge.synopilot.network.UpsInfo
import io.github.duskedge.synopilot.network.Utilization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class NetSample(val time: Long, val rx: Long, val tx: Long)

@Serializable
data class DashboardData(
    val deviceId: String,
    val info: SystemInfo? = null,
    val utilization: Utilization? = null,
    val storage: StorageInfo? = null,
    val ups: UpsInfo? = null,
    val network: List<NetSample> = emptyList(),
    val updatedAt: Long = 0,
    /** 来自本地缓存（离线时显示），不是实时数据 */
    val fromCache: Boolean = false,
)

/**
 * 总览页数据：CPU / 内存 / 网络按设置的间隔刷新（默认 3 秒），
 * 系统信息、存储、UPS 每 30 秒刷新；最近一次数据缓存到本地，断网时先显示缓存。
 */
class DashboardRepository(
    private val connection: ConnectionManager,
    private val repository: DeviceRepository,
    private val cacheDir: File,
    private val snapshot: SnapshotStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observe(): Flow<DashboardData> = channelFlow {
        connection.state
            .map { it.deviceOrNull?.id to (it is ConnectionState.Connected) }
            .distinctUntilChanged()
            .collectLatest { (deviceId, connected) ->
                if (deviceId == null) return@collectLatest
                var data = readCache(deviceId)?.copy(fromCache = true) ?: DashboardData(deviceId)
                send(data)
                if (!connected) return@collectLatest

                val pollMs = repository.appSettings.first().pollSeconds.coerceIn(1, 60) * 1000L
                var lastSlow = 0L
                while (true) {
                    val now = clock()
                    try {
                        val util = connection.request { api, s ->
                            DsmParsers.utilization(api.call("SYNO.Core.System.Utilization", "get", 1, session = s))
                        }
                        val samples = (data.network + NetSample(now, util.rxBytesPerSec, util.txBytesPerSec)).takeLast(60)
                        data = data.copy(utilization = util, network = samples, updatedAt = now, fromCache = false)
                        snapshot?.update { it.copy(cpu = util.cpuPercent, memory = util.memoryPercent, temperature = data.info?.temperatureC ?: it.temperature) }

                        if (now - lastSlow >= SLOW_INTERVAL_MS) {
                            data = data.copy(
                                info = runCatching { connection.request { api, s -> DsmParsers.systemInfo(api.call("SYNO.DSM.Info", "getinfo", 2, session = s)) } }.getOrElse { data.info },
                                storage = optional { connection.request { api, s -> DsmParsers.storage(api.call("SYNO.Storage.CGI.Storage", "load_info", 1, session = s)) } } ?: data.storage,
                                ups = optional { connection.request { api, s -> DsmParsers.ups(api.call("SYNO.Core.ExternalDevice.UPS", "get", 1, session = s)) } } ?: data.ups,
                            )
                            lastSlow = now
                            writeCache(data)
                            data.storage?.let { st -> snapshot?.update { it.withStorage(st.totalBytes, st.usedBytes) } }
                        }
                        send(data)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 保留上一次的数据；连接层会负责重连
                    }
                    delay(pollMs)
                }
            }
    }

    /** 可选功能（没装套件、不是管理员等）失败时返回 null，不影响其他数据 */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: DsmException) {
        null
    }

    private fun cacheFile(deviceId: String) = File(cacheDir, "dashboard-$deviceId.json")

    private suspend fun readCache(deviceId: String): DashboardData? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<DashboardData>(cacheFile(deviceId).readText()) }.getOrNull()
    }

    private suspend fun writeCache(data: DashboardData) = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.mkdirs()
            cacheFile(data.deviceId).writeText(json.encodeToString(data.copy(fromCache = false)))
        }
    }

    private companion object {
        const val SLOW_INTERVAL_MS = 30_000L
    }
}
