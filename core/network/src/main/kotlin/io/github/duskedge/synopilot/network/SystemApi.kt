package io.github.duskedge.synopilot.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---- 模型 ------------------------------------------------------------------

/** 定时开关机的一条规则。[weekdays] 0 = 周日 … 6 = 周六 */
@Serializable
data class PowerTask(val enabled: Boolean, val hour: Int, val minute: Int, val weekdays: Set<Int>)

data class PowerSchedule(val powerOn: List<PowerTask>, val powerOff: List<PowerTask>)

data class NetworkInterface(val name: String, val mac: String, val ip: String?)

data class DsmUpdate(val available: Boolean, val version: String?, val needsReboot: Boolean)

enum class BackupResult { Success, Failed, Running, Partial, Never, Unknown }

data class BackupTask(
    val id: String,
    val name: String,
    val target: String,
    val result: BackupResult,
    /** 秒；0 表示没有 */
    val lastTime: Long,
    val nextTime: Long,
    val progress: Int?,
)

data class ProcessInfo(val pid: Int, val name: String, val cpuPercent: Double, val memoryBytes: Long)

data class DiskIo(val name: String, val readBytesPerSec: Long, val writeBytesPerSec: Long, val utilization: Int)

data class ResourceDetail(val disks: List<DiskIo>, val volumes: List<DiskIo>)

data class BlockedIp(val ip: String, val recorded: Long, val expires: Long?)

data class LoginSession(
    val user: String,
    val from: String,
    val type: String,
    val description: String,
    val since: String,
    val canKick: Boolean,
    val pid: Int?,
)

data class Certificate(
    val id: String,
    val subject: String,
    val issuer: String,
    val validTill: Long?,
    val isDefault: Boolean,
    val alternateNames: List<String>,
)

// ---- 接口 ------------------------------------------------------------------

object SystemApi {
    const val SYSTEM = "SYNO.Core.System"
    const val POWER_SCHEDULE = "SYNO.Core.Hardware.PowerSchedule"
    const val ETHERNET = "SYNO.Core.Network.Ethernet"
    const val UPGRADE = "SYNO.Core.Upgrade.Server"
    const val BACKUP = "SYNO.Backup.Task"
    const val PROCESS = "SYNO.Core.System.Process"
    const val UTILIZATION = "SYNO.Core.System.Utilization"
    const val AUTO_BLOCK = "SYNO.Core.Security.AutoBlock.Rules"
    const val CONNECTIONS = "SYNO.Core.CurrentConnection"
    const val CERTIFICATES = "SYNO.Core.Certificate.CRT"

    suspend fun reboot(api: DsmApi, session: DsmSession) {
        api.call(SYSTEM, "reboot", 1, mapOf("force" to "false", "local" to "true"), session)
    }

    suspend fun shutdown(api: DsmApi, session: DsmSession) {
        api.call(SYSTEM, "shutdown", 1, mapOf("force" to "false", "local" to "true"), session)
    }

    suspend fun powerSchedule(api: DsmApi, session: DsmSession): PowerSchedule =
        SystemParsers.powerSchedule(api.call(POWER_SCHEDULE, "load", 1, session = session))

    suspend fun savePowerSchedule(api: DsmApi, session: DsmSession, schedule: PowerSchedule) {
        api.call(
            POWER_SCHEDULE, "save", 1,
            mapOf(
                "poweron_tasks" to SystemParsers.encodeTasks(schedule.powerOn).toString(),
                "poweroff_tasks" to SystemParsers.encodeTasks(schedule.powerOff).toString(),
            ),
            session,
        )
    }

    suspend fun interfaces(api: DsmApi, session: DsmSession): List<NetworkInterface> =
        SystemParsers.interfaces(api.call(ETHERNET, "list", 2, session = session))

    suspend fun checkUpdate(api: DsmApi, session: DsmSession): DsmUpdate =
        SystemParsers.update(api.call(UPGRADE, "check", 2, session = session, timeoutMillis = 60_000))

    suspend fun backups(api: DsmApi, session: DsmSession): List<BackupTask> {
        val additional = JsonArray(listOf("last_bkp_time", "next_bkp_time", "last_bkp_result", "last_bkp_progress", "target_type").map(::JsonPrimitive))
        return SystemParsers.backups(api.call(BACKUP, "list", 1, mapOf("additional" to additional.toString()), session))
    }

