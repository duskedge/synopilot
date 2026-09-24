package io.github.duskedge.synopilot.feature.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.duskedge.synopilot.data.Alert
import io.github.duskedge.synopilot.data.AlertChecker
import io.github.duskedge.synopilot.data.AlertRule
import io.github.duskedge.synopilot.data.AlertStore
import io.github.duskedge.synopilot.data.AppSettings
import io.github.duskedge.synopilot.data.ConnectionManager
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.DeviceRepository
import io.github.duskedge.synopilot.data.SystemRepository
import io.github.duskedge.synopilot.designsystem.component.SpMessage
import io.github.duskedge.synopilot.network.BackupResult
import io.github.duskedge.synopilot.network.BackupTask
import io.github.duskedge.synopilot.network.BlockedIp
import io.github.duskedge.synopilot.network.Certificate
import io.github.duskedge.synopilot.network.ContainerState
import io.github.duskedge.synopilot.network.DockerApi
import io.github.duskedge.synopilot.network.DsmParsers
import io.github.duskedge.synopilot.network.DsmUpdate
import io.github.duskedge.synopilot.network.LoginSession
import io.github.duskedge.synopilot.network.PowerSchedule
import io.github.duskedge.synopilot.network.PowerTask
import io.github.duskedge.synopilot.network.ProcessInfo
import io.github.duskedge.synopilot.network.ResourceDetail
import io.github.duskedge.synopilot.network.UpsInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 加载中 / 成功 / 失败 */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

val <T> Load<T>.valueOrNull: T? get() = (this as? Load.Ok)?.value

private suspend fun <T> load(block: suspend () -> T): Load<T> = try {
    Load.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Load.Failed(e.message ?: "加载失败")
}

/** 设置页里「电源 / 系统 / 安全 / 通知」几组入口的摘要，以及重启关机、唤醒、DSM 更新、UPS。 */
data class SystemSummary(
    val schedule: Load<PowerSchedule> = Load.Loading,
    val update: Load<DsmUpdate>? = null,
    val backups: Load<List<BackupTask>> = Load.Loading,
    val blocked: Load<List<BlockedIp>> = Load.Loading,
    val sessions: Load<List<LoginSession>> = Load.Loading,
    val certificates: Load<List<Certificate>> = Load.Loading,
    val ups: Load<UpsInfo> = Load.Loading,
)

enum class PowerAction { Reboot, Shutdown }

data class PowerImpact(val runningContainers: Int?, val sessions: Int?)

