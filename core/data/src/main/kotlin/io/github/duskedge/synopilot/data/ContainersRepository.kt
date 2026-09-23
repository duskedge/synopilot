package io.github.duskedge.synopilot.data

import io.github.duskedge.synopilot.network.ComposeProject
import io.github.duskedge.synopilot.network.Container
import io.github.duskedge.synopilot.network.ContainerDetail
import io.github.duskedge.synopilot.network.ContainerLogLine
import io.github.duskedge.synopilot.network.ContainerState
import io.github.duskedge.synopilot.network.DockerApi
import io.github.duskedge.synopilot.network.DsmApi
import io.github.duskedge.synopilot.network.DsmException
import io.github.duskedge.synopilot.network.DsmSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

data class ContainersData(
    val containers: List<Container> = emptyList(),
    val projects: List<ComposeProject> = emptyList(),
    val loaded: Boolean = false,
    /** 没装 Container Manager 或不是管理员 */
    val unavailable: String? = null,
    val error: String? = null,
    val updatedAt: Long = 0,
)

/** 容器列表每 5 秒刷新一次；操作后立即刷新。 */
class ContainersRepository(
    private val connection: ConnectionManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val refresh = Channel<Unit>(Channel.CONFLATED)

    fun refreshNow() {
        refresh.trySend(Unit)
    }

    fun observe(): Flow<ContainersData> = channelFlow {
        connection.state
            .map { (it as? ConnectionState.Connected)?.device?.id }
            .distinctUntilChanged()
            .collectLatest { deviceId ->
                var data = ContainersData()
                send(data)
                if (deviceId == null) return@collectLatest
                var projectsAt = 0L
                while (true) {
                    try {
                        val containers = connection.request { api, s ->
                            if (!api.supports(DockerApi.CONTAINER)) throw Unsupported()
                            DockerApi.containers(api, s)
                        }
                        val now = clock()
                        val projects = if (now - projectsAt > PROJECTS_INTERVAL_MS) {
                            projectsAt = now
                            runCatching { connection.request { api, s -> if (api.supports(DockerApi.PROJECT)) DockerApi.projects(api, s) else emptyList() } }
                                .getOrElse { data.projects }
                        } else {
                            data.projects
                        }
                        data = ContainersData(containers, projects, loaded = true, updatedAt = now)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Unsupported) {
                        data = ContainersData(loaded = true, unavailable = "这台 NAS 没有安装 Container Manager")
                    } catch (e: DsmException) {
                        data = if (e.code == 105) {
                            ContainersData(loaded = true, unavailable = "当前账号没有权限管理容器（需要管理员）")
                        } else {
                            data.copy(loaded = true, error = e.message)
                        }
                    } catch (e: Exception) {
                        data = data.copy(loaded = true, error = e.message ?: "加载失败")
                    }
                    send(data)
                    // 等到下一个周期，或者被操作触发提前刷新
                    withTimeoutOrNull(POLL_MS) { refresh.receive() }
                }
            }
    }

    private class Unsupported : Exception()

    suspend fun start(name: String) = act { api, s -> DockerApi.start(api, s, name) }
    suspend fun stop(name: String) = act { api, s -> DockerApi.stop(api, s, name) }
    suspend fun restart(name: String) = act { api, s -> DockerApi.restart(api, s, name) }

    /** 重启所有异常退出的容器，返回成功数量 */
    suspend fun restartExited(containers: List<Container>): Int {
        var ok = 0
        for (c in containers.filter { it.state == ContainerState.Exited }) {
            if (runCatching { connection.request { api, s -> DockerApi.start(api, s, c.name) } }.isSuccess) ok++
        }
        refreshNow()
        return ok
    }

    suspend fun projectAction(id: String, action: String) = act { api, s -> DockerApi.projectAction(api, s, id, action) }

    suspend fun logs(name: String, limit: Int = 300): List<ContainerLogLine> =
        connection.request { api, s -> DockerApi.logs(api, s, name, limit) }

    suspend fun detail(name: String): ContainerDetail =
        connection.request { api, s -> DockerApi.detail(api, s, name) }

    private suspend fun act(block: suspend (DsmApi, DsmSession) -> Unit) {
        try {
            connection.request(block)
        } finally {
            refreshNow()
        }
    }

    private companion object {
        const val POLL_MS = 5_000L
        const val PROJECTS_INTERVAL_MS = 30_000L
    }
}
