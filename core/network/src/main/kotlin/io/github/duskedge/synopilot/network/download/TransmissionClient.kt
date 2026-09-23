package io.github.duskedge.synopilot.network.download

import io.github.duskedge.synopilot.network.DsmApi
import io.github.duskedge.synopilot.network.array
import io.github.duskedge.synopilot.network.child
import io.github.duskedge.synopilot.network.double
import io.github.duskedge.synopilot.network.int
import io.github.duskedge.synopilot.network.long
import io.github.duskedge.synopilot.network.obj
import io.github.duskedge.synopilot.network.str
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Transmission RPC。
 * 第一次请求会返回 409 和 X-Transmission-Session-Id，带上后重发；之后再 409 说明会话过期，刷新后重试。
 */
class TransmissionClient(
    override val id: String,
    private val http: HttpClient,
    baseUrl: String,
    private val username: String,
    private val password: String,
) : Downloader {
    override val kind = EngineKind.Transmission
    private val endpoint = DsmApi.normalizeBaseUrl(baseUrl).let { if (it.endsWith("/transmission/rpc")) it else "$it/transmission/rpc" }
    private var sessionId: String? = null

    private suspend fun rpc(method: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = buildJsonObject {
            put("method", method)
            put("arguments", arguments)
        }.toString()
        suspend fun send(): HttpResponse = http.post(endpoint) {
            if (username.isNotBlank()) basicAuth(username, password)
            sessionId?.let { header(SESSION_HEADER, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        var response = send()
        if (response.status == HttpStatusCode.Conflict) {
            sessionId = response.headers[SESSION_HEADER]
            response = send()
        }
        if (response.status == HttpStatusCode.Unauthorized) throw DownloaderException("Transmission 用户名或密码不正确", authFailed = true)
        if (!response.status.isSuccess()) throw DownloaderException("Transmission 请求失败（HTTP ${response.status.value}）")
        val root = try {
            DsmApi.json.parseToJsonElement(response.bodyAsText()).obj()
        } catch (e: Exception) {
            null
        } ?: throw DownloaderException("Transmission 返回的数据格式不对")
        val result = root.str("result")
        if (result != "success") throw DownloaderException("Transmission：${result ?: "未知错误"}")
        return root.child("arguments") ?: JsonObject(emptyMap())
    }

    override suspend fun version(): EngineVersion {
        val a = rpc("session-get")
        return EngineVersion(app = a.str("version").orEmpty(), api = "RPC ${a.str("rpc-version").orEmpty()}")
    }

    override suspend fun list(): List<DownloadTask> {
        val args = buildJsonObject { put("fields", JsonArray(FIELDS.map(::JsonPrimitive))) }
        return parseTorrents(id, rpc("torrent-get", args))
    }

    override suspend fun add(urls: List<String>, savePath: String?, category: String?) {
        for (url in urls) {
            val a = rpc(
                "torrent-add",
                buildJsonObject {
                    put("filename", url)
                    put("paused", false)
                    if (!savePath.isNullOrBlank()) put("download-dir", savePath)
                    if (!category.isNullOrBlank()) put("labels", JsonArray(listOf(JsonPrimitive(category))))
                },
            )
            if (a.containsKey("torrent-duplicate")) throw DownloaderException("任务已存在")
        }
    }

    override suspend fun pause(ids: List<String>) {
        rpc("torrent-stop", idsArg(ids))
    }

    override suspend fun resume(ids: List<String>) {
        rpc("torrent-start", idsArg(ids))
    }

    override suspend fun remove(ids: List<String>, deleteFiles: Boolean) {
        rpc(
            "torrent-remove",
            buildJsonObject {
                put("ids", JsonArray(ids.map { JsonPrimitive(it.toLongOrNull() ?: 0) }))
                put("delete-local-data", deleteFiles)
            },
        )
    }

    override suspend fun transfer(): TransferInfo {
        val stats = rpc("session-stats")
        val session = rpc("session-get")
        return TransferInfo(
            downloadSpeed = stats.long("downloadSpeed") ?: 0,
            uploadSpeed = stats.long("uploadSpeed") ?: 0,
            downloadLimit = if (session.str("speed-limit-down-enabled") == "true") session.long("speed-limit-down")?.times(KB) else null,
            uploadLimit = if (session.str("speed-limit-up-enabled") == "true") session.long("speed-limit-up")?.times(KB) else null,
        )
    }

    override suspend fun setLimits(download: Long?, upload: Long?) {
        rpc(
            "session-set",
            buildJsonObject {
                put("speed-limit-down-enabled", download != null)
                put("speed-limit-up-enabled", upload != null)
                // Transmission 的单位是 kB/s（1000 字节）
                download?.let { put("speed-limit-down", (it / KB).coerceAtLeast(1)) }
                upload?.let { put("speed-limit-up", (it / KB).coerceAtLeast(1)) }
            },
        )
    }

    private fun idsArg(ids: List<String>) = buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it.toLongOrNull() ?: 0) })) }

    companion object {
        private const val SESSION_HEADER = "X-Transmission-Session-Id"
        private const val KB = 1000L
        private val FIELDS = listOf(
            "id", "hashString", "name", "totalSize", "percentDone", "rateDownload", "rateUpload", "eta", "status",
            "uploadRatio", "downloadDir", "peersSendingToUs", "peersGettingFromUs", "error", "errorString", "labels",
        )

        fun parseTorrents(engineId: String, arguments: JsonElement): List<DownloadTask> =
            arguments.obj().array("torrents").map { t ->
                val id = t.str("id").orEmpty()
                val percent = (t.double("percentDone") ?: 0.0).coerceIn(0.0, 1.0)
                val errorCode = t.int("error")
                DownloadTask(
                    key = "$engineId:$id",
                    engineId = engineId,
                    nativeId = id,
                    name = t.str("name").orEmpty(),
                    sizeBytes = t.long("totalSize") ?: 0,
                    progress = percent,
                    downloadSpeed = t.long("rateDownload") ?: 0,
                    uploadSpeed = t.long("rateUpload") ?: 0,
                    etaSec = t.long("eta")?.takeIf { it >= 0 },
                    state = TaskStates.transmission(t.int("status"), percent, errorCode),
                    ratio = t.double("uploadRatio")?.takeIf { it >= 0 },
                    savePath = t.str("downloadDir").orEmpty(),
                    seeds = t.int("peersSendingToUs"),
                    peers = t.int("peersGettingFromUs"),
                    category = (t["labels"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content },
                    error = t.str("errorString")?.takeIf { errorCode != null && errorCode != 0 && it.isNotBlank() },
                )
            }
    }
}
