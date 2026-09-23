package io.github.duskedge.synopilot.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/** 访问 DSM 和下载器用的 HTTP 客户端：证书固定、合理超时、跟随跳转。 */
object DsmHttp {
    fun create(pins: TrustedPins, userAgent: String): HttpClient {
        val trustManager = PinningTrustManager(pins)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = true
            engine {
                config {
                    sslSocketFactory(ssl.socketFactory, trustManager)
                    hostnameVerifier(PinningHostnameVerifier(pins))
                    connectTimeout(8, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
            }
            defaultRequest {
                headers.append(HttpHeaders.UserAgent, userAgent)
            }
        }
    }
}
