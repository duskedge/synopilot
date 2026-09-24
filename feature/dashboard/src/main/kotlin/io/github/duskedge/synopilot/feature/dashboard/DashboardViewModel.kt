package io.github.duskedge.synopilot.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.duskedge.synopilot.data.ConnectionManager
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.DashboardData
import io.github.duskedge.synopilot.data.DashboardRepository
import io.github.duskedge.synopilot.data.DeviceRepository
import io.github.duskedge.synopilot.data.FailureReason
import io.github.duskedge.synopilot.data.SystemRepository
import io.github.duskedge.synopilot.designsystem.component.SpMessage
import io.github.duskedge.synopilot.designsystem.component.SpMessageBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUi(
    val connection: ConnectionState = ConnectionState.NoDevice,
    val data: DashboardData? = null,
)

class DashboardViewModel(
    private val connection: ConnectionManager,
    dashboard: DashboardRepository,
    private val devices: DeviceRepository,
    private val system: SystemRepository,
) : ViewModel() {

    val ui: StateFlow<DashboardUi> = combine(connection.state, dashboard.observe()) { c, d -> DashboardUi(c, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi(connection.state.value))

    fun retry() = connection.reconnect()

    /** 网络唤醒，20 秒后重试连接 */
    fun wake() {
        val device = connection.state.value.deviceOrNull ?: return
        viewModelScope.launch {
            val sent = runCatching { system.wake(device) }.getOrDefault(false)
            SpMessageBus.post(SpMessage(if (sent) "已发送唤醒信号，NAS 一般 1–2 分钟后开机" else "唤醒信号发送失败"))
            if (sent) {
                delay(20_000)
                connection.reconnect()
            }
        }
    }

    /** 证书变化时，用户确认后把新证书加入信任列表（连接层会自动重连） */
    fun trustCertificate(reason: FailureReason.UntrustedCertificate) {
        val device = connection.state.value.deviceOrNull ?: return
        viewModelScope.launch {
            devices.update(device.id) { d -> d.copy(pinnedCerts = (d.pinnedCerts + reason.certificate.sha256).distinct()) }
        }
    }
}
