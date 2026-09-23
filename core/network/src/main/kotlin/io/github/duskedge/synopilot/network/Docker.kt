package io.github.duskedge.synopilot.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ContainerState {
    Running,
    Restarting,
    Paused,

    /** 非正常退出（退出码不为 0） */
    Exited,

    /** 正常停止 */
    Stopped,
    Unknown,
}

@Serializable
data class PortMapping(val hostPort: Int?, val containerPort: Int?, val protocol: String)

@Serializable
data class Container(
    val name: String,
    val image: String,
    val state: ContainerState,
    val exitCode: Int?,
    /** DSM 给出的状态描述，如 Up 14 days */
    val statusText: String,
    val ports: List<PortMapping>,
    val memoryLimitBytes: Long?,
    val cpuPercent: Double? = null,
    val memoryBytes: Long? = null,
    val memoryPercent: Double? = null,
)

@Serializable
data class ComposeProject(
    val id: String,
    val name: String,
    val path: String,
    val status: String,
    val containerNames: List<String>,
)

data class ContainerLogLine(val time: String, val stream: String, val text: String)

data class ContainerDetail(
    val env: List<Pair<String, String>>,
    val volumes: List<VolumeBinding>,
    val ports: List<PortMapping>,
    val networks: List<Pair<String, String>>,
    val restartPolicy: String,
)

data class VolumeBinding(val hostPath: String, val mountPoint: String, val readOnly: Boolean)

/** Container Manager（DSM 7.2 起的 Docker 套件）接口。 */
object DockerApi {
    const val CONTAINER = "SYNO.Docker.Container"
    const val RESOURCE = "SYNO.Docker.Container.Resource"
    const val LOG = "SYNO.Docker.Container.Log"
    const val PROJECT = "SYNO.Docker.Project"

    suspend fun containers(api: DsmApi, session: DsmSession): List<Container> {
        val list = api.call(CONTAINER, "list", 1, mapOf("limit" to "-1", "offset" to "0", "type" to "all"), session)
        val resources = runCatching { api.call(RESOURCE, "get", 1, session = session) }.getOrNull()
        return DockerParsers.containers(list, resources)
    }

    suspend fun projects(api: DsmApi, session: DsmSession): List<ComposeProject> =
        DockerParsers.projects(api.call(PROJECT, "list", 1, session = session))

    suspend fun start(api: DsmApi, session: DsmSession, name: String) = action(api, session, "start", name)
    suspend fun stop(api: DsmApi, session: DsmSession, name: String) = action(api, session, "stop", name)
    suspend fun restart(api: DsmApi, session: DsmSession, name: String) = action(api, session, "restart", name)

    suspend fun logs(api: DsmApi, session: DsmSession, name: String, limit: Int = 300): List<ContainerLogLine> {
        val data = withNameFallback(name) { n ->
            api.call(LOG, "get", 1, mapOf("name" to n, "offset" to "0", "limit" to limit.toString(), "sort_dir" to "DESC"), session)
        }
        return DockerParsers.logs(data).reversed()
    }

    suspend fun detail(api: DsmApi, session: DsmSession, name: String): ContainerDetail {
        val data = withNameFallback(name) { n -> api.call(CONTAINER, "get", 1, mapOf("name" to n), session) }
        return DockerParsers.detail(data)
    }

    /** Compose 项目操作：start / stop / build（拉取镜像并重新构建） */
    suspend fun projectAction(api: DsmApi, session: DsmSession, id: String, action: String) {
        withNameFallback(id) { v -> api.callStream(PROJECT, "${action}_stream", 1, mapOf("id" to v), session) }
    }

    private suspend fun action(api: DsmApi, session: DsmSession, method: String, name: String) {
        withNameFallback(name) { n -> api.call(CONTAINER, method, 1, mapOf("name" to n), session) }
    }

    /** 不同 DSM 版本对字符串参数的格式要求不同：参数错误时改用 JSON 字符串（带引号）重试一次。 */
    private suspend fun <T> withNameFallback(value: String, block: suspend (String) -> T): T = try {
        block(value)
    } catch (e: DsmException) {
        if (e.code == 114 || e.code == 120 || e.code == 101) block(JsonPrimitive(value).toString()) else throw e
    }
}

