package io.github.duskedge.synopilot.data

import io.github.duskedge.synopilot.network.BackupTask
import io.github.duskedge.synopilot.network.BlockedIp
import io.github.duskedge.synopilot.network.Certificate
import io.github.duskedge.synopilot.network.DsmUpdate
import io.github.duskedge.synopilot.network.LoginSession
import io.github.duskedge.synopilot.network.PowerSchedule
import io.github.duskedge.synopilot.network.ProcessInfo
import io.github.duskedge.synopilot.network.ResourceDetail
import io.github.duskedge.synopilot.network.SystemApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 电源、系统、安全相关的操作。 */
class SystemRepository(
    private val connection: ConnectionManager,
    private val repository: DeviceRepository,
) {
    /** 连上一台 NAS 后记下它的 MAC，断线时也能网络唤醒 */
    fun start(scope: CoroutineScope) {
        scope.launch {
            connection.state.filterIsInstance<ConnectionState.Connected>()
                .map { it.device.id }
                .distinctUntilChanged()
                .collect { id ->
                    val macs = runCatching { connection.request { api, s -> SystemApi.interfaces(api, s) } }.getOrNull()
                        ?.map { it.mac }?.distinct()?.takeIf { it.isNotEmpty() } ?: return@collect
                    repository.update(id) { d -> if (d.macAddresses == macs) d else d.copy(macAddresses = macs) }
                }
        }
    }

    suspend fun reboot() = connection.request { api, s -> SystemApi.reboot(api, s) }
    suspend fun shutdown() = connection.request { api, s -> SystemApi.shutdown(api, s) }

    /** 网络唤醒。返回 false 表示还不知道这台 NAS 的 MAC。 */
    suspend fun wake(device: Device): Boolean {
        if (device.macAddresses.isEmpty()) return false
        WakeOnLan.send(device.macAddresses, device.lanHost)
        return true
    }

    suspend fun powerSchedule(): PowerSchedule = connection.request { api, s -> SystemApi.powerSchedule(api, s) }
    suspend fun savePowerSchedule(schedule: PowerSchedule) = connection.request { api, s -> SystemApi.savePowerSchedule(api, s, schedule) }

    suspend fun checkUpdate(): DsmUpdate = connection.request { api, s -> SystemApi.checkUpdate(api, s) }

    suspend fun backups(): List<BackupTask> = connection.request { api, s -> SystemApi.backups(api, s) }
    suspend fun backupNow(taskId: String) = connection.request { api, s -> SystemApi.backupNow(api, s, taskId) }

    suspend fun processes(): List<ProcessInfo> = connection.request { api, s -> SystemApi.processes(api, s) }
    suspend fun resourceDetail(): ResourceDetail = connection.request { api, s -> SystemApi.resourceDetail(api, s) }

    suspend fun blockedIps(): List<BlockedIp> = connection.request { api, s -> SystemApi.blockedIps(api, s) }
    suspend fun unblock(ip: String) = connection.request { api, s -> SystemApi.unblock(api, s, listOf(ip)) }

    suspend fun sessions(): List<LoginSession> = connection.request { api, s -> SystemApi.sessions(api, s) }
    suspend fun kick(session: LoginSession) = connection.request { api, s -> SystemApi.kick(api, s, session) }

    suspend fun certificates(): List<Certificate> = connection.request { api, s -> SystemApi.certificates(api, s) }
}