    suspend fun backupNow(api: DsmApi, session: DsmSession, taskId: String) {
        api.call(BACKUP, "backup", 1, mapOf("task_id" to taskId), session)
    }

    suspend fun processes(api: DsmApi, session: DsmSession): List<ProcessInfo> =
        SystemParsers.processes(api.call(PROCESS, "list", 1, session = session))

    suspend fun resourceDetail(api: DsmApi, session: DsmSession): ResourceDetail =
        SystemParsers.resourceDetail(api.call(UTILIZATION, "get", 1, session = session))

    suspend fun blockedIps(api: DsmApi, session: DsmSession): List<BlockedIp> =
        SystemParsers.blocked(api.call(AUTO_BLOCK, "list", 1, mapOf("type" to "deny", "offset" to "0", "limit" to "200"), session))

    suspend fun unblock(api: DsmApi, session: DsmSession, ips: List<String>) {
        api.call(AUTO_BLOCK, "delete", 1, mapOf("type" to "deny", "ip" to JsonArray(ips.map(::JsonPrimitive)).toString()), session)
    }

    suspend fun sessions(api: DsmApi, session: DsmSession): List<LoginSession> =
        SystemParsers.sessions(api.call(CONNECTIONS, "list", 1, session = session))

    suspend fun kick(api: DsmApi, session: DsmSession, target: LoginSession) {
        val conn = buildJsonArray {
            add(
                buildJsonObject {
                    put("who", target.user)
                    put("from", target.from)
                    target.pid?.let { put("pid", it) }
                },
            )
        }
        api.call(CONNECTIONS, "kick_connection", 1, mapOf("http_conn" to conn.toString(), "service_conn" to "[]"), session)
    }

    suspend fun certificates(api: DsmApi, session: DsmSession): List<Certificate> =
        SystemParsers.certificates(api.call(CERTIFICATES, "list", 1, session = session))
}

// ---- 解析 ------------------------------------------------------------------

object SystemParsers {

    fun powerSchedule(data: JsonElement): PowerSchedule {
        val o = data.obj()
        return PowerSchedule(tasks(o, "poweron_tasks"), tasks(o, "poweroff_tasks"))
    }

