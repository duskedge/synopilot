package io.github.duskedge.synopilot.network.download

import io.github.duskedge.synopilot.network.DsmApi
import io.github.duskedge.synopilot.network.DsmSession
import io.github.duskedge.synopilot.network.array
import io.github.duskedge.synopilot.network.child
import io.github.duskedge.synopilot.network.int
import io.github.duskedge.synopilot.network.long
import io.github.duskedge.synopilot.network.obj
import io.github.duskedge.synopilot.network.str
import kotlinx.serialization.json.JsonElement

/**
 * Download Station：复用 DSM 会话，不需要单独登录。
 * [request] 由连接层提供（会话过期时会自动重新登录）。
 */
class DownloadStationClient(
    override val id: String,
    private val request: suspend (suspend (DsmApi, DsmSession) -> JsonElement) -> JsonElement,
) : Downloader {
    override val kind = EngineKind.DownloadStation

    private suspend fun call(api: String, method: String, params: Map<String, String> = emptyMap()): JsonElement =
        request { a, s -> a.call(api, method, 1, params, s) }

    override suspend fun version(): EngineVersion {
        val info = call(INFO, "getinfo").obj()
        return EngineVersion(app = info.str("version_string") ?: info.str("version").orEmpty(), api = "DSM WebAPI")
    }

    override suspend fun list(): List<DownloadTask> =
        parseTasks(id, call(TASK, "list", mapOf("additional" to "detail,transfer", "offset" to "0", "limit" to "-1")))

    override suspend fun add(urls: List<String>, savePath: String?, category: String?) {
        call(
            TASK,
            "create",
            buildMap {
                put("uri", urls.joinToString(","))
                if (!savePath.isNullOrBlank()) put("destination", savePath.trim('/'))
            },
        )
    }

    override suspend fun pause(ids: List<String>) {
        call(TASK, "pause", mapOf("id" to ids.joinToString(",")))
    }

    override suspend fun resume(ids: List<String>) {
        call(TASK, "resume", mapOf("id" to ids.joinToString(",")))
    }

    override suspend fun remove(ids: List<String>, deleteFiles: Boolean) {
        // Download Station 的删除不会删除已下载到共享文件夹的文件
        call(TASK, "delete", mapOf("id" to ids.joinToString(","), "force_complete" to "false"))
    }

    override suspend fun transfer(): TransferInfo {
        val stat = call(STATISTIC, "getinfo").obj()
        val config = runCatching { call(INFO, "getconfig").obj() }.getOrNull()
        return TransferInfo(
            downloadSpeed = stat.long("speed_download") ?: 0,
            uploadSpeed = stat.long("speed_upload") ?: 0,
            downloadLimit = config.long("bt_max_download")?.takeIf { it > 0 }?.times(1024),
            uploadLimit = config.long("bt_max_upload")?.takeIf { it > 0 }?.times(1024),
        )
    }

    override suspend fun setLimits(download: Long?, upload: Long?) {
        // Download Station 的单位是 KB/s，0 表示不限
        call(
            INFO,
            "setserverconfig",
            mapOf(
                "bt_max_download" to ((download ?: 0) / 1024).toString(),
                "bt_max_upload" to ((upload ?: 0) / 1024).toString(),
                "http_max_download" to ((download ?: 0) / 1024).toString(),
            ),
        )
    }

    companion object {
        const val TASK = "SYNO.DownloadStation.Task"
        const val INFO = "SYNO.DownloadStation.Info"
        const val STATISTIC = "SYNO.DownloadStation.Statistic"

        fun parseTasks(engineId: String, data: JsonElement): List<DownloadTask> =
            data.obj().array("tasks").map { t ->
                val id = t.str("id").orEmpty()
                val size = t.long("size") ?: 0
                val transfer = t.child("additional").child("transfer")
                val detail = t.child("additional").child("detail")
                val done = transfer.long("size_downloaded") ?: 0
                val up = transfer.long("size_uploaded") ?: 0
                val speed = transfer.long("speed_download") ?: 0
                DownloadTask(
                    key = "$engineId:$id",
                    engineId = engineId,
                    nativeId = id,
                    name = t.str("title").orEmpty(),
                    sizeBytes = size,
                    progress = if (size > 0) (done.toDouble() / size).coerceIn(0.0, 1.0) else 0.0,
                    downloadSpeed = speed,
                    uploadSpeed = transfer.long("speed_upload") ?: 0,
                    etaSec = if (speed > 0 && size > done) (size - done) / speed else null,
                    state = TaskStates.downloadStation(t.str("status")),
                    ratio = if (done > 0) up.toDouble() / done else null,
                    savePath = detail.str("destination").orEmpty(),
                    seeds = detail.int("connected_seeders"),
                    peers = detail.int("connected_leechers") ?: detail.int("connected_peers"),
                    category = null,
                    error = t.child("status_extra").str("error_detail"),
                )
            }
    }
}
