package io.github.duskedge.synopilot.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.duskedge.synopilot.data.AddressKind
import io.github.duskedge.synopilot.data.AddressRecord
import io.github.duskedge.synopilot.data.Addresses
import io.github.duskedge.synopilot.data.ConnectionManager
import io.github.duskedge.synopilot.data.Device
import io.github.duskedge.synopilot.data.DeviceRepository
import io.github.duskedge.synopilot.data.DeviceSecrets
import io.github.duskedge.synopilot.network.CertificateInfo
import io.github.duskedge.synopilot.network.DiscoveredNas
import io.github.duskedge.synopilot.network.DsmApi
import io.github.duskedge.synopilot.network.DsmAuth
import io.github.duskedge.synopilot.network.DsmException
import io.github.duskedge.synopilot.network.DsmParsers
import io.github.duskedge.synopilot.network.DsmProbe
import io.github.duskedge.synopilot.network.LanDiscovery
import io.github.duskedge.synopilot.network.LoginResult
import io.github.duskedge.synopilot.network.ProbeFailure
import io.ktor.client.HttpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class OnboardingStep { Discover, Login, Otp, Done }

data class PendingCertificate(val url: String, val certificate: CertificateInfo)

data class OnboardingUi(
    val step: OnboardingStep = OnboardingStep.Discover,
    val scanning: Boolean = false,
    val lanAvailable: Boolean = true,
    val discovered: List<DiscoveredNas> = emptyList(),
    val address: String = "",
    val account: String = "",
    val password: String = "",
    val otp: String = "",
    val trustDevice: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val certificate: PendingCertificate? = null,
    val deviceName: String = "",
    val biometricLock: Boolean = false,
    /** 重新登录已有设备（而不是添加新设备） */
    val relogin: Boolean = false,
)

