package io.github.duskedge.synopilot.data

enum class Slot { Primary, Backup, QuickConnect }

data class Route(val slot: Slot, val url: String, val kind: AddressKind, val latencyMs: Long)

/** 路线选择规则（纯函数，便于测试）。latency 为 null 表示这个地址连不上。 */
object RouteSelector {
    fun choose(prefer: PreferRoute, primary: Pair<String, Long?>?, backup: Pair<String, Long?>?): Route? {
        val ok = buildList {
            primary?.let { (url, lat) -> if (lat != null) add(Route(Slot.Primary, url, Addresses.kindOf(url), lat)) }
            backup?.let { (url, lat) -> if (lat != null) add(Route(Slot.Backup, url, Addresses.kindOf(url), lat)) }
        }
        if (ok.isEmpty()) return null
        return when (prefer) {
            PreferRoute.Auto -> ok.minBy { it.latencyMs }
            PreferRoute.Primary -> ok.firstOrNull { it.slot == Slot.Primary } ?: ok.first()
            PreferRoute.Backup -> ok.firstOrNull { it.slot == Slot.Backup } ?: ok.first()
        }
    }
}
