package io.github.duskedge.synopilot.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.duskedge.synopilot.data.AddressRecord
import io.github.duskedge.synopilot.data.AddressTest
import io.github.duskedge.synopilot.data.AppSettings
import io.github.duskedge.synopilot.data.ConnectionManager
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.Device
import io.github.duskedge.synopilot.data.DeviceRepository
import io.github.duskedge.synopilot.data.PreferRoute
import io.github.duskedge.synopilot.network.CertificateInfo
import io.github.duskedge.synopilot.network.DsmAuth
import io.github.duskedge.synopilot.network.DsmApi
import io.github.duskedge.synopilot.network.DsmSession
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: DeviceRepository,
    private val connection: ConnectionManager,
    private val http: HttpClient,
) : ViewModel() {
    val devices: StateFlow<List<Device>> = repository.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val connectionState: StateFlow<ConnectionState> = connection.state
    val appSettings: StateFlow<AppSettings> = repository.appSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun switchTo(id: String) {
        viewModelScope.launch { repository.setCurrent(id) }
    }

    fun setBiometricLock(on: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(biometricLock = on) } }
    }

    fun setDownloadNotification(on: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(downloadNotification = on) } }
    }

    fun setPollSeconds(seconds: Int) {
        viewModelScope.launch { repository.updateSettings { it.copy(pollSeconds = seconds) } }
    }

    /** 退出并移除设备：先在 DSM 上注销会话，再删除本地配置和加密凭证。 */
    fun remove(device: Device) {
        viewModelScope.launch {
            val secrets = repository.secrets(device.id)
            val url = (connection.state.value as? ConnectionState.Connected)?.takeIf { it.device.id == device.id }?.route?.url
            if (secrets?.sid != null && url != null) {
                DsmAuth.logout(DsmApi(http, url), DsmSession(secrets.sid!!, secrets.synoToken))
            }
            repository.remove(device.id)
            repository.devices.first().firstOrNull()?.let { repository.setCurrent(it.id) }
        }
    }
}

data class AddressSlotUi(
    val draft: String = "",
    val testing: Boolean = false,
    val result: AddressTest? = null,
)

data class ServerAddressUi(
    val device: Device? = null,
    val primary: AddressSlotUi = AddressSlotUi(),
    val backup: AddressSlotUi = AddressSlotUi(),
    val quickConnectDraft: String = "",
    val pendingCertificate: Pair<String, CertificateInfo>? = null,
    val message: String? = null,
)

enum class AddressSlot { Primary, Backup }