class SystemViewModel(
    private val connection: ConnectionManager,
    private val devices: DeviceRepository,
    private val system: SystemRepository,
    alerts: AlertStore,
) : ViewModel() {
    val connectionState = connection.state
    val unreadAlerts: StateFlow<Int> = alerts.unread.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    private val _summary = MutableStateFlow(SystemSummary())
    val summary: StateFlow<SystemSummary> = _summary.asStateFlow()
    private val _impact = MutableStateFlow<PowerImpact?>(null)
    val impact: StateFlow<PowerImpact?> = _impact.asStateFlow()
    private val _messages = Channel<SpMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            connection.state.map { (it as? ConnectionState.Connected)?.device?.id }.distinctUntilChanged().collect { id ->
                if (id != null) refresh() else _summary.value = SystemSummary()
            }
        }
    }

    private var refreshedAt = 0L

    /** 从子页面返回设置页时刷新摘要（刚刷新过就跳过） */
    fun refreshIfStale() {
        if (connection.state.value is ConnectionState.Connected && System.currentTimeMillis() - refreshedAt > 2_000) refresh()
    }

    fun refresh() {
        refreshedAt = System.currentTimeMillis()
        viewModelScope.launch { _summary.update { it.copy(schedule = load { system.powerSchedule() }) } }
        viewModelScope.launch { _summary.update { it.copy(backups = load { system.backups() }) } }
        viewModelScope.launch { _summary.update { it.copy(blocked = load { system.blockedIps() }) } }
        viewModelScope.launch { _summary.update { it.copy(sessions = load { system.sessions() }) } }
        viewModelScope.launch { _summary.update { it.copy(certificates = load { system.certificates() }) } }
        viewModelScope.launch {
            _summary.update { it.copy(ups = load { connection.request { api, s -> DsmParsers.ups(api.call("SYNO.Core.ExternalDevice.UPS", "get", 1, session = s)) } }) }
        }
    }

    fun checkUpdate() {
        _summary.update { it.copy(update = Load.Loading) }
        viewModelScope.launch { _summary.update { it.copy(update = load { system.checkUpdate() }) } }
    }

    /** 打开重启 / 关机确认时，算一下会影响什么 */
    fun loadImpact() {
        _impact.value = PowerImpact(null, null)
        viewModelScope.launch {
            val containers = runCatching {
                connection.request { api, s -> if (api.supports(DockerApi.CONTAINER)) DockerApi.containers(api, s) else emptyList() }
            }.getOrNull()?.count { it.state == ContainerState.Running }
            val sessions = runCatching { system.sessions() }.getOrNull()?.size
            _impact.value = PowerImpact(containers, sessions)
        }
    }

    fun power(action: PowerAction) {
        viewModelScope.launch {
            try {
                if (action == PowerAction.Reboot) system.reboot() else system.shutdown()
                _messages.send(SpMessage(if (action == PowerAction.Reboot) "NAS 正在重启，几分钟后会自动重新连接" else "NAS 正在关机"))
                delay(3_000)
                connection.reconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(SpMessage(e.message ?: "操作失败"))
            }
        }
    }

    fun wake() {
        viewModelScope.launch {
            val device = devices.currentDevice.first() ?: return@launch
            val ok = runCatching { system.wake(device) }
            _messages.send(
                SpMessage(
                    when {
                        ok.isFailure -> "发送失败：${ok.exceptionOrNull()?.message}"
                        ok.getOrNull() == false -> "还不知道这台 NAS 的 MAC 地址，连接成功一次后才能使用"
                        else -> "已发送唤醒信号，NAS 一般 1–2 分钟后开机"
                    },
                ),
            )
            delay(20_000)
            connection.reconnect()
        }
    }
}

// ---- 定时开关机 -------------------------------------------------------------

class ScheduleViewModel(private val system: SystemRepository) : ViewModel() {
    private val _state = MutableStateFlow<Load<PowerSchedule>>(Load.Loading)
    val state: StateFlow<Load<PowerSchedule>> = _state.asStateFlow()
    private val _messages = Channel<SpMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    init {
        viewModelScope.launch { _state.value = load { system.powerSchedule() } }
    }

    fun save(schedule: PowerSchedule, onDone: () -> Unit = {}) {
        _saving.value = true
        viewModelScope.launch {
            try {
                system.savePowerSchedule(schedule)
                _state.value = Load.Ok(schedule)
                _messages.send(SpMessage("已保存"))
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(SpMessage(e.message ?: "保存失败"))
            } finally {
                _saving.value = false
            }
        }
    }

    fun toggle(on: Boolean, index: Int, enabled: Boolean) {
        val s = _state.value.valueOrNull ?: return
        save(edit(s, on) { list -> list.mapIndexed { i, t -> if (i == index) t.copy(enabled = enabled) else t } })
    }

    fun put(on: Boolean, index: Int?, task: PowerTask, onDone: () -> Unit) {
        val s = _state.value.valueOrNull ?: return
        save(edit(s, on) { list -> if (index == null) list + task else list.mapIndexed { i, t -> if (i == index) task else t } }, onDone)
    }

    fun remove(on: Boolean, index: Int, onDone: () -> Unit) {
        val s = _state.value.valueOrNull ?: return
        save(edit(s, on) { list -> list.filterIndexed { i, _ -> i != index } }, onDone)
    }

    private fun edit(s: PowerSchedule, on: Boolean, f: (List<PowerTask>) -> List<PowerTask>) =
        if (on) s.copy(powerOn = f(s.powerOn)) else s.copy(powerOff = f(s.powerOff))
}

// ---- 备份 ------------------------------------------------------------------