    private fun tasks(o: JsonObject?, key: String): List<PowerTask> = o.array(key).map { t ->
        PowerTask(
            enabled = t.bool("enabled") ?: true,
            hour = t.int("hour") ?: 0,
            minute = t.int("min") ?: t.int("minute") ?: 0,
            weekdays = t.str("weekdays").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..6 }.toSet(),
        )
    }

    fun encodeTasks(tasks: List<PowerTask>): JsonArray = buildJsonArray {
        tasks.forEach { t ->
            add(
                buildJsonObject {
                    put("enabled", t.enabled)
                    put("hour", t.hour)
                    put("min", t.minute)
                    put("weekdays", t.weekdays.sorted().joinToString(","))
                },
            )
        }
    }

    fun interfaces(data: JsonElement): List<NetworkInterface> {
        val items = (data as? JsonArray)?.mapNotNull { it.obj() } ?: data.obj().array("interfaces").ifEmpty { data.obj().array("ethernets") }
        return items.mapNotNull { i ->
            val mac = i.str("mac")?.takeIf { it.count { c -> c == ':' || c == '-' } == 5 } ?: return@mapNotNull null
            NetworkInterface(i.str("ifname") ?: i.str("id").orEmpty(), mac.uppercase().replace('-', ':'), i.str("ip")?.takeIf { it.isNotBlank() })
        }
    }

    fun update(data: JsonElement): DsmUpdate {
        val o = data.obj()
        val u = o.child("update") ?: o
        val available = u.bool("available") == true
        val version = u.str("version")?.takeIf { it.isNotBlank() }
            ?: u.child("version_details").let { d ->
                listOfNotNull(d.str("os_name"), d.str("major")?.let { "$it.${d.str("minor") ?: 0}" }).joinToString(" ").takeIf { it.isNotBlank() }
            }
        return DsmUpdate(available, version, u.str("reboot")?.let { it != "none" } ?: true)
    }

    fun backups(data: JsonElement): List<BackupTask> = data.obj().array("task_list").ifEmpty { data.obj().array("tasks") }.map { t ->
        val result = when (t.str("last_bkp_result")?.lowercase() ?: t.str("status")?.lowercase()) {
            "done", "success" -> BackupResult.Success
            "failed", "error", "fail" -> BackupResult.Failed
            "backingup", "running", "preparing", "waiting" -> BackupResult.Running
            "partial", "partial_success", "warning" -> BackupResult.Partial
            "none", "", null -> BackupResult.Never
            else -> BackupResult.Unknown
        }
        BackupTask(
            id = t.str("task_id").orEmpty(),
            name = t.str("name").orEmpty(),
            target = t.str("target_type") ?: t.str("repo_type").orEmpty(),
            result = if (t.str("state") == "backingup" || t.str("status") == "backup") BackupResult.Running else result,
            lastTime = dsmTime(t.str("last_bkp_time")),
            nextTime = dsmTime(t.str("next_bkp_time")),
            progress = t.child("last_bkp_progress").int("progress") ?: t.int("progress"),
        )
    }

    private val DSM_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT)

    /** 备份时间可能是秒数，也可能是「2026/09/23 01:30」（NAS 本地时间，按手机时区解析；两者通常一致） */
    fun dsmTime(raw: String?, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (raw.isNullOrBlank()) return 0
        raw.toLongOrNull()?.let { return it }
        return runCatching { LocalDateTime.parse(raw.take(16), DSM_TIME).atZone(zone).toEpochSecond() }.getOrDefault(0)
    }

    fun processes(data: JsonElement): List<ProcessInfo> = data.obj().array("process").ifEmpty { data.obj().array("processes") }.map { p ->
        ProcessInfo(
            pid = p.int("pid") ?: 0,
            name = p.str("command") ?: p.str("name").orEmpty(),
            cpuPercent = (p.double("cpu") ?: 0.0).let { if (it > 100 && p.str("cpu")?.contains('.') == false) it / 10 else it },
            memoryBytes = p.long("mem")?.let { it * 1024 } ?: p.long("memory") ?: 0,
        )
    }.sortedByDescending { it.cpuPercent }

    fun resourceDetail(data: JsonElement): ResourceDetail {
        val o = data.obj()
        fun io(list: List<JsonObject>) = list.map { d ->
            DiskIo(
                name = d.str("display_name") ?: d.str("device").orEmpty(),
                readBytesPerSec = d.long("read_byte") ?: 0,
                writeBytesPerSec = d.long("write_byte") ?: 0,
                utilization = d.int("utilization") ?: 0,
            )
        }
        return ResourceDetail(io(o.child("disk").array("disk")), io(o.child("space").array("volume")))
    }

    fun blocked(data: JsonElement): List<BlockedIp> = data.obj().array("ip_info").map {
        BlockedIp(
            ip = it.str("ip").orEmpty(),
            recorded = it.long("recorded_time") ?: 0,
            expires = it.long("expire_time")?.takeIf { e -> e > 0 },
        )
    }.sortedByDescending { it.recorded }

    fun sessions(data: JsonElement): List<LoginSession> = data.obj().array("items").map {
        LoginSession(
            user = it.str("who").orEmpty(),
            from = it.str("from").orEmpty(),
            type = it.str("type").orEmpty(),
            description = it.str("descr").orEmpty(),
            since = it.str("time").orEmpty(),
            canKick = it.bool("can_be_kicked") ?: false,
            pid = it.int("pid"),
        )
    }

    private val CERT_TIME = DateTimeFormatter.ofPattern("MMM d HH:mm:ss yyyy z", Locale.ENGLISH)

    fun certificates(data: JsonElement): List<Certificate> = data.obj().array("certificates").map { c ->
        Certificate(
            id = c.str("id").orEmpty(),
            subject = c.child("subject").str("common_name") ?: c.str("desc").orEmpty(),
            issuer = c.child("issuer").str("common_name") ?: c.child("issuer").str("organization").orEmpty(),
            validTill = c.str("valid_till")?.let { certTime(it) },
            isDefault = c.bool("is_default") == true,
            alternateNames = (c.child("subject")?.get("sub_alt_name") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
        )
    }

    /** "Dec 20 23:59:59 2026 GMT" → 秒 */
    fun certTime(raw: String): Long? = runCatching {
        ZonedDateTime.parse(raw.replace(Regex("\\s+"), " ").trim(), CERT_TIME).toEpochSecond()
    }.getOrNull()
}
