package io.github.duskedge.synopilot.data

import io.github.duskedge.synopilot.network.DockerApi
import io.github.duskedge.synopilot.network.DsmException
import io.github.duskedge.synopilot.network.download.Category
import io.github.duskedge.synopilot.network.download.DownloadStationClient
import io.github.duskedge.synopilot.network.download.DownloadTask
import io.github.duskedge.synopilot.network.download.Downloader
import io.github.duskedge.synopilot.network.download.DownloaderException
import io.github.duskedge.synopilot.network.download.EngineKind
import io.github.duskedge.synopilot.network.download.EngineVersion
import io.github.duskedge.synopilot.network.download.QBittorrentClient
import io.github.duskedge.synopilot.network.download.TransferInfo
import io.github.duskedge.synopilot.network.download.TransmissionClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class EngineStatus(
    val config: DownloaderConfig,
    val tasks: List<DownloadTask> = emptyList(),
    val transfer: TransferInfo? = null,
    /** 连不上 / 出错的原因；为 null 表示正常 */
    val problem: String? = null,
    val authFailed: Boolean = false,
    val loaded: Boolean = false,
)

data class DownloadsData(
    val engines: List<EngineStatus> = emptyList(),
    val defaultEngineId: String = "",
    val loaded: Boolean = false,
) {
    val tasks: List<DownloadTask> get() = engines.flatMap { it.tasks }
}

/** 自动发现到的候选下载器 */
data class DiscoveredDownloader(val config: DownloaderConfig, val source: String)

/**
 * 多下载器聚合：按设置的间隔并发拉取每个启用的下载器，合并成一个列表。
 * 单个下载器出错只影响它自己。
 */
