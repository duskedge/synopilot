package io.github.duskedge.synopilot.data

import io.github.duskedge.synopilot.network.download.EngineKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloaderRoutesTest {
    private val qb = DownloaderConfig("q", EngineKind.QBittorrent, "qB", lanUrl = "http://192.168.1.10:8080")
    private fun route(url: String) = Route(Slot.Primary, url, Addresses.kindOf(url), 10)

    @Test
    fun `局域网直接用局域网地址`() {
        assertEquals(DownloaderRoute.Direct("http://192.168.1.10:8080"), DownloaderRoutes.resolve(qb, route("http://192.168.1.10:5000")))
    }

    @Test
    fun `Tailscale 换成 Tailscale 主机，端口不变`() {
        assertEquals(
            DownloaderRoute.Direct("http://100.101.1.2:8080"),
            DownloaderRoutes.resolve(qb, route("https://100.101.1.2:5001")),
        )
        assertEquals("https://nas.tail1.ts.net:9091/transmission/rpc", DownloaderRoutes.replaceHost("https://1.2.3.4:9091/transmission/rpc", "nas.tail1.ts.net"))
    }

    @Test
    fun `外网没有外网地址时不可用`() {
        val r = DownloaderRoutes.resolve(qb, route("https://abc.quickconnect.to"))
        assertEquals(true, r is DownloaderRoute.Unavailable)
        assertEquals(
            DownloaderRoute.Direct("https://qb.example.com"),
            DownloaderRoutes.resolve(qb.copy(remoteUrl = "https://qb.example.com"), route("https://nas.example.com:5001")),
        )
    }

    @Test
    fun `Download Station 总是走 DSM`() {
        assertEquals(DownloaderRoute.Dsm, DownloaderRoutes.resolve(DownloaderConfig("d", EngineKind.DownloadStation, "DS"), null))
    }
}
