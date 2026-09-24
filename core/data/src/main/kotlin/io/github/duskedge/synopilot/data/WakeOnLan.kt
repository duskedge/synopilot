package io.github.duskedge.synopilot.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** 网络唤醒：向局域网广播魔术包（6 个 0xFF + 16 次 MAC）。只在同一局域网里有效。 */
object WakeOnLan {
    fun packet(mac: String): ByteArray {
        val bytes = mac.split(':', '-').map { it.toInt(16).toByte() }
        require(bytes.size == 6) { "MAC 地址格式不对：$mac" }
        return ByteArray(6) { 0xFF.toByte() } + List(16) { bytes }.flatten().toByteArray()
    }

    /** 广播地址：全局广播，加上局域网地址所在 /24 网段的广播地址 */
    fun broadcastTargets(lanHost: String?): List<String> {
        val subnet = lanHost?.takeIf { Regex("""^\d+\.\d+\.\d+\.\d+$""").matches(it) }?.substringBeforeLast('.')?.let { "$it.255" }
        return listOfNotNull("255.255.255.255", subnet).distinct()
    }

    suspend fun send(macs: List<String>, lanHost: String?) = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (mac in macs) {
                val data = packet(mac)
                for (target in broadcastTargets(lanHost)) {
                    val address = InetAddress.getByName(target)
                    for (port in intArrayOf(9, 7)) socket.send(DatagramPacket(data, data.size, address, port))
                }
            }
        }
    }
}