class ServerAddressViewModel(
    private val repository: DeviceRepository,
    private val connection: ConnectionManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(ServerAddressUi())
    val ui: StateFlow<ServerAddressUi> = _ui.asStateFlow()
    val connectionState: StateFlow<ConnectionState> = connection.state

    init {
        viewModelScope.launch {
            repository.currentDevice.collect { d ->
                _ui.update { u ->
                    val first = u.device == null
                    u.copy(
                        device = d,
                        primary = if (first) AddressSlotUi(draft = d?.primaryUrl.orEmpty()) else u.primary,
                        backup = if (first) AddressSlotUi(draft = d?.backupUrl.orEmpty()) else u.backup,
                        quickConnectDraft = if (first) d?.quickConnectId.orEmpty() else u.quickConnectDraft,
                    )
                }
            }
        }
    }

    fun setDraft(slot: AddressSlot, value: String) = _ui.update {
        when (slot) {
            AddressSlot.Primary -> it.copy(primary = AddressSlotUi(draft = value))
            AddressSlot.Backup -> it.copy(backup = AddressSlotUi(draft = value))
        }
    }

    fun test(slot: AddressSlot, thenSave: Boolean = false) {
        val device = _ui.value.device ?: return
        val url = DsmApi.normalizeBaseUrl(slotUi(slot).draft)
        val other = if (slot == AddressSlot.Primary) device.backupUrl else device.primaryUrl
        if (url == other) {
            setSlot(slot) { it.copy(result = AddressTest(false, null, "和另一个地址重复了")) }
            return
        }
        setSlot(slot) { it.copy(testing = true, result = null) }
        viewModelScope.launch {
            val r = connection.testAddress(device, url)
            setSlot(slot) { it.copy(testing = false, result = r, draft = url) }
            r.certificate?.let { cert -> _ui.update { it.copy(pendingCertificate = url to cert) } }
            if (thenSave && r.ok) save(slot, url)
        }
    }

    private suspend fun save(slot: AddressSlot, url: String) {
        val device = _ui.value.device ?: return
        repository.update(device.id) { d ->
            when (slot) {
                AddressSlot.Primary -> d.copy(primaryUrl = url)
                AddressSlot.Backup -> d.copy(backupUrl = url)
            }
        }
        _ui.update { it.copy(message = "${if (slot == AddressSlot.Primary) "主地址" else "备用地址"}已保存") }
    }

    fun trustPendingCertificate() {
        val (url, cert) = _ui.value.pendingCertificate ?: return
        val device = _ui.value.device ?: return
        _ui.update { it.copy(pendingCertificate = null) }
        viewModelScope.launch {
            repository.update(device.id) { d -> d.copy(pinnedCerts = (d.pinnedCerts + cert.sha256).distinct()) }
            val slot = if (DsmApi.normalizeBaseUrl(_ui.value.primary.draft) == url) AddressSlot.Primary else AddressSlot.Backup
            test(slot)
        }
    }

    fun dismissCertificate() = _ui.update { it.copy(pendingCertificate = null) }

    fun setPrefer(prefer: PreferRoute) = updateDevice { it.copy(prefer = prefer) }

    fun setQuickConnectFallback(on: Boolean) = updateDevice { it.copy(quickConnectFallback = on) }

    fun setQuickConnectDraft(v: String) = _ui.update { it.copy(quickConnectDraft = v.trim()) }

    fun saveQuickConnect() {
        val id = _ui.value.quickConnectDraft.trim().lowercase()
        updateDevice { it.copy(quickConnectId = id) }
        _ui.update { it.copy(message = if (id.isBlank()) "已清除 QuickConnect ID" else "QuickConnect ID 已保存") }
    }

    fun useHistory(record: AddressRecord, slot: AddressSlot) {
        val device = _ui.value.device ?: return
        if (record.serial.isNotBlank() && device.serial.isNotBlank() && record.serial != device.serial) {
            _ui.update { it.copy(message = "这是另一台 NAS（${record.model.ifBlank { record.serial }}）的地址，不能用于这台设备") }
            return
        }
        updateDevice { d ->
            val other = if (slot == AddressSlot.Primary) d.backupUrl else d.primaryUrl
            val current = if (slot == AddressSlot.Primary) d.primaryUrl else d.backupUrl
            // 已经是另一个地址时两者互换
            val (p, b) = if (other == record.url) {
                if (slot == AddressSlot.Primary) record.url to current else current to record.url
            } else {
                if (slot == AddressSlot.Primary) record.url to d.backupUrl else d.primaryUrl to record.url
            }
            d.copy(primaryUrl = p, backupUrl = b)
        }
        viewModelScope.launch {
            val d = repository.currentDevice.first() ?: return@launch
            _ui.update { it.copy(primary = AddressSlotUi(d.primaryUrl), backup = AddressSlotUi(d.backupUrl), message = "已设为${if (slot == AddressSlot.Primary) "主地址" else "备用地址"}") }
        }
    }

    fun removeHistory(record: AddressRecord) {
        val d = _ui.value.device ?: return
        if (record.url == d.primaryUrl || record.url == d.backupUrl) {
            _ui.update { it.copy(message = "正在使用的地址不能删除") }
            return
        }
        updateDevice { it.copy(addressHistory = it.addressHistory.filterNot { r -> r.url == record.url }) }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    private fun updateDevice(transform: (Device) -> Device) {
        val id = _ui.value.device?.id ?: return
        viewModelScope.launch { repository.update(id, transform) }
    }

    private fun slotUi(slot: AddressSlot) = if (slot == AddressSlot.Primary) _ui.value.primary else _ui.value.backup

    private fun setSlot(slot: AddressSlot, transform: (AddressSlotUi) -> AddressSlotUi) = _ui.update {
        when (slot) {
            AddressSlot.Primary -> it.copy(primary = transform(it.primary))
            AddressSlot.Backup -> it.copy(backup = transform(it.backup))
        }
    }
}
