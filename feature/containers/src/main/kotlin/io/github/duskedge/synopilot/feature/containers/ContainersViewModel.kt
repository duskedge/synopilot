package io.github.duskedge.synopilot.feature.containers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.duskedge.synopilot.data.ConnectionManager
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.ContainersData
import io.github.duskedge.synopilot.data.ContainersRepository
import io.github.duskedge.synopilot.designsystem.component.SpMessage
import io.github.duskedge.synopilot.network.ComposeProject
import io.github.duskedge.synopilot.network.Container
import io.github.duskedge.synopilot.network.ContainerDetail
import io.github.duskedge.synopilot.network.ContainerLogLine
import io.github.duskedge.synopilot.network.ContainerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ContainerFilter { All, Running, Exited, Stopped }
enum class ContainersView { Containers, Projects }

data class ContainersUi(
    val connection: ConnectionState = ConnectionState.NoDevice,
    val data: ContainersData = ContainersData(),
    val filter: ContainerFilter = ContainerFilter.All,
    val view: ContainersView = ContainersView.Containers,
    /** 正在操作的容器 / 项目，按钮显示忙碌 */
    val busy: Set<String> = emptySet(),
) {
    fun count(f: ContainerFilter) = data.containers.count { it.matches(f) }
    val visible: List<Container> get() = data.containers.filter { it.matches(filter) }
}

fun Container.matches(f: ContainerFilter) = when (f) {
    ContainerFilter.All -> true
    ContainerFilter.Running -> state == ContainerState.Running || state == ContainerState.Restarting || state == ContainerState.Paused
    ContainerFilter.Exited -> state == ContainerState.Exited
    ContainerFilter.Stopped -> state == ContainerState.Stopped || state == ContainerState.Unknown
}

data class DetailUi(
    val name: String,
    val logs: List<ContainerLogLine> = emptyList(),
    val detail: ContainerDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val following: Boolean = false,
)

class ContainersViewModel(
    connection: ConnectionManager,
    private val repository: ContainersRepository,
) : ViewModel() {
    private val local = MutableStateFlow(ContainersUi())
    private val _messages = Channel<SpMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val ui: StateFlow<ContainersUi> = combine(connection.state, repository.observe(), local) { c, d, l -> l.copy(connection = c, data = d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContainersUi(connection.state.value))

    private val _detail = MutableStateFlow<DetailUi?>(null)
    val detail: StateFlow<DetailUi?> = _detail.asStateFlow()
    private var followJob: Job? = null

    fun setFilter(f: ContainerFilter) = local.update { it.copy(filter = f) }
    fun setView(v: ContainersView) = local.update { it.copy(view = v) }
    fun refresh() = repository.refreshNow()

    private fun run(key: String, success: String?, block: suspend () -> Unit, undo: SpMessage? = null) {
        if (key in local.value.busy) return
        local.update { it.copy(busy = it.busy + key) }
        viewModelScope.launch {
            try {
                block()
                if (undo != null) _messages.send(undo) else if (success != null) _messages.send(SpMessage(success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(SpMessage(e.message ?: "操作失败"))
            } finally {
                local.update { it.copy(busy = it.busy - key) }
            }
        }
    }

    fun toggle(c: Container) {
        if (c.state == ContainerState.Running) stop(c.name) else start(c.name)
    }

    fun start(name: String) = run(name, "$name 已启动", { repository.start(name) })

    fun stop(name: String) = run(
        name, null, { repository.stop(name) },
        undo = SpMessage("$name 已停止", "撤销", action = { start(name) }),
    )

    fun restart(name: String) = run(name, "$name 已重启", { repository.restart(name) })

    fun restartExited() {
        val exited = ui.value.data.containers.filter { it.state == ContainerState.Exited }
        run("__exited", null, {
            val ok = repository.restartExited(exited)
            _messages.send(SpMessage(if (ok == exited.size) "已重新启动 $ok 个容器" else "重新启动了 $ok 个，${exited.size - ok} 个失败"))
        })
    }

    fun projectAction(p: ComposeProject, action: String) {
        val label = when (action) {
            "start" -> "已启动"
            "stop" -> "已停止"
            else -> "已拉取镜像并重建"
        }
        run("project:${p.id}", "${p.name} $label", { repository.projectAction(p.id, action) })
    }

    fun openDetail(name: String) {
        _detail.value = DetailUi(name)
        viewModelScope.launch {
            val logs = runCatching { repository.logs(name) }
            val detail = runCatching { repository.detail(name) }
            _detail.update { d ->
                if (d?.name != name) return@update d
                d.copy(
                    logs = logs.getOrDefault(emptyList()),
                    detail = detail.getOrNull(),
                    loading = false,
                    error = (logs.exceptionOrNull() ?: detail.exceptionOrNull())?.message,
                )
            }
        }
    }

    /** 跟随日志：每 2 秒拉取一次最新日志 */
    fun setFollowing(on: Boolean) {
        val name = _detail.value?.name ?: return
        _detail.update { it?.copy(following = on) }
        followJob?.cancel()
        if (!on) return
        followJob = viewModelScope.launch {
            while (isActive) {
                delay(2_000)
                val logs = runCatching { repository.logs(name) }.getOrNull() ?: continue
                _detail.update { d -> if (d?.name == name) d.copy(logs = logs) else d }
            }
        }
    }

    fun closeDetail() {
        followJob?.cancel()
        _detail.value = null
    }
}