class BackupViewModel(private val system: SystemRepository) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<BackupTask>>>(Load.Loading)
    val state: StateFlow<Load<List<BackupTask>>> = _state.asStateFlow()
    private val _messages = Channel<SpMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _state.value = load { system.backups() } }
    }

    fun backupNow(task: BackupTask) {
        viewModelScope.launch {
            try {
                system.backupNow(task.id)
                _messages.send(SpMessage("已开始备份「${task.name}」"))
                _state.update { s -> (s as? Load.Ok)?.let { Load.Ok(it.value.map { t -> if (t.id == task.id) t.copy(result = BackupResult.Running) else t }) } ?: s }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(SpMessage(e.message ?: "操作失败"))
            }
        }
    }
}

// ---- 资源监控 ----------------------------------------------------------------

data class MonitorUi(val detail: Load<ResourceDetail> = Load.Loading, val processes: Load<List<ProcessInfo>> = Load.Loading)

class MonitorViewModel(private val system: SystemRepository) : ViewModel() {
    private val _state = MutableStateFlow(MonitorUi())
    val state: StateFlow<MonitorUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                val detail = load { system.resourceDetail() }
                val processes = load { system.processes().take(20) }
                _state.update { old ->
                    MonitorUi(
                        detail = if (detail is Load.Failed && old.detail is Load.Ok) old.detail else detail,
                        processes = if (processes is Load.Failed && old.processes is Load.Ok) old.processes else processes,
                    )
                }
                delay(3_000)
            }
        }
    }
}

// ---- 安全 ------------------------------------------------------------------

data class SecurityUi(
    val blocked: Load<List<BlockedIp>> = Load.Loading,
    val sessions: Load<List<LoginSession>> = Load.Loading,
    val certificates: Load<List<Certificate>> = Load.Loading,
)

class SecurityViewModel(private val system: SystemRepository) : ViewModel() {
    private val _state = MutableStateFlow(SecurityUi())
    val state: StateFlow<SecurityUi> = _state.asStateFlow()
    private val _messages = Channel<SpMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _state.update { it.copy(blocked = load { system.blockedIps() }) } }
        viewModelScope.launch { _state.update { it.copy(sessions = load { system.sessions() }) } }
        viewModelScope.launch { _state.update { it.copy(certificates = load { system.certificates() }) } }
    }

    fun unblock(ip: BlockedIp) {
        viewModelScope.launch {
            try {
                system.unblock(ip.ip)
                _messages.send(SpMessage("已解除封锁 ${ip.ip}"))
                _state.update { s -> s.copy(blocked = (s.blocked as? Load.Ok)?.let { Load.Ok(it.value.filterNot { b -> b.ip == ip.ip }) } ?: s.blocked) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(SpMessage(e.message ?: "操作失败"))
            }
        }
    }

    fun kick(session: LoginSession) {
        viewModelScope.launch {
            try {
                system.kick(session)
                _messages.send(SpMessage("已断开 ${session.user}（${session.from}）"))
                _state.update { it.copy(sessions = load { system.sessions() }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(SpMessage(e.message ?: "操作失败"))
            }
        }
    }
}

// ---- 通知中心 ----------------------------------------------------------------

class NotificationsViewModel(
    private val store: AlertStore,
    private val checker: AlertChecker,
    private val devices: DeviceRepository,
) : ViewModel() {
    val alerts: StateFlow<List<Alert>> = store.alerts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<AppSettings> = devices.appSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    private val _messages = Channel<SpMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun checkNow() {
        _checking.value = true
        viewModelScope.launch {
            val n = runCatching { checker.check(notify = false) }.getOrDefault(0)
            _checking.value = false
            _messages.send(SpMessage(if (n > 0) "发现 $n 条新告警" else "没有发现新问题"))
        }
    }

    fun markAllRead() {
        viewModelScope.launch { store.markAllRead() }
    }

    fun clear() {
        viewModelScope.launch { store.clear() }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { devices.updateSettings { it.copy(alertsEnabled = enabled) } }
    }

    fun setRule(rule: AlertRule, enabled: Boolean) {
        viewModelScope.launch {
            devices.updateSettings { s -> s.copy(disabledAlertRules = if (enabled) s.disabledAlertRules - rule.name else s.disabledAlertRules + rule.name) }
        }
    }
}
