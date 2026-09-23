package io.github.duskedge.synopilot.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.duskedge.synopilot.data.ConnectionManager
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.DashboardData
import io.github.duskedge.synopilot.data.DashboardRepository
import io.github.duskedge.synopilot.data.DeviceRepository
import io.github.duskedge.synopilot.data.FailureReason
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
) : ViewModel() {

    val ui: StateFlow<DashboardUi> = combine(connection.state, dashboard.observe()) { c, d -> DashboardUi(c, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi(connection.state.value))

    fun retry() = connection.reconnect()

    /** 证书变化时，用户确认后把新证书加入信任列表（连接层会自动重连） */
    fun trustCertificate(reason: FailureReason.UntrustedCertificate) {
        val device = connection.state.value.deviceOrNull ?: return
        viewModelScope.launch {
            devices.update(device.id) { d -> d.copy(pinnedCerts = (d.pinnedCerts + reason.certificate.sha256).distinct()) }
        }
    }
}
