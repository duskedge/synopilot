package io.github.duskedge.synopilot.network.download

import io.github.duskedge.synopilot.network.DsmApi
import io.github.duskedge.synopilot.network.arr
import io.github.duskedge.synopilot.network.double
import io.github.duskedge.synopilot.network.int
import io.github.duskedge.synopilot.network.long
import io.github.duskedge.synopilot.network.obj
import io.github.duskedge.synopilot.network.str
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * qBittorrent WebUI API v2。
 * - 登录后拿会话 Cookie：旧版叫 SID，5.x 起叫 QBT_SID_<端口>；请求必须带 Referer，否则会被 CSRF 校验拒绝；
 * - 5.1 起登录成功返回 204（没有「Ok.」）；
 * - WebAPI ≥ 2.11（qBittorrent 5.0）起 pause/resume 改名为 stop/start。
 */
class QBittorrentClient(
    override val id: String,
    private val http: HttpClient,
    baseUrl: String,
    private val username: String,
    private val password: String,
) : Downloader {
    override val kind = EngineKind.QBittorrent
    private val base = DsmApi.normalizeBaseUrl(baseUrl)
    private val mutex = Mutex()
    /** 完整的「名字=值」，如 SID=xxx 或 QBT_SID_8080=xxx */
    private var sessionCookie: String? = null
    private var webApiVersion: String? = null

    private suspend fun login() {
        val response = http.post("$base/api/v2/auth/login") {
            header(HttpHeaders.Referrer, base)
            setBody(FormDataContent(Parameters.build { append("username", username); append("password", password) }))
        }
        val body = response.bodyAsText().trim()
        if (response.status == HttpStatusCode.Forbidden) throw DownloaderException("登录失败次数过多，qBittorrent 暂时封禁了这个 IP", authFailed = true)
        if (!response.status.isSuccess() || body.equals("Fails.", ignoreCase = true)) throw DownloaderException("qBittorrent 用户名或密码不正确", authFailed = true)
        sessionCookie = sessionCookie(response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
        // 关闭了身份验证（或白名单免登录）时没有会话 Cookie，直接可用
    }

    /** 带登录态发请求；403 时重新登录一次 */
    private suspend fun request(block: suspend (cookie: String?) -> HttpResponse): HttpResponse {
        mutex.withLock { if (sessionCookie == null) login() }
        var response = block(sessionCookie)
        if (response.status == HttpStatusCode.Forbidden) {
            mutex.withLock { login() }
            response = block(sessionCookie)
        }
        if (response.status == HttpStatusCode.Forbidden) {
            throw DownloaderException("qBittorrent 拒绝了请求（403）：登录后仍然没有权限，检查反向代理是否转发了 Cookie，以及 WebUI 的「Host 头验证」设置")
        }
        if (!response.status.isSuccess()) throw DownloaderException("qBittorrent 请求失败（HTTP ${response.status.value}）")
        return response
    }

    private suspend fun get(path: String): String = request { cookie ->
        http.get("$base/api/v2/$path") {
            header(HttpHeaders.Referrer, base)
            cookie?.let { header(HttpHeaders.Cookie, it) }
        }
    }.bodyAsText()

    private suspend fun post(path: String, form: Map<String, String>): String = request { cookie ->
        http.post("$base/api/v2/$path") {
            header(HttpHeaders.Referrer, base)
            cookie?.let { header(HttpHeaders.Cookie, it) }
            setBody(FormDataContent(Parameters.build { form.forEach { (k, v) -> append(k, v) } }))
        }
    }.bodyAsText()

    private suspend fun apiVersion(): String = webApiVersion ?: get("app/webapiVersion").trim().also { webApiVersion = it }

    /** WebAPI 2.11 起用 stop/start */
    private suspend fun usesStopStart(): Boolean {
        val parts = apiVersion().split('.').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        return major > 2 || (major == 2 && minor >= 11)
    }

    override suspend fun version() = EngineVersion(app = get("app/version").trim(), api = apiVersion())

    override suspend fun list(): List<DownloadTask> = parseTorrents(id, parse(get("torrents/info")))

    override suspend fun add(urls: List<String>, savePath: String?, category: String?) {
        val body = request { cookie ->
            http.post("$base/api/v2/torrents/add") {
                header(HttpHeaders.Referrer, base)
                cookie?.let { header(HttpHeaders.Cookie, it) }
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("urls", urls.joinToString("\n"))
                            if (!savePath.isNullOrBlank()) append("savepath", savePath)
                            if (!category.isNullOrBlank()) append("category", category)
                        },
                    ),
                )
            }
        }.bodyAsText().trim()
        if (body.equals("Fails.", ignoreCase = true)) throw DownloaderException("qBittorrent 没有接受这个任务（可能已存在或链接无效）")
    }

    override suspend fun pause(ids: List<String>) {
        post(if (usesStopStart()) "torrents/stop" else "torrents/pause", mapOf("hashes" to ids.joinToString("|")))
    }

    override suspend fun resume(ids: List<String>) {
        post(if (usesStopStart()) "torrents/start" else "torrents/resume", mapOf("hashes" to ids.joinToString("|")))
    }

    override suspend fun remove(ids: List<String>, deleteFiles: Boolean) {
        post("torrents/delete", mapOf("hashes" to ids.joinToString("|"), "deleteFiles" to deleteFiles.toString()))
    }

    override suspend fun transfer(): TransferInfo {
        val o = parse(get("transfer/info")).obj()
        return TransferInfo(
            downloadSpeed = o.long("dl_info_speed") ?: 0,
            uploadSpeed = o.long("up_info_speed") ?: 0,
            downloadLimit = o.long("dl_rate_limit")?.takeIf { it > 0 },
            uploadLimit = o.long("up_rate_limit")?.takeIf { it > 0 },
        )
    }

    override suspend fun setLimits(download: Long?, upload: Long?) {
        post("transfer/setDownloadLimit", mapOf("limit" to (download ?: 0).toString()))
        post("transfer/setUploadLimit", mapOf("limit" to (upload ?: 0).toString()))
    }

    override suspend fun categories(): List<Category> =
        parse(get("torrents/categories")).obj()?.values?.mapNotNull { it.obj() }
            ?.map { Category(it.str("name").orEmpty(), it.str("savePath").orEmpty()) }
            ?.sortedBy { it.name }
            .orEmpty()

    companion object {
        /** 从 Set-Cookie 里找会话 Cookie（SID 或 QBT_SID_<端口>），返回「名字=值」 */
        internal fun sessionCookie(setCookies: List<String>): String? = setCookies
            .mapNotNull { it.substringBefore(';').trim().takeIf { c -> '=' in c } }
            .firstOrNull { c -> c.substringBefore('=').let { it == "SID" || it.startsWith("QBT_SID") } }

        /** qBittorrent 用 8640000 表示「无穷大」 */
        private const val ETA_INFINITY = 8_640_000L

        internal fun parse(body: String): JsonElement = try {
            DsmApi.json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw DownloaderException("qBittorrent 返回的数据格式不对")
        }

        fun parseTorrents(engineId: String, data: JsonElement): List<DownloadTask> =
            data.arr().orEmpty().mapNotNull { it.obj() }.map { t ->
                val hash = t.str("hash").orEmpty()
                DownloadTask(
                    key = "$engineId:$hash",
                    engineId = engineId,
                    nativeId = hash,
                    name = t.str("name").orEmpty(),
                    sizeBytes = t.long("size") ?: 0,
                    progress = (t.double("progress") ?: 0.0).coerceIn(0.0, 1.0),
                    downloadSpeed = t.long("dlspeed") ?: 0,
                    uploadSpeed = t.long("upspeed") ?: 0,
                    etaSec = t.long("eta")?.takeIf { it in 0 until ETA_INFINITY },
                    state = TaskStates.qbittorrent(t.str("state")),
                    ratio = t.double("ratio"),
                    savePath = t.str("save_path").orEmpty(),
                    seeds = t.int("num_seeds"),
                    peers = t.int("num_leechs"),
                    category = t.str("category")?.takeIf { it.isNotBlank() },
                    error = null,
                )
            }
    }
}
