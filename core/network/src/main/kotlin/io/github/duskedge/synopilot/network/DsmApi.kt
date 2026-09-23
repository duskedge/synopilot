package io.github.duskedge.synopilot.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 登录后的会话：之后的请求都带上 _sid 和 X-SYNO-TOKEN。 */
data class DsmSession(val sid: String, val synoToken: String?)

/** 一个 API 在这台 DSM 上的路径和版本范围（来自 query.cgi）。 */
data class ApiEntry(val path: String, val minVersion: Int, val maxVersion: Int)

/**
 * DSM WebAPI 客户端，绑定一个地址（如 https://192.168.1.100:5001）。
 * 所有请求用表单 POST 发送，避免密码等参数出现在 URL 里。
 */
class DsmApi(
    private val http: HttpClient,
    baseUrl: String,
) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)
    private val mutex = Mutex()
    private var apis: Map<String, ApiEntry>? = null

    suspend fun apiInfo(): Map<String, ApiEntry> = mutex.withLock {
        apis ?: loadApiInfo().also { apis = it }
    }

    suspend fun supports(api: String): Boolean = api in apiInfo()

    private suspend fun loadApiInfo(): Map<String, ApiEntry> {
        val response = http.get("$baseUrl/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=all")
        if (!response.status.isSuccess()) throw DsmException(0, "SYNO.API.Info", "连接 NAS 失败（HTTP ${response.status.value}）")
        val data = parseEnvelope(response.bodyAsText(), "SYNO.API.Info").obj()
            ?: throw DsmException(0, "SYNO.API.Info", "这个地址不是群晖 DSM")
        return data.mapNotNull { (name, value) ->
            val o = value.obj() ?: return@mapNotNull null
            val path = o.str("path") ?: return@mapNotNull null
            name to ApiEntry(path, o.int("minVersion") ?: 1, o.int("maxVersion") ?: 1)
        }.toMap()
    }

    /**
     * 调用一个 API，返回 data 部分（没有 data 时为 JsonNull）。
     * [version] 是期望的版本，会被限制在这台 DSM 支持的范围内。
     */
    suspend fun call(
        api: String,
        method: String,
        version: Int,
        params: Map<String, String> = emptyMap(),
        session: DsmSession? = null,
        timeoutMillis: Long? = null,
    ): JsonElement {
        val entry = apiInfo()[api] ?: throw DsmException(102, api)
        val v = minOf(version, entry.maxVersion)
        if (v < entry.minVersion) throw DsmException(104, api)

        val response = http.submitForm(
            url = "$baseUrl/webapi/${entry.path}",
            formParameters = parameters {
                append("api", api)
                append("version", v.toString())
                append("method", method)
                params.forEach { (k, value) -> append(k, value) }
                if (session != null) append("_sid", session.sid)
            },
        ) {
            session?.synoToken?.let { header("X-SYNO-TOKEN", it) }
            if (timeoutMillis != null) timeout { requestTimeoutMillis = timeoutMillis }
        }
        if (!response.status.isSuccess()) throw DsmException(0, api, "请求失败（HTTP ${response.status.value}）")
        return parseEnvelope(response.bodyAsText(), api)
    }

    /**
     * 调用返回「流」的方法（如 Compose 项目的 start_stream / build_stream），
     * 只关心是否成功，返回原始文本。
     */
    suspend fun callStream(
        api: String,
        method: String,
        version: Int,
        params: Map<String, String> = emptyMap(),
        session: DsmSession? = null,
        timeoutMillis: Long = 10 * 60_000,
    ): String {
        val entry = apiInfo()[api] ?: throw DsmException(102, api)
        val response = http.submitForm(
            url = "$baseUrl/webapi/${entry.path}",
            formParameters = parameters {
                append("api", api)
                append("version", minOf(version, entry.maxVersion).toString())
                append("method", method)
                params.forEach { (k, value) -> append(k, value) }
                if (session != null) append("_sid", session.sid)
            },
        ) {
            session?.synoToken?.let { header("X-SYNO-TOKEN", it) }
            timeout { requestTimeoutMillis = timeoutMillis }
        }
        if (!response.status.isSuccess()) throw DsmException(0, api, "请求失败（HTTP ${response.status.value}）")
        val body = response.bodyAsText()
        // 流式接口出错时仍会返回标准的错误信封
        if (body.trimStart().startsWith("{")) runCatching { parseEnvelope(body, api) }.onFailure { if (it is DsmException) throw it }
        return body
    }

    /**
     * 下载文件类接口（如 SYNO.FileStation.Download / Thumb）：把响应体写进 [sink]，返回写入的字节数。
     * DSM 出错时返回 JSON 错误信封，会转成 [DsmException]。[maxBytes] 用来限制预览时读取的大小。
     */
    suspend fun download(
        api: String,
        method: String,
        version: Int,
        params: Map<String, String>,
        session: DsmSession,
        sink: OutputStream,
        maxBytes: Long? = null,
        onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): Long {
        val entry = apiInfo()[api] ?: throw DsmException(102, api)
        return http.prepareGet("$baseUrl/webapi/${entry.path}") {
            parameter("api", api)
            parameter("version", minOf(version, entry.maxVersion))
            parameter("method", method)
            params.forEach { (k, v) -> parameter(k, v) }
            parameter("_sid", session.sid)
            session.synoToken?.let { header("X-SYNO-TOKEN", it) }
            timeout { requestTimeoutMillis = Long.MAX_VALUE }
        }.execute { response ->
            if (!response.status.isSuccess()) throw DsmException(0, api, "下载失败（HTTP ${response.status.value}）")
            if (response.contentType()?.match(ContentType.Application.Json) == true) {
                parseEnvelope(response.bodyAsText(), api)
                throw DsmException(0, api, "下载失败")
            }
            val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            while (true) {
                coroutineContext.ensureActive()
                val limit = maxBytes?.let { (it - done).coerceAtMost(buffer.size.toLong()).toInt() } ?: buffer.size
                if (limit <= 0) break
                val n = channel.readAvailable(buffer, 0, limit)
                if (n < 0) break
                sink.write(buffer, 0, n)
                done += n
                onProgress(done, total)
            }
            done
        }
    }

    /** SYNO.FileStation.Upload：multipart 上传一个文件到 [folder]。 */
    suspend fun upload(
        folder: String,
        fileName: String,
        size: Long?,
        session: DsmSession,
        overwrite: Boolean,
        open: () -> InputStream,
        onProgress: (sent: Long, total: Long?) -> Unit = { _, _ -> },
    ) {
        val api = "SYNO.FileStation.Upload"
        val entry = apiInfo()[api] ?: throw DsmException(102, api)
        val response = http.submitFormWithBinaryData(
            url = "$baseUrl/webapi/${entry.path}?api=$api&version=${minOf(2, entry.maxVersion)}&method=upload&_sid=${session.sid}",
            formData = formData {
                append("path", folder)
                append("create_parents", "true")
                append("overwrite", overwrite.toString())
                append(
                    "file",
                    ChannelProvider(size) { open().toByteReadChannel() },
                    Headers.build {
                        // Ktor 会自动加上 form-data; name="file"，这里只补文件名
                        append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                        append(HttpHeaders.ContentType, "application/octet-stream")
                    },
                )
            },
        ) {
            session.synoToken?.let { header("X-SYNO-TOKEN", it) }
            timeout { requestTimeoutMillis = Long.MAX_VALUE }
            onUpload { sent, total -> onProgress(sent, total) }
        }
        if (!response.status.isSuccess()) throw DsmException(0, api, "上传失败（HTTP ${response.status.value}）")
        parseEnvelope(response.bodyAsText(), api)
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun normalizeBaseUrl(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.endsWith("/webapi")) u = u.removeSuffix("/webapi")
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u
        }

        /** 解析 {"success":…, "data":…, "error":{"code":…}} */
        internal fun parseEnvelope(body: String, api: String): JsonElement {
            val root: JsonObject = try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw DsmException(0, api, "这个地址返回的不是 DSM 接口数据")
            }
            if (root.bool("success") == true) return root["data"] ?: JsonNull
            val code = root.child("error").int("code") ?: 100
            throw DsmException(code, api)
        }
    }
}