object DockerParsers {

    fun containers(list: JsonElement, resources: JsonElement?): List<Container> {
        val usage = resources.obj().array("resources").associateBy { it.str("name").orEmpty() }
        return list.obj().array("containers").map { c ->
            val name = c.str("name").orEmpty()
            val stateObj = c.child("State")
            val exitCode = stateObj.int("ExitCode") ?: c.int("exit_code")
            val r = usage[name]
            Container(
                name = name,
                image = c.str("image").orEmpty(),
                state = state(c.str("status") ?: stateObj.str("Status"), exitCode),
                exitCode = exitCode,
                statusText = c.str("up_status") ?: stateObj.str("Status").orEmpty(),
                ports = c.array("ports").map(::port),
                memoryLimitBytes = c.long("memory_limit")?.takeIf { it > 0 },
                cpuPercent = r?.double("cpu"),
                memoryBytes = r?.long("memory"),
                memoryPercent = r?.double("memoryPercent"),
            )
        }.sortedWith(compareBy({ order(it.state) }, { it.name }))
    }

    fun state(status: String?, exitCode: Int?): ContainerState = when (status?.lowercase()) {
        "running" -> ContainerState.Running
        "restarting" -> ContainerState.Restarting
        "paused" -> ContainerState.Paused
        "exited", "stopped", "dead", "created" -> if (exitCode != null && exitCode != 0) ContainerState.Exited else ContainerState.Stopped
        else -> ContainerState.Unknown
    }

    private fun order(s: ContainerState) = when (s) {
        ContainerState.Exited -> 0
        ContainerState.Restarting -> 1
        ContainerState.Running -> 2
        ContainerState.Paused -> 3
        ContainerState.Stopped -> 4
        ContainerState.Unknown -> 5
    }

    private fun port(p: JsonObject) = PortMapping(
        hostPort = p.int("host_port")?.takeIf { it > 0 },
        containerPort = p.int("container_port"),
        protocol = p.str("type") ?: p.str("protocol") ?: "tcp",
    )

    fun projects(data: JsonElement): List<ComposeProject> {
        val root = data.obj() ?: return emptyList()
        // 可能是 {"<id>": {...}} 也可能是 {"projects": [...]}
        val items = root.array("projects").ifEmpty { root.values.mapNotNull { it.obj() }.filter { it.str("name") != null } }
        return items.map { p ->
            ComposeProject(
                id = p.str("id").orEmpty(),
                name = p.str("name").orEmpty(),
                path = p.str("path") ?: p.str("share_path").orEmpty(),
                status = p.str("status").orEmpty(),
                containerNames = p.array("containers").mapNotNull { it.str("Name") ?: it.str("name") }.map { it.removePrefix("/") },
            )
        }.sortedBy { it.name }
    }

    fun logs(data: JsonElement): List<ContainerLogLine> =
        data.obj().array("logs").map { ContainerLogLine(it.str("created").orEmpty(), it.str("stream").orEmpty(), it.str("text").orEmpty().trimEnd()) }

    fun detail(data: JsonElement): ContainerDetail {
        val o = data.obj()
        val profile = o.child("profile")
        val details = o.child("details")
        val networks = details.child("NetworkSettings").child("Networks")?.mapNotNull { (name, v) ->
            name to (v.obj().str("IPAddress").orEmpty())
        }.orEmpty()
        return ContainerDetail(
            env = profile.array("env_variables").map { (it.str("key").orEmpty()) to (it.str("value").orEmpty()) },
            volumes = profile.array("volume_bindings").map {
                VolumeBinding(
                    hostPath = it.str("host_volume_file") ?: it.str("host_path").orEmpty(),
                    mountPoint = it.str("mount_point").orEmpty(),
                    readOnly = it.str("type") == "ro",
                )
            },
            ports = profile.array("port_bindings").map(::port),
            networks = networks,
            restartPolicy = details.child("HostConfig").child("RestartPolicy").str("Name")
                ?: (if (profile.bool("enable_restart_policy") == true) "always" else "no"),
        )
    }
}
