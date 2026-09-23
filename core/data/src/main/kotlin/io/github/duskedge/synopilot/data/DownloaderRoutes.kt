package io.github.duskedge.synopilot.data

import io.github.duskedge.synopilot.network.download.EngineKind

sealed interface DownloaderRoute {
    /** 走 DSM 会话（Download Station） */
    data object Dsm : DownloaderRoute
    data class Direct(val url: String) : DownloaderRoute
    data class Unavailable(val reason: String) : DownloaderRoute
}

/** 根据当前连接 NAS 的线路，决定下载器用哪个地址（纯函数，便于测试）。 */
object DownloaderRoutes {
    fun resolve(config: DownloaderConfig, route: Route?): DownloaderRoute {
        if (config.kind == EngineKind.DownloadStation) return DownloaderRoute.Dsm
        if (route == null) return DownloaderRoute.Unavailable("未连接到 NAS")
        val lan = config.lanUrl.trim()
        val remote = config.remoteUrl.trim()
        return when (route.kind) {
            AddressKind.Lan -> if (lan.isNotBlank()) DownloaderRoute.Direct(lan) else remoteOrUnavailable(remote, "没有填写局域网地址")
            AddressKind.Tailscale ->
                if (lan.isNotBlank()) DownloaderRoute.Direct(replaceHost(lan, Addresses.host(route.url))) else remoteOrUnavailable(remote, "没有填写局域网地址")
            AddressKind.Domain, AddressKind.QuickConnect -> remoteOrUnavailable(remote, "外网不可用：没有配置外网地址")
        }
    }

    private fun remoteOrUnavailable(remote: String, reason: String) =
        if (remote.isNotBlank()) DownloaderRoute.Direct(remote) else DownloaderRoute.Unavailable(reason)

    fun replaceHost(url: String, host: String): String {
        val scheme = url.substringBefore("://", "http")
        val rest = url.substringAfter("://")
        val authority = rest.substringBefore('/')
        val path = rest.removePrefix(authority)
        val port = authority.substringAfter(':', "").takeIf { it.isNotBlank() }
        return "$scheme://$host${port?.let { ":$it" }.orEmpty()}$path"
    }
}
