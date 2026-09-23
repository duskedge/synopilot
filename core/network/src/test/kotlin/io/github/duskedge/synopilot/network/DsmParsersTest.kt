package io.github.duskedge.synopilot.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun fixture(name: String): String =
    requireNotNull(DsmParsersTest::class.java.getResource("/dsm/$name")) { "缺少测试数据 $name" }.readText()

internal fun data(name: String) = DsmApi.parseEnvelope(fixture(name), "test")

class DsmParsersTest {

    @Test
    fun `系统信息`() {
        val info = DsmParsers.systemInfo(data("dsm_info.json"))
        assertEquals("DS923+", info.model)
        assertEquals("2270R9X4K1B2", info.serial)
        assertEquals("DSM 7.2.2-72806 Update 2", info.versionString)
        assertEquals(44, info.temperatureC)
        assertEquals(3_334_032L, info.uptimeSec)
    }

    @Test
    fun `负载：CPU 为三项之和，网络优先取 total，字符串数字也能解析`() {
        val u = DsmParsers.utilization(data("utilization.json"))
        assertEquals(24, u.cpuPercent)
        assertEquals(0.84, u.load1!!, 0.001)
        assertEquals(38, u.memoryPercent)
        assertEquals(16_258_252L, u.memoryTotalKb)
        assertEquals(40_265_318L, u.rxBytesPerSec)
        assertEquals(6_500_000L, u.txBytesPerSec)
    }

    @Test
    fun `存储：池、卷、硬盘和健康状态`() {
        val s = DsmParsers.storage(data("storage.json"))
        assertEquals(1, s.pools.size)
        assertEquals("存储池 1", s.pools[0].name)
        assertEquals(23_440_890_000_000L, s.pools[0].totalBytes)
        assertEquals(14_300_000_000_000L, s.usedBytes)
        assertEquals(3, s.disks.size)
        val d1 = s.disks[0]
        assertEquals("ST8000NT001-3LZ101", d1.model)
        assertEquals("Seagate", d1.vendor)
        assertEquals(Health.Ok, d1.health)
        assertEquals(Health.Warning, s.disks[1].health)
        assertTrue(s.disks[2].isCache)
        assertTrue(s.disks[2].isSsd)
        assertFalse(d1.isCache)
    }

    @Test
    fun `健康状态映射`() {
        assertEquals(Health.Ok, DsmParsers.health("normal"))
        assertEquals(Health.Error, DsmParsers.health("crashed"))
        assertEquals(Health.Error, DsmParsers.health("failing"))
        assertEquals(Health.Warning, DsmParsers.health("degraded"))
        assertEquals(Health.Warning, DsmParsers.health("something_new"))
        assertEquals(Health.Unknown, DsmParsers.health("not_use"))
        assertEquals(Health.Unknown, DsmParsers.health(null))
    }

    @Test
    fun `字段缺失时不崩溃`() {
        val empty = DsmApi.parseEnvelope("""{"success":true,"data":{}}""", "x")
        assertEquals("", DsmParsers.systemInfo(empty).model)
        assertEquals(0, DsmParsers.utilization(empty).cpuPercent)
        assertTrue(DsmParsers.storage(empty).disks.isEmpty())
    }
}
