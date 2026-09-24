package io.github.duskedge.synopilot.data

import io.github.duskedge.synopilot.network.CertificateInfo
import io.github.duskedge.synopilot.network.DsmApi
import io.github.duskedge.synopilot.network.DsmAuth
import io.github.duskedge.synopilot.network.DsmException
import io.github.duskedge.synopilot.network.DsmParsers
import io.github.duskedge.synopilot.network.DsmProbe
import io.github.duskedge.synopilot.network.DsmSession
import io.github.duskedge.synopilot.network.LoginResult
import io.github.duskedge.synopilot.network.ProbeFailure
import io.github.duskedge.synopilot.network.ProbeResult
import io.github.duskedge.synopilot.network.SystemInfo
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ConnectionState {
    data object NoDevice : ConnectionState
    data class Connecting(val device: Device) : ConnectionState
    data class Connected(val device: Device, val route: Route, val info: SystemInfo) : ConnectionState
    data class Failed(val device: Device, val reason: FailureReason) : ConnectionState

    val deviceOrNull: Device?
        get() = when (this) {
            NoDevice -> null
            is Connecting -> device
            is Connected -> device
            is Failed -> device
        }
}

sealed interface FailureReason {
    val message: String

    /** 所有地址都连不上 */
    data class Offline(override val message: String) : FailureReason

    /** 需要重新输入密码或两步验证码 */
    data class NeedsLogin(override val message: String) : FailureReason

    /** 证书变了（或第一次遇到），需要用户确认 */
    data class UntrustedCertificate(val url: String, val certificate: CertificateInfo) : FailureReason {
        override val message = "NAS 的证书和之前信任的不一致，请确认后再连接"
    }

    /** 地址指向了另一台 NAS */
    data class WrongDevice(val url: String, val serial: String) : FailureReason {
        override val message = "这个地址连到的是另一台 NAS（序列号 $serial）"
    }
}

/** 测试某个地址（服务端地址配置页用）的结果 */
data class AddressTest(val ok: Boolean, val latencyMs: Long?, val message: String, val certificate: CertificateInfo? = null)

/**
 * 当前设备的连接：选线路 → 校验是同一台 NAS → 保持会话。
 * 所有 DSM 请求都通过 [request] 发出，会话过期时自动重新登录一次。
 */
