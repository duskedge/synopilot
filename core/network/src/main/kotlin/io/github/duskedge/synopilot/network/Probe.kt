package io.github.duskedge.synopilot.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.time.TimeSource

sealed interface ProbeFailure {
    data object Timeout : ProbeFailure
    data class Unreachable(val message: String) : ProbeFailure
    data class UntrustedCertificate(val certificate: CertificateInfo) : ProbeFailure
    data object NotDsm : ProbeFailure
}

data class ProbeResult(val url: String, val latencyMs: Long?, val failure: ProbeFailure?) {
    val ok: Boolean get() = failure == null
}

/** 测试一个地址能否访问 DSM，并测量延迟（不需要登录）。 */
object DsmProbe {
    suspend fun probe(http: HttpClient, url: String, timeoutMillis: Long = 2_500): ProbeResult {
        val base = DsmApi.normalizeBaseUrl(url)
        val mark = TimeSource.Monotonic.markNow()
        return try {
            val response = http.get("$base/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth") {
                timeout {
                    requestTimeoutMillis = timeoutMillis
                    connectTimeoutMillis = timeoutMillis
                }
            }
            val elapsed = mark.elapsedNow().inWholeMilliseconds
            if (!response.status.isSuccess()) return ProbeResult(base, null, ProbeFailure.NotDsm)
            val data = runCatching { DsmApi.parseEnvelope(response.bodyAsText(), "SYNO.API.Info").obj() }.getOrNull()
            if (data?.containsKey(DsmException.AUTH_API) == true) {
                ProbeResult(base, elapsed, null)
            } else {
                ProbeResult(base, null, ProbeFailure.NotDsm)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ProbeResult(base, null, classify(e, base))
        }
    }

    fun classify(e: Throwable, url: String): ProbeFailure {
        e.findCause<UntrustedCertificateException>()?.let { return ProbeFailure.UntrustedCertificate(it.certificate) }
        if (e.findCause<SSLPeerUnverifiedException>() != null) {
            val host = runCatching { Url(url).host }.getOrNull()
            PinningHostnameVerifier.lastRejected[host]?.let { return ProbeFailure.UntrustedCertificate(it) }
        }
        if (e.findCause<HttpRequestTimeoutException>() != null || e.findCause<java.net.SocketTimeoutException>() != null) {
            return ProbeFailure.Timeout
        }
        return ProbeFailure.Unreachable(e.message ?: e::class.simpleName.orEmpty())
    }
}