class DownloadsRepository(
    private val connection: ConnectionManager,
    private val repository: DeviceRepository,
    private val http: HttpClient,
) {
    private val refresh = Channel<Unit>(Channel.CONFLATED)
    private val clientsLock = Mutex()
    private val clients = mutableMapOf<String, Pair<ClientKey, Downloader>>()

    /** 每个下载器最近一次成功拉到的状态；断线时继续显示（标记为不可用） */
    private val lastGood = ConcurrentHashMap<String, EngineStatus>()

    private data class ClientKey(val config: DownloaderConfig, val route: DownloaderRoute, val password: String)

    fun refreshNow() {
        refresh.trySend(Unit)
    }

    fun observe(): Flow<DownloadsData> = channelFlow {
        combine(connection.state, repository.currentDevice) { state, device ->
            val route = (state as? ConnectionState.Connected)?.route
            Triple(device, route, state is ConnectionState.Connected)
        }.distinctUntilChanged().collectLatest { (device, route, connected) ->
            if (device == null) {
                send(DownloadsData(loaded = true))
                return@collectLatest
            }
            val enabled = device.downloaders.filter { it.enabled }
            var data = DownloadsData(enabled.map { EngineStatus(it) }, device.defaultDownloaderId, loaded = enabled.isEmpty())
            send(data)
            if (!connected || enabled.isEmpty()) {
                if (!connected) {
                    val cached = enabled.map { cfg ->
                        (lastGood[cfg.id]?.copy(config = cfg, transfer = null) ?: EngineStatus(cfg))
                            .copy(problem = "未连接到 NAS", loaded = true)
                    }
                    send(data.copy(engines = cached, loaded = true))
                }
                return@collectLatest
            }
            val pollMs = repository.appSettings.first().pollSeconds.coerceIn(2, 60) * 1000L
            while (true) {
                val statuses = coroutineScope {
                    enabled.map { cfg -> async { poll(device, cfg, route) } }.awaitAll()
                }
                statuses.filter { it.problem == null }.forEach { lastGood[it.config.id] = it }
                data = data.copy(engines = statuses, loaded = true)
                send(data)
                withTimeoutOrNull(pollMs) { refresh.receive() }
            }
        }
    }

    private suspend fun poll(device: Device, cfg: DownloaderConfig, route: Route?): EngineStatus {
        fun failed(problem: String?, authFailed: Boolean = false) =
            EngineStatus(cfg, tasks = lastGood[cfg.id]?.tasks.orEmpty(), problem = problem ?: "连接失败", authFailed = authFailed, loaded = true)
        return try {
            val client = client(device, cfg, route)
            coroutineScope {
                val tasks = async { client.list() }
                val transfer = async { runCatching { client.transfer() }.getOrNull() }
                EngineStatus(cfg, tasks.await(), transfer.await(), loaded = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Unavailable) {
            failed(e.message)
        } catch (e: DownloaderException) {
            failed(e.message, e.authFailed)
        } catch (e: DsmException) {
            failed(if (e.code == 102) "这台 NAS 没有安装 Download Station" else e.message)
        } catch (e: Exception) {
            failed(describe(e))
        }
    }

    private class Unavailable(message: String) : Exception(message)

    private suspend fun client(device: Device, cfg: DownloaderConfig, route: Route?): Downloader {
        val resolved = DownloaderRoutes.resolve(cfg, route)
        if (resolved is DownloaderRoute.Unavailable) throw Unavailable(resolved.reason)
        val password = repository.secrets(device.id)?.downloaderPasswords?.get(cfg.id).orEmpty()
        val key = ClientKey(cfg, resolved, password)
        return clientsLock.withLock {
            clients[cfg.id]?.takeIf { it.first == key }?.second
                ?: build(cfg, resolved, password).also { clients[cfg.id] = key to it }
        }
    }

    private fun build(cfg: DownloaderConfig, route: DownloaderRoute, password: String): Downloader = when (cfg.kind) {
        EngineKind.DownloadStation -> DownloadStationClient(cfg.id) { block -> connection.request(block) }
        EngineKind.QBittorrent -> QBittorrentClient(cfg.id, http, (route as DownloaderRoute.Direct).url, cfg.username, password)
        EngineKind.Transmission -> TransmissionClient(cfg.id, http, (route as DownloaderRoute.Direct).url, cfg.username, password)
    }

    private suspend fun clientFor(engineId: String): Downloader {
        val device = repository.currentDevice.first() ?: throw DownloaderException("没有选择 NAS")
        val cfg = device.downloaders.firstOrNull { it.id == engineId } ?: throw DownloaderException("下载器不存在")
        val route = (connection.state.value as? ConnectionState.Connected)?.route
        return try {
            client(device, cfg, route)
        } catch (e: Unavailable) {
            throw DownloaderException(e.message ?: "下载器不可用")
        }
    }

    /** 按下载器分组后批量操作 */
    private suspend fun byEngine(tasks: List<DownloadTask>, action: suspend (Downloader, List<String>) -> Unit) {
        try {
            tasks.groupBy { it.engineId }.forEach { (engineId, list) -> action(clientFor(engineId), list.map { it.nativeId }) }
        } finally {
            refreshNow()
        }
    }

    suspend fun pause(tasks: List<DownloadTask>) = byEngine(tasks) { c, ids -> c.pause(ids) }
    suspend fun resume(tasks: List<DownloadTask>) = byEngine(tasks) { c, ids -> c.resume(ids) }
    suspend fun remove(tasks: List<DownloadTask>, deleteFiles: Boolean) = byEngine(tasks) { c, ids -> c.remove(ids, deleteFiles) }

    suspend fun add(engineId: String, urls: List<String>, savePath: String?, category: String?) {
        try {
            clientFor(engineId).add(urls, savePath, category)
        } finally {
            refreshNow()
        }
    }

    suspend fun setLimits(engineId: String, download: Long?, upload: Long?) {
        try {
            clientFor(engineId).setLimits(download, upload)
        } finally {
            refreshNow()
        }
    }

    suspend fun categories(engineId: String): List<Category> = runCatching { clientFor(engineId).categories() }.getOrDefault(emptyList())

    /** 设置页「测试连接」：用填写的配置（未保存）试一下 */
    suspend fun test(config: DownloaderConfig, password: String?): Result<EngineVersion> = runCatching {
        val device = repository.currentDevice.first() ?: throw DownloaderException("没有选择 NAS")
        val route = (connection.state.value as? ConnectionState.Connected)?.route
        val resolved = DownloaderRoutes.resolve(config, route)
        if (resolved is DownloaderRoute.Unavailable) throw DownloaderException(resolved.reason)
        val pwd = password ?: repository.secrets(device.id)?.downloaderPasswords?.get(config.id).orEmpty()
        try {
            build(config, resolved, pwd).version()
        } catch (e: DownloaderException) {
            throw e
        } catch (e: DsmException) {
            throw DownloaderException(if (e.code == 102) "这台 NAS 没有安装 Download Station" else e.message ?: "连接失败")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DownloaderException(describe(e))
        }
    }

    /** 保存下载器；[password] 为 null 表示不修改密码 */
    suspend fun save(config: DownloaderConfig, password: String?) {
        val device = repository.currentDevice.first() ?: return
        repository.update(device.id) { d ->
            val list = if (d.downloaders.any { it.id == config.id }) {
                d.downloaders.map { if (it.id == config.id) config else it }
            } else {
                d.downloaders + config
            }
            d.copy(downloaders = list, defaultDownloaderId = d.defaultDownloaderId.ifBlank { config.id })
        }
        if (password != null) {
            repository.secrets(device.id)?.let { s ->
                repository.saveSecrets(device.id, s.copy(downloaderPasswords = s.downloaderPasswords + (config.id to password)))
            }
        }
        clientsLock.withLock { clients.remove(config.id) }
    }

    suspend fun delete(id: String) {
        val device = repository.currentDevice.first() ?: return
        repository.update(device.id) { d ->
            val list = d.downloaders.filterNot { it.id == id }
            d.copy(downloaders = list, defaultDownloaderId = if (d.defaultDownloaderId == id) list.firstOrNull()?.id.orEmpty() else d.defaultDownloaderId)
        }
        repository.secrets(device.id)?.let { s -> repository.saveSecrets(device.id, s.copy(downloaderPasswords = s.downloaderPasswords - id)) }
        clientsLock.withLock { clients.remove(id) }
    }

    suspend fun setDefault(id: String) {
        val device = repository.currentDevice.first() ?: return
        repository.update(device.id) { it.copy(defaultDownloaderId = id) }
    }

    /** 自动发现：Download Station 套件，以及跑着 qBittorrent / Transmission 的容器。已添加的不重复返回。 */
    suspend fun discover(): List<DiscoveredDownloader> {
        val device = repository.currentDevice.first() ?: return emptyList()
        val connected = connection.state.value as? ConnectionState.Connected ?: return emptyList()
        val host = device.lanHost ?: Addresses.host(connected.route.url)
        val found = mutableListOf<DiscoveredDownloader>()
        val hasDs = runCatching { connection.request { api, _ -> api.supports(DownloadStationClient.TASK) } }.getOrDefault(false)
        if (hasDs && device.downloaders.none { it.kind == EngineKind.DownloadStation }) {
            found += DiscoveredDownloader(DownloaderConfig(newId(), EngineKind.DownloadStation, "Download Station"), "套件")
        }
        val containers = runCatching { connection.request { api, s -> if (api.supports(DockerApi.CONTAINER)) DockerApi.containers(api, s) else emptyList() } }
            .getOrDefault(emptyList())
        for (c in containers) {
            val image = c.image.lowercase()
            val (kind, webPort, label) = when {
                "qbittorrent" in image -> Triple(EngineKind.QBittorrent, 8080, "qBittorrent")
                "transmission" in image -> Triple(EngineKind.Transmission, 9091, "Transmission")
                else -> continue
            }
            if (device.downloaders.any { it.containerName == c.name }) continue
            val tcp = c.ports.filter { it.protocol == "tcp" && it.hostPort != null }
            val port = (tcp.firstOrNull { it.containerPort == webPort } ?: tcp.firstOrNull())?.hostPort
                ?: webPort.takeIf { c.ports.isEmpty() } // host 网络模式没有端口映射
                ?: continue
            found += DiscoveredDownloader(
                DownloaderConfig(newId(), kind, label, lanUrl = "http://$host:$port", username = if (kind == EngineKind.QBittorrent) "admin" else "", containerName = c.name),
                "容器 ${c.name}",
            )
        }
        return found
    }

    private fun newId() = UUID.randomUUID().toString().take(8)

    private fun describe(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException, is io.ktor.client.plugins.HttpRequestTimeoutException -> "连接超时"
        is java.net.ConnectException, is java.net.UnknownHostException -> "无法连接"
        else -> e.message ?: "连接失败"
    }
}