class ConnectionManager(
    private val repository: DeviceRepository,
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.NoDevice)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var api: DsmApi? = null
    private var session: DsmSession? = null

    /** App 在前台时才自动重试（后台检查由 WorkManager 负责，不在这里反复重连） */
    @Volatile private var foreground = false
    private var retryJob: Job? = null
    private var retryAttempt = 0

    fun setForeground(value: Boolean) {
        foreground = value
        if (value && (_state.value as? ConnectionState.Failed)?.reason is FailureReason.Offline) reconnect()
    }

    fun start() {
        scope.launch {
            // 连不上时按 5、10、15、30、60 秒的间隔自动重试（比如 NAS 正在重启）
            _state.collect { s ->
                retryJob?.cancel()
                when {
                    s is ConnectionState.Connected -> retryAttempt = 0
                    s is ConnectionState.Failed && s.reason is FailureReason.Offline && foreground -> {
                        val wait = RETRY_DELAYS_MS[retryAttempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
                        retryAttempt++
                        retryJob = scope.launch {
                            delay(wait)
                            if (foreground) reconnect()
                        }
                    }
                }
            }
        }
        scope.launch {
            // 设备本身或它的地址配置变化时重新连接
            repository.currentDevice
                .map { it?.let { d -> ConnectKey(d.id, d.primaryUrl, d.backupUrl, d.prefer, d.quickConnectId, d.quickConnectFallback, d.pinnedCerts) } }
                .distinctUntilChanged()
                .collect { key -> if (key == null) reset(ConnectionState.NoDevice) else reconnect() }
        }
    }

    private data class ConnectKey(
        val id: String, val primary: String, val backup: String, val prefer: PreferRoute,
        val qc: String, val qcFallback: Boolean, val pins: List<String>,
    )

    private fun reset(newState: ConnectionState) {
        api = null
        session = null
        _state.value = newState
    }

    /** 重新选线路并连接（切换设备、网络变化、用户点「重试」时调用） */
    fun reconnect() {
        scope.launch { mutex.withLock { connectLocked() } }
    }

    private suspend fun connectLocked() {
        val device = repository.currentDevice.first() ?: return reset(ConnectionState.NoDevice)
        _state.value = ConnectionState.Connecting(device)
        api = null
        session = null

        val route = try {
            selectRoute(device)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RouteFailure) {
            _state.value = ConnectionState.Failed(device, e.reason)
            return
        }

        val candidate = DsmApi(http, route.url)
        try {
            val (s, info) = ensureSession(device, candidate)
            if (device.serial.isNotBlank() && info.serial.isNotBlank() && info.serial != device.serial) {
                _state.value = ConnectionState.Failed(device, FailureReason.WrongDevice(route.url, info.serial))
                return
            }
            api = candidate
            session = s
            val updated = repository.device(device.id)?.let { d ->
                d.withAddressUsed(route.url, clock()).copy(
                    model = info.model.ifBlank { d.model },
                    serial = d.serial.ifBlank { info.serial },
                    dsmVersion = info.versionString.ifBlank { d.dsmVersion },
                )
            } ?: device
            if (updated != device) repository.update(device.id) { updated }
            _state.value = ConnectionState.Connected(updated, route, info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoginRequired) {
            _state.value = ConnectionState.Failed(device, FailureReason.NeedsLogin(e.message ?: "需要重新登录"))
        } catch (e: DsmException) {
            _state.value = ConnectionState.Failed(device, FailureReason.Offline(e.message ?: "连接失败"))
        } catch (e: Exception) {
            _state.value = ConnectionState.Failed(device, FailureReason.Offline(e.message ?: "连接失败"))
        }
    }

    private class RouteFailure(val reason: FailureReason) : Exception(reason.message)
    private class LoginRequired(message: String) : Exception(message)

    /** 并发探测主地址和备用地址，按「优先使用」选择；都不通时走 QuickConnect 兜底。 */
    private suspend fun selectRoute(device: Device): Route {
        val (primary, backup) = coroutineScope {
            val p = device.primaryUrl.takeIf { it.isNotBlank() }?.let { async { DsmProbe.probe(http, it) } }
            val b = device.backupUrl.takeIf { it.isNotBlank() }?.let { async { DsmProbe.probe(http, it) } }
            p?.await() to b?.await()
        }
        RouteSelector.choose(device.prefer, primary?.let { it.url to it.latencyMs }, backup?.let { it.url to it.latencyMs })
            ?.let { return it }

        // 证书问题优先提示（比「连不上」更有用）
        listOfNotNull(primary, backup).firstNotNullOfOrNull { it.certificateFailure() }?.let { throw RouteFailure(it) }

        val qc = device.quickConnectUrl
        if (device.quickConnectFallback && qc != null) {
            val r = DsmProbe.probe(http, qc, timeoutMillis = 6_000)
            if (r.ok) return Route(Slot.QuickConnect, r.url, AddressKind.QuickConnect, r.latencyMs ?: 0)
            r.certificateFailure()?.let { throw RouteFailure(it) }
        }
        val detail = listOfNotNull(primary, backup).joinToString("；") { "${it.url}：${describe(it.failure)}" }
        throw RouteFailure(FailureReason.Offline(if (detail.isBlank()) "还没有配置服务端地址" else "所有地址都连不上（$detail）"))
    }

    private fun ProbeResult.certificateFailure(): FailureReason? =
        (failure as? ProbeFailure.UntrustedCertificate)?.let { FailureReason.UntrustedCertificate(url, it.certificate) }

    /** 用已保存的会话；失效时用保存的密码（和设备令牌）重新登录。返回会话和设备信息。 */
    private suspend fun ensureSession(device: Device, api: DsmApi): Pair<DsmSession, SystemInfo> {
        val secrets = repository.secrets(device.id) ?: throw LoginRequired("没有保存登录信息，请重新登录")
        val saved = secrets.sid?.let { DsmSession(it, secrets.synoToken) }
        if (saved != null) {
            try {
                return saved to fetchInfo(api, saved)
            } catch (e: DsmException) {
                if (!e.isSessionExpired) throw e
            }
        }
        val fresh = login(device, api, secrets)
        return fresh to fetchInfo(api, fresh)
    }

    private suspend fun login(device: Device, api: DsmApi, secrets: DeviceSecrets): DsmSession {
        when (val r = DsmAuth.login(api, device.account, secrets.password, deviceToken = secrets.deviceToken, trustDevice = false)) {
            is LoginResult.Success -> {
                repository.saveSecrets(device.id, secrets.copy(sid = r.session.sid, synoToken = r.session.synoToken, deviceToken = r.deviceToken))
                return r.session
            }
            LoginResult.NeedsOtp -> throw LoginRequired("需要重新输入两步验证码")
            is LoginResult.Failed -> if (r.code in setOf(400, 401, 402, 404, 406, 408, 409, 410)) {
                throw LoginRequired(r.message)
            } else {
                throw DsmException(r.code ?: 0, DsmException.AUTH_API, r.message)
            }
        }
    }

    private suspend fun fetchInfo(api: DsmApi, session: DsmSession): SystemInfo =
        DsmParsers.systemInfo(api.call("SYNO.DSM.Info", "getinfo", version = 2, session = session))

    /**
     * 发出一个需要登录的 DSM 请求。会话过期时自动重新登录并重试一次；
     * 网络错误会触发重新选线路。
     */
    suspend fun <T> request(block: suspend (DsmApi, DsmSession) -> T): T {
        val (a, s) = mutex.withLock {
            val a = api
            val s = session
            if (a == null || s == null) throw DsmException(0, "", "尚未连接到 NAS")
            a to s
        }
        return try {
            block(a, s)
        } catch (e: DsmException) {
            if (!e.isSessionExpired) throw e
            val renewed = mutex.withLock {
                val device = repository.currentDevice.first() ?: throw e
                val secrets = repository.secrets(device.id) ?: throw e
                try {
                    login(device, a, secrets).also { session = it }
                } catch (le: LoginRequired) {
                    _state.value = ConnectionState.Failed(device, FailureReason.NeedsLogin(le.message ?: "需要重新登录"))
                    throw DsmException(e.code, e.api, le.message ?: "需要重新登录")
                }
            }
            block(a, renewed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            reconnect()
            throw e
        }
    }

    /** 服务端地址配置页：测试一个地址是否可用、是否是同一台 NAS。 */
    suspend fun testAddress(device: Device, url: String): AddressTest {
        if (!Addresses.isValid(url)) return AddressTest(false, null, "地址格式不对，需要以 http:// 或 https:// 开头")
        val probe = DsmProbe.probe(http, url, timeoutMillis = 5_000)
        probe.failure?.let { f ->
            return AddressTest(false, null, describe(f), (f as? ProbeFailure.UntrustedCertificate)?.certificate)
        }
        val s = mutex.withLock { session }
        if (s != null && device.serial.isNotBlank()) {
            // 同一台 NAS 上会话是通用的：用当前会话读序列号，就能确认是不是同一台
            val serial = runCatching { fetchInfo(DsmApi(http, url), s).serial }.getOrNull()
            if (serial != null && serial != device.serial) {
                return AddressTest(false, probe.latencyMs, "这是另一台 NAS（序列号 $serial），两个地址必须指向同一台")
            }
            if (serial == null) {
                return AddressTest(true, probe.latencyMs, "可以连接，但无法确认是否是同一台 NAS（登录后会再次校验）")
            }
        }
        return AddressTest(true, probe.latencyMs, "可以连接")
    }

    companion object {
        private val RETRY_DELAYS_MS = longArrayOf(5_000, 10_000, 15_000, 30_000, 60_000)

        fun describe(failure: ProbeFailure?): String = when (failure) {
            null -> "正常"
            ProbeFailure.Timeout -> "连接超时"
            ProbeFailure.NotDsm -> "不是群晖 DSM"
            is ProbeFailure.UntrustedCertificate -> "证书需要确认"
            is ProbeFailure.Unreachable -> "无法连接"
        }
    }
}

