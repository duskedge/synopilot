package io.github.duskedge.synopilot.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 设备基本信息（SYNO.DSM.Info getinfo） */
@Serializable
data class SystemInfo(
    val model: String,
    val serial: String,
    val versionString: String,
    val ramMb: Long,
    val temperatureC: Int?,
    val temperatureWarn: Boolean,
    val uptimeSec: Long,
)

/** 实时负载（SYNO.Core.System.Utilization get） */
@Serializable
data class Utilization(
    val cpuPercent: Int,
    val load1: Double?,
    val memoryPercent: Int,
    val memoryTotalKb: Long,
    val memoryAvailableKb: Long,
    val memoryCachedKb: Long,
    /** 所有网卡合计，字节/秒 */
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long,
)

enum class Health { Ok, Warning, Error, Unknown }

@Serializable
data class StoragePool(
    val id: String,
    val name: String,
    val status: String,
    val health: Health,
    val raidType: String,
    val totalBytes: Long,
    val usedBytes: Long,
)

@Serializable
data class Volume(
    val id: String,
    val path: String,
    val status: String,
    val health: Health,
    val fsType: String,
    val totalBytes: Long,
    val usedBytes: Long,
)

@Serializable
data class Disk(
    val id: String,
    val name: String,
    val model: String,
    val vendor: String,
    val serial: String,
    val temperatureC: Int?,
    val status: String,
    val smartStatus: String,
    val health: Health,
    val sizeBytes: Long,
    val isSsd: Boolean,
    /** M.2 / 缓存盘等不在普通盘位的硬盘 */
    val isCache: Boolean,
)

@Serializable
data class StorageInfo(
    val pools: List<StoragePool>,
    val volumes: List<Volume>,
    val disks: List<Disk>,
) {
    val totalBytes: Long get() = volumes.sumOf { it.totalBytes }
    val usedBytes: Long get() = volumes.sumOf { it.usedBytes }
}

@Serializable
data class UpsInfo(
    val enabled: Boolean,
    val onBattery: Boolean,
    val chargePercent: Int?,
    val runtimeSec: Long?,
    val model: String,
)

object DsmParsers {

    fun systemInfo(data: JsonElement): SystemInfo {
        val o = data.obj()
        return SystemInfo(
            model = o.str("model").orEmpty(),
            serial = o.str("serial").orEmpty(),
            versionString = o.str("version_string") ?: o.str("version").orEmpty(),
            ramMb = o.long("ram") ?: 0,
            temperatureC = o.int("temperature"),
            temperatureWarn = o.bool("temperature_warn") ?: false,
            uptimeSec = o.long("uptime") ?: 0,
        )
    }

    fun utilization(data: JsonElement): Utilization {
        val o = data.obj()
        val cpu = o.child("cpu")
        val cpuPercent = listOf("user_load", "system_load", "other_load").sumOf { cpu.int(it) ?: 0 }.coerceIn(0, 100)
        val mem = o.child("memory")
        val nets = o.array("network")
        val total = nets.firstOrNull { it.str("device") == "total" }
        val rx = total?.long("rx") ?: nets.sumOf { it.long("rx") ?: 0 }
        val tx = total?.long("tx") ?: nets.sumOf { it.long("tx") ?: 0 }
        return Utilization(
            cpuPercent = cpuPercent,
            load1 = cpu.double("1min_load")?.let { it / 100.0 },
            memoryPercent = (mem.int("real_usage") ?: 0).coerceIn(0, 100),
            memoryTotalKb = mem.long("total_real") ?: mem.long("memory_size") ?: 0,
            memoryAvailableKb = mem.long("avail_real") ?: 0,
            memoryCachedKb = mem.long("cached") ?: 0,
            rxBytesPerSec = rx,
            txBytesPerSec = tx,
        )
    }

    fun storage(data: JsonElement): StorageInfo {
        val o = data.obj()
        val pools = o.array("storagePools").map { p ->
            val size = p.child("size")
            StoragePool(
                id = p.str("id").orEmpty(),
                name = p.int("num_id")?.let { "存储池 $it" } ?: p.str("id").orEmpty(),
                status = p.str("status").orEmpty(),
                health = health(p.str("status")),
                raidType = p.str("raidType") ?: p.str("device_type").orEmpty(),
                totalBytes = size.long("total") ?: 0,
                usedBytes = size.long("used") ?: 0,
            )
        }
        val volumes = o.array("volumes").map { v ->
            val size = v.child("size")
            Volume(
                id = v.str("id").orEmpty(),
                path = v.str("vol_path") ?: v.str("id").orEmpty(),
                status = v.str("status").orEmpty(),
                health = health(v.str("status")),
                fsType = v.str("fs_type").orEmpty(),
                totalBytes = size.long("total") ?: 0,
                usedBytes = size.long("used") ?: 0,
            )
        }
        val disks = o.array("disks").map { d ->
            val status = d.str("overview_status") ?: d.str("status").orEmpty()
            val smart = d.str("smart_status").orEmpty()
            val id = d.str("id").orEmpty()
            Disk(
                id = id,
                name = d.str("longName") ?: d.str("name") ?: id,
                model = d.str("model").orEmpty().trim(),
                vendor = d.str("vendor").orEmpty().trim(),
                serial = d.str("serial").orEmpty().trim(),
                temperatureC = d.int("temp")?.takeIf { it > 0 },
                status = status,
                smartStatus = smart,
                health = worst(health(status), health(smart.ifBlank { null })),
                sizeBytes = d.long("size_total") ?: 0,
                isSsd = d.bool("isSsd") ?: false,
                isCache = id.startsWith("nvme") || (d.str("diskType")?.contains("M.2", ignoreCase = true) == true),
            )
        }
        return StorageInfo(pools, volumes, disks)
    }

    fun ups(data: JsonElement): UpsInfo {
        val o = data.obj()
        val status = o.str("status").orEmpty()
        return UpsInfo(
            enabled = o.bool("enable") ?: false,
            onBattery = status.contains("onbatt", ignoreCase = true) || status.contains("on_battery", ignoreCase = true),
            chargePercent = o.int("charge"),
            runtimeSec = o.long("runtime"),
            model = o.str("model").orEmpty(),
        )
    }

    /** DSM 的状态字符串 → 健康等级（和 DSM 存储管理器的良好 / 警告 / 危险对应） */
    fun health(status: String?): Health {
        val s = status?.lowercase() ?: return Health.Unknown
        return when {
            s.isBlank() -> Health.Unknown
            s in setOf("normal", "healthy", "initialized", "ok", "background") -> Health.Ok
            listOf("crash", "fail", "broken", "critical", "damage", "unusable").any { it in s } -> Health.Error
            listOf("warn", "degrad", "abnormal", "attention", "repair", "sys_partition").any { it in s } -> Health.Warning
            s == "not_use" || s == "none" || s == "unknown" -> Health.Unknown
            else -> Health.Warning
        }
    }

    private fun worst(a: Health, b: Health): Health = listOf(Health.Error, Health.Warning, Health.Ok, Health.Unknown)
        .first { it == a || it == b }
}
