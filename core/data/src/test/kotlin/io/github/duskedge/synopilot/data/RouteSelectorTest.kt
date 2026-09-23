package io.github.duskedge.synopilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSelectorTest {
    private val primary = "https://nas.homelab.me:5001"
    private val backup = "http://192.168.1.100:5000"

    @Test
    fun `自动：选延迟低的`() {
        val r = RouteSelector.choose(PreferRoute.Auto, primary to 64L, backup to 3L)!!
        assertEquals(Slot.Backup, r.slot)
        assertEquals(AddressKind.Lan, r.kind)
    }

    @Test
    fun `优先主地址：两个都通时用主地址`() {
        assertEquals(Slot.Primary, RouteSelector.choose(PreferRoute.Primary, primary to 64L, backup to 3L)!!.slot)
    }

    @Test
    fun `优先的地址不通时用另一个`() {
        assertEquals(Slot.Primary, RouteSelector.choose(PreferRoute.Backup, primary to 64L, backup to null)!!.slot)
    }

    @Test
    fun `都不通或都没配置`() {
        assertNull(RouteSelector.choose(PreferRoute.Auto, primary to null, backup to null))
        assertNull(RouteSelector.choose(PreferRoute.Auto, null, null))
    }

    @Test
    fun `地址类型`() {
        assertEquals(AddressKind.Lan, Addresses.kindOf("http://192.168.1.100:5000"))
        assertEquals(AddressKind.Lan, Addresses.kindOf("https://10.0.0.2:5001"))
        assertEquals(AddressKind.Lan, Addresses.kindOf("https://172.20.1.3"))
        assertEquals(AddressKind.Domain, Addresses.kindOf("https://172.32.1.3"))
        assertEquals(AddressKind.Lan, Addresses.kindOf("http://ds923.local:5000"))
        assertEquals(AddressKind.Tailscale, Addresses.kindOf("https://100.101.7.12:5001"))
        assertEquals(AddressKind.Tailscale, Addresses.kindOf("https://ds923.tail1234.ts.net"))
        assertEquals(AddressKind.Domain, Addresses.kindOf("https://100.200.1.1"))
        assertEquals(AddressKind.QuickConnect, Addresses.kindOf("https://homelab.quickconnect.to"))
        assertEquals(AddressKind.Domain, Addresses.kindOf("https://nas.homelab.me:5001/"))
    }

    @Test
    fun `地址格式`() {
        assertTrue(Addresses.isValid("https://nas.example.com"))
        assertFalse(Addresses.isValid("nas.example.com"))
        assertFalse(Addresses.isValid("https://"))
        assertFalse(Addresses.isValid("https://a b.com"))
    }

    @Test
    fun `地址历史：最近使用的排最前，最多 12 条`() {
        var d = Device(id = "1", name = "n", account = "a", serial = "S")
        for (i in 1..15) d = d.withAddressUsed("https://h$i", now = i.toLong())
        assertEquals(12, d.addressHistory.size)
        assertEquals("https://h15", d.addressHistory.first().url)
        d = d.withAddressUsed("https://h10", now = 100)
        assertEquals("https://h10", d.addressHistory.first().url)
        assertEquals(100L, d.addressHistory.first().lastUsedAt)
        assertEquals(12, d.addressHistory.size)
        assertEquals("S", d.addressHistory.first().serial)
    }
}
