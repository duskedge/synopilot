package io.github.duskedge.synopilot.network

import android.content.Context
import android.net.ConnectivityManager
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address

data class DiscoveredNas(val host: String, val url: String, val latencyMs: Long?, val needsCertificateTrust: Boolean)

/**
 * 局域网发现：扫描当前 Wi-Fi 所在 /24 网段的 5000 端口（DSM 默认 HTTP 端口）。
 * 开了「HTTP 自动跳转 HTTPS」的 NAS 会跳到 5001，自签名证书同样算作发现。
 */
class LanDiscovery(private val context: Context, private val http: HttpClient) {

    fun localPrefix(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val address = props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: return null
        val parts = address.hostAddress?.split(".") ?: return null
        return if (parts.size == 4) parts.take(3).joinToString(".") else null
    }

    fun scan(): Flow<DiscoveredNas> = channelFlow {
        val prefix = localPrefix() ?: return@channelFlow
        val gate = Semaphore(48)
        coroutineScope {
            for (i in 1..254) {
                launch {
                    gate.withPermit {
                        val host = "$prefix.$i"
                        val r = DsmProbe.probe(http, "http://$host:5000", timeoutMillis = 800)
                        when {
                            r.ok -> send(DiscoveredNas(host, "http://$host:5000", r.latencyMs, needsCertificateTrust = false))
                            r.failure is ProbeFailure.UntrustedCertificate ->
                                send(DiscoveredNas(host, "https://$host:5001", null, needsCertificateTrust = true))
                            else -> Unit
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
