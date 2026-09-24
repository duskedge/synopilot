package io.github.duskedge.synopilot.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun `魔术包：6 个 FF 加 16 次 MAC`() {
        val p = WakeOnLan.packet("00:11:32:AB:CD:EF")
        assertEquals(102, p.size)
        assertEquals(List(6) { 0xFF.toByte() }, p.take(6))
        assertEquals(listOf(0x00, 0x11, 0x32, 0xAB, 0xCD, 0xEF).map { it.toByte() }, p.drop(96))
    }

    @Test
    fun `广播地址`() {
        assertEquals(listOf("255.255.255.255", "192.168.1.255"), WakeOnLan.broadcastTargets("192.168.1.10"))
        assertEquals(listOf("255.255.255.255"), WakeOnLan.broadcastTargets("nas.local"))
    }
}
