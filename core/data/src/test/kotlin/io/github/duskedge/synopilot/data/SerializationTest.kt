package io.github.duskedge.synopilot.data

import io.github.duskedge.synopilot.network.Health
import io.github.duskedge.synopilot.network.StorageInfo
import io.github.duskedge.synopilot.network.Volume
import io.github.duskedge.synopilot.network.download.EngineKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 持久化用到的类都要能序列化往返。
 * （曾经踩过的坑：@Serializable 类里放 private companion object，运行时会 IllegalAccessError。）
 */
class SerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `设备列表往返`() {
        val d = Device(id = "1", name = "DS923+", account = "admin", primaryUrl = "https://nas.example.com", pinnedCerts = listOf("ab"),
            downloaders = listOf(DownloaderConfig("q", EngineKind.QBittorrent, "qB", lanUrl = "http://192.168.1.2:8080")), defaultDownloaderId = "q")
            .withAddressUsed("https://nas.example.com", 5)
        val list = listOf(d)
        assertEquals(list, json.decodeFromString<List<Device>>(json.encodeToString(list)))
    }

    @Test
    fun `凭证和设置往返`() {
        val s = DeviceSecrets("pw", "sid", "tok", "did", mapOf("q" to "qbpw"))
        assertEquals(s, json.decodeFromString<DeviceSecrets>(json.encodeToString(s)))
        val a = AppSettings(biometricLock = true, pollSeconds = 10)
        assertEquals(a, json.decodeFromString<AppSettings>(json.encodeToString(a)))
    }

    @Test
    fun `总览缓存往返`() {
        val data = DashboardData(
            deviceId = "1",
            storage = StorageInfo(emptyList(), listOf(Volume("v1", "/volume1", "normal", Health.Ok, "btrfs", 10, 5)), emptyList()),
            network = listOf(NetSample(1, 2, 3)),
            updatedAt = 9,
        )
        assertEquals(data, json.decodeFromString<DashboardData>(json.encodeToString(data)))
    }

    @Test
    fun `旧版本数据缺字段时用默认值`() {
        val d = json.decodeFromString<Device>("""{"id":"1","name":"n","account":"a"}""")
        assertEquals(PreferRoute.Auto, d.prefer)
        assertEquals(true, d.quickConnectFallback)
    }
}
