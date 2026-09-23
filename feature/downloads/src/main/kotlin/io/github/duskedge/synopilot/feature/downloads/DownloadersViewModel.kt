package io.github.duskedge.synopilot.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.duskedge.synopilot.data.ConnectionManager
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.DeviceRepository
import io.github.duskedge.synopilot.data.DiscoveredDownloader
import io.github.duskedge.synopilot.data.DownloaderConfig
import io.github.duskedge.synopilot.data.DownloadsRepository
import io.github.duskedge.synopilot.designsystem.component.SpMessage
import io.github.duskedge.synopilot.network.download.EngineKind
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class DownloadersUi(
    val downloaders: List<DownloaderConfig> = emptyList(),
    val defaultId: String = "",
    val connected: Boolean = false,
    /** 当前线路说明，比如「通过 QuickConnect 连接」 */
    val routeKind: String? = null,
)

/** 编辑中的下载器 */
data class DownloaderDraft(
    val config: DownloaderConfig,
    val isNew: Boolean,
    /** 新建时必填；编辑时留空表示不修改 */
    val password: String = "",
    val isDefault: Boolean = false,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean? = null,
)

data class DiscoverUi(val running: Boolean = false, val found: List<DiscoveredDownloader>? = null)

class DownloadersViewModel(
    connection: ConnectionManager,
    devices: DeviceRepository,
    private val repository: DownloadsRepository,
) : ViewModel() {
    val ui: StateFlow<DownloadersUi> = combine(devices.currentDevice, connection.state) { d, s ->
        DownloadersUi(
            downloaders = d?.downloaders.orEmpty(),
            defaultId = d?.defaultDownloaderId.orEmpty(),
            connected = s is ConnectionState.Connected,
            routeKind = (s as? ConnectionState.Connected)?.route?.kind?.name,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadersUi())

    private val _draft = MutableStateFlow<DownloaderDraft?>(null)
    val draft: StateFlow<DownloaderDraft?> = _draft.asStateFlow()
    private val _discover = MutableStateFlow(DiscoverUi())
    val discover: StateFlow<DiscoverUi> = _discover.asStateFlow()
    private val _messages = Channel<SpMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun newDraft(kind: EngineKind) {
        val name = when (kind) {
            EngineKind.DownloadStation -> "Download Station"
            EngineKind.QBittorrent -> "qBittorrent"
            EngineKind.Transmission -> "Transmission"
        }
        _draft.value = DownloaderDraft(
            DownloaderConfig(UUID.randomUUID().toString().take(8), kind, name, username = if (kind == EngineKind.QBittorrent) "admin" else ""),
            isNew = true,
            isDefault = ui.value.downloaders.isEmpty(),
        )
    }

    fun edit(config: DownloaderConfig) {
        _draft.value = DownloaderDraft(config, isNew = false, isDefault = ui.value.defaultId == config.id)
    }

    fun fromDiscovered(d: DiscoveredDownloader) {
        _draft.value = DownloaderDraft(d.config, isNew = true, isDefault = ui.value.downloaders.isEmpty())
        _discover.update { it.copy(found = it.found?.filterNot { f -> f.config.id == d.config.id }) }
    }

    fun update(transform: (DownloaderDraft) -> DownloaderDraft) = _draft.update { it?.let(transform)?.copy(testResult = null, testOk = null) }

    fun closeDraft() {
        _draft.value = null
    }

    private fun DownloaderDraft.passwordArg(): String? = if (isNew || password.isNotEmpty()) password else null

    fun test() {
        val d = _draft.value ?: return
        _draft.update { it?.copy(testing = true, testResult = null, testOk = null) }
        viewModelScope.launch {
            val r = repository.test(d.config.normalized(), d.passwordArg())
            _draft.update {
                it?.copy(
                    testing = false,
                    testOk = r.isSuccess,
                    testResult = r.fold({ v -> "连接成功 · ${v.app.ifBlank { "已连接" }}" }, { e -> e.message ?: "连接失败" }),
                )
            }
        }
    }

    fun save() {
        val d = _draft.value ?: return
        val cfg = d.config.normalized()
        if (cfg.name.isBlank()) return
        viewModelScope.launch {
            repository.save(cfg, d.passwordArg())
            if (d.isDefault) repository.setDefault(cfg.id)
            _draft.value = null
            _messages.send(SpMessage(if (d.isNew) "已添加 ${cfg.name}" else "已保存"))
        }
    }

    fun delete() {
        val d = _draft.value ?: return
        viewModelScope.launch {
            repository.delete(d.config.id)
            _draft.value = null
            _messages.send(SpMessage("已删除 ${d.config.name}"))
        }
    }

    fun setEnabled(config: DownloaderConfig, enabled: Boolean) {
        viewModelScope.launch { repository.save(config.copy(enabled = enabled), null) }
    }

    fun runDiscovery() {
        _discover.value = DiscoverUi(running = true)
        viewModelScope.launch {
            val found = runCatching { repository.discover() }.getOrDefault(emptyList())
            _discover.value = DiscoverUi(running = false, found = found)
            if (found.isEmpty()) _messages.send(SpMessage("没有发现新的下载器"))
        }
    }

    fun closeDiscovery() {
        _discover.value = DiscoverUi()
    }
}

private fun DownloaderConfig.normalized() = copy(
    name = name.trim(),
    lanUrl = normalizeUrl(lanUrl),
    remoteUrl = normalizeUrl(remoteUrl),
    username = username.trim(),
)

private fun normalizeUrl(url: String): String {
    val u = url.trim().trimEnd('/')
    if (u.isEmpty()) return u
    return if (u.startsWith("http://") || u.startsWith("https://")) u else "http://$u"
}