class OnboardingViewModel(
    private val discovery: LanDiscovery,
    private val http: HttpClient,
    private val repository: DeviceRepository,
    private val connection: ConnectionManager,
    private val existingDeviceId: String?,
) : ViewModel() {

    private val _ui = MutableStateFlow(OnboardingUi(relogin = existingDeviceId != null))
    val ui: StateFlow<OnboardingUi> = _ui.asStateFlow()

    private var scanJob: Job? = null
    private val trustedCerts = mutableSetOf<String>()

    init {
        if (existingDeviceId != null) {
            viewModelScope.launch {
                val d = repository.device(existingDeviceId) ?: return@launch
                val url = d.addressHistory.firstOrNull()?.url ?: d.primaryUrl.ifBlank { d.backupUrl }
                trustedCerts += d.pinnedCerts
                _ui.update { it.copy(step = OnboardingStep.Login, address = url, account = d.account) }
            }
        } else {
            startScan()
        }
    }

    fun startScan() {
        scanJob?.cancel()
        _ui.update { it.copy(scanning = true, discovered = emptyList(), lanAvailable = discovery.localPrefix() != null) }
        scanJob = viewModelScope.launch {
            discovery.scan().collect { nas -> _ui.update { u -> u.copy(discovered = (u.discovered + nas).sortedBy { it.host }) } }
            _ui.update { it.copy(scanning = false) }
        }
    }

    fun pick(nas: DiscoveredNas) {
        scanJob?.cancel()
        _ui.update { it.copy(step = OnboardingStep.Login, address = nas.url, scanning = false, error = null) }
    }

    fun manual() {
        scanJob?.cancel()
        _ui.update { it.copy(step = OnboardingStep.Login, scanning = false, error = null) }
    }

    fun back() {
        _ui.update {
            when (it.step) {
                OnboardingStep.Otp -> it.copy(step = OnboardingStep.Login, otp = "", error = null)
                OnboardingStep.Login -> if (it.relogin) it else it.copy(step = OnboardingStep.Discover, error = null)
                else -> it
            }
        }
    }

    fun setAddress(v: String) = _ui.update { it.copy(address = v, error = null) }
    fun setAccount(v: String) = _ui.update { it.copy(account = v, error = null) }
    fun setPassword(v: String) = _ui.update { it.copy(password = v, error = null) }
    fun setTrustDevice(v: Boolean) = _ui.update { it.copy(trustDevice = v) }

    fun setOtp(v: String) {
        val digits = v.filter(Char::isDigit).take(6)
        _ui.update { it.copy(otp = digits, error = null) }
        if (digits.length == 6) submitOtp()
    }

    fun submitLogin() = attempt(otp = null)

    fun submitOtp() = attempt(otp = _ui.value.otp)

    fun trustCertificate() {
        val pending = _ui.value.certificate ?: return
        trustedCerts += pending.certificate.sha256
        repository.trustTemporarily(pending.certificate.sha256)
        _ui.update { it.copy(certificate = null) }
        attempt(otp = _ui.value.otp.takeIf { _ui.value.step == OnboardingStep.Otp })
    }

    fun rejectCertificate() = _ui.update { it.copy(certificate = null, error = "已取消：没有信任 NAS 的证书") }

    fun setBiometricLock(on: Boolean) {
        _ui.update { it.copy(biometricLock = on) }
        viewModelScope.launch { repository.updateSettings { s -> s.copy(biometricLock = on) } }
    }

    private fun attempt(otp: String?) {
        val u = _ui.value
        if (u.busy) return
        if (!Addresses.isValid(DsmApi.normalizeBaseUrl(u.address))) return fail("地址格式不对，例如 https://192.168.1.100:5001")
        if (u.account.isBlank() || u.password.isEmpty()) return fail("请填写用户名和密码")
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val url = DsmApi.normalizeBaseUrl(u.address)
                val probe = DsmProbe.probe(http, url, timeoutMillis = 8_000)
                when (val f = probe.failure) {
                    is ProbeFailure.UntrustedCertificate -> {
                        _ui.update { it.copy(busy = false, certificate = PendingCertificate(url, f.certificate)) }
                        return@launch
                    }
                    ProbeFailure.NotDsm -> return@launch fail("这个地址不是群晖 DSM，请检查端口（默认 5000 / 5001）")
                    ProbeFailure.Timeout -> return@launch fail("连接超时，请确认地址和网络")
                    is ProbeFailure.Unreachable -> return@launch fail("无法连接：${f.message}")
                    null -> Unit
                }
                val api = DsmApi(http, url)
                val previousToken = existingDeviceId?.let { repository.secrets(it)?.deviceToken }
                when (val r = DsmAuth.login(api, u.account.trim(), u.password, otp = otp, deviceToken = previousToken, trustDevice = u.trustDevice)) {
                    is LoginResult.Success -> finish(api, url, r)
                    LoginResult.NeedsOtp -> _ui.update { it.copy(busy = false, step = OnboardingStep.Otp, otp = "") }
                    is LoginResult.Failed -> fail(r.message)
                }
            } catch (e: DsmException) {
                fail(e.message ?: "登录失败")
            } catch (e: Exception) {
                fail("登录失败：${e.message}")
            }
        }
    }

    private suspend fun finish(api: DsmApi, url: String, result: LoginResult.Success) {
        val info = DsmParsers.systemInfo(api.call("SYNO.DSM.Info", "getinfo", 2, session = result.session))
        val now = System.currentTimeMillis()
        val u = _ui.value
        val existing = existingDeviceId?.let { repository.device(it) }
            ?: repository.devices.first().firstOrNull { it.serial.isNotBlank() && it.serial == info.serial }
        val lan = Addresses.kindOf(url) == AddressKind.Lan
        val device = (existing ?: Device(
            id = UUID.randomUUID().toString(),
            name = info.model.ifBlank { "群晖 NAS" },
            account = u.account.trim(),
            addedAt = now,
        )).let { d ->
            d.copy(
                account = u.account.trim(),
                model = info.model.ifBlank { d.model },
                serial = info.serial.ifBlank { d.serial },
                dsmVersion = info.versionString.ifBlank { d.dsmVersion },
                // 局域网地址默认作为备用地址，其他地址作为主地址
                primaryUrl = if (!lan && d.primaryUrl.isBlank()) url else d.primaryUrl,
                backupUrl = if (lan && d.backupUrl.isBlank()) url else d.backupUrl,
                pinnedCerts = (d.pinnedCerts + trustedCerts).distinct(),
                addressHistory = (listOf(AddressRecord(url, now, info.serial, info.model)) + d.addressHistory.filterNot { it.url == url }).take(12),
            )
        }
        repository.upsert(device)
        repository.saveSecrets(
            device.id,
            DeviceSecrets(
                password = u.password,
                sid = result.session.sid,
                synoToken = result.session.synoToken,
                deviceToken = result.deviceToken,
            ),
        )
        repository.setCurrent(device.id)
        connection.reconnect()
        val biometric = repository.appSettings.first().biometricLock
        _ui.update { it.copy(busy = false, step = OnboardingStep.Done, deviceName = device.name, password = "", biometricLock = biometric) }
    }

    private fun fail(message: String) = _ui.update { it.copy(busy = false, error = message) }

    override fun onCleared() {
        scanJob?.cancel()
    }
}
