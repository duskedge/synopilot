package io.github.duskedge.synopilot.data

import io.github.duskedge.synopilot.network.download.EngineKind
import kotlinx.serialization.Serializable

/** 两个地址都能连上时优先用哪个 */
enum class PreferRoute { Auto, Primary, Backup }

/** 地址类型，用于图标和「能否访问容器端口」的判断 */
enum class AddressKind { Lan, Tailscale, Domain, QuickConnect }

@Serializable
data class AddressRecord(
    val url: String,
    val lastUsedAt: Long,
    /** 这个地址对应设备的序列号；属于其他 NAS 的地址不能设为本机地址 */
    val serial: String = "",
    val model: String = "",
)

/** 一台 NAS 的配置。密码和会话在 [DeviceSecrets] 里单独加密保存。 */
@Serializable
data class Device(
    val id: String,
    val name: String,
    val account: String,
    val model: String = "",
    val serial: String = "",
    val dsmVersion: String = "",
    val primaryUrl: String = "",
    val backupUrl: String = "",
    val prefer: PreferRoute = PreferRoute.Auto,
    val quickConnectId: String = "",
    val quickConnectFallback: Boolean = true,
    /** 用户确认信任的证书指纹（SHA-256） */
    val pinnedCerts: List<String> = emptyList(),
    val addressHistory: List<AddressRecord> = emptyList(),
    val addedAt: Long = 0,
    val downloaders: List<DownloaderConfig> = emptyList(),
    /** 添加任务时默认使用的下载器 */
    val defaultDownloaderId: String = "",
) {
    /** 这台 NAS 在局域网里的主机名/IP（来自主地址或备用地址），用于拼容器端口地址 */
    val lanHost: String? get() = listOf(primaryUrl, backupUrl)
        .firstOrNull { it.isNotBlank() && Addresses.kindOf(it) == AddressKind.Lan }?.let(Addresses::host)

    val quickConnectUrl: String? get() = quickConnectId.takeIf { it.isNotBlank() }?.let { "https://$it.quickconnect.to" }

    fun withAddressUsed(url: String, now: Long): Device {
        val rest = addressHistory.filterNot { it.url == url }
        val record = addressHistory.firstOrNull { it.url == url }?.copy(lastUsedAt = now)
            ?: AddressRecord(url, now, serial, model)
        return copy(addressHistory = (listOf(record) + rest).take(MAX_ADDRESS_HISTORY))
    }
}

// 不能放进 @Serializable 类的 private companion：序列化插件会在 companion 上生成 serializer()
private const val MAX_ADDRESS_HISTORY = 12

@Serializable
data class DeviceSecrets(
    val password: String,
    val sid: String? = null,
    val synoToken: String? = null,
    /** 「信任这台设备」后 DSM 返回的令牌，带上后登录不再需要两步验证码 */
    val deviceToken: String? = null,
    /** 下载器 id → 密码 */
    val downloaderPasswords: Map<String, String> = emptyMap(),
)

/**
 * 一个下载器。qBittorrent / Transmission 跑在 NAS 的容器里，只能通过端口访问：
 * 局域网用 [lanUrl]；Tailscale 下把 [lanUrl] 的主机换成 Tailscale 地址；
 * 外网（域名 / QuickConnect）用 [remoteUrl]，没填就是「外网不可用」。
 * Download Station 走 DSM 会话，不需要地址。
 */
@Serializable
data class DownloaderConfig(
    val id: String,
    val kind: EngineKind,
    val name: String,
    val lanUrl: String = "",
    val remoteUrl: String = "",
    val username: String = "",
    /** 自动发现时对应的容器名 */
    val containerName: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class AppSettings(
    val biometricLock: Boolean = false,
    val pollSeconds: Int = 3,
)

object Addresses {
    private val LAN = Regex("""^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.)""")
    private val TAILSCALE_IP = Regex("""^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.""")

    fun host(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()

    fun kindOf(url: String): AddressKind {
        val h = host(url)
        return when {
            h.endsWith("quickconnect.to") -> AddressKind.QuickConnect
            h.endsWith(".ts.net") || TAILSCALE_IP.containsMatchIn(h) -> AddressKind.Tailscale
            LAN.containsMatchIn(h) || h.endsWith(".local") || h.endsWith(".lan") -> AddressKind.Lan
            else -> AddressKind.Domain
        }
    }

    fun isValid(url: String): Boolean {
        val u = url.trim()
        return (u.startsWith("http://") || u.startsWith("https://")) && host(u).isNotBlank() && ' ' !in u
    }
}
