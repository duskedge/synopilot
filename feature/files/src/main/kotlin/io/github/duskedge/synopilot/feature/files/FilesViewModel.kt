package io.github.duskedge.synopilot.feature.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.duskedge.synopilot.data.ConnectionManager
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.FilesRepository
import io.github.duskedge.synopilot.data.Transfer
import io.github.duskedge.synopilot.data.TransferManager
import io.github.duskedge.synopilot.data.TransferStatus
import io.github.duskedge.synopilot.designsystem.component.SpMessage
import io.github.duskedge.synopilot.network.RemoteFile
import io.github.duskedge.synopilot.network.ShareLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class FilesTab { Browse, Transfers }

data class BrowseState(
    val path: String = FilesRepository.ROOT,
    val files: List<RemoteFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** 当前文件夹内的筛选 */
    val query: String = "",
    /** 在子文件夹里搜索的结果；null 表示没有在搜索 */
    val searchResults: List<RemoteFile>? = null,
    val searching: Boolean = false,
    val tab: FilesTab = FilesTab.Browse,
) {
    val visible: List<RemoteFile>
        get() = searchResults ?: if (query.isBlank()) files else files.filter { it.name.contains(query.trim(), ignoreCase = true) }
}

data class FilesUi(
    val connection: ConnectionState = ConnectionState.NoDevice,
    val browse: BrowseState = BrowseState(),
    val transfers: List<Transfer> = emptyList(),
) {
    val activeTransfers: Int get() = transfers.count { it.status == TransferStatus.Running || it.status == TransferStatus.Queued }
}

enum class PreviewKind { Image, Text, Other }

data class PreviewUi(
    val file: RemoteFile,
    val kind: PreviewKind,
    val loading: Boolean = true,
    val image: ByteArray? = null,
    val text: String? = null,
    val error: String? = null,
)

data class ShareUi(val file: RemoteFile, val creating: Boolean = false, val link: ShareLink? = null, val error: String? = null)

class FilesViewModel(
    private val connection: ConnectionManager,
    private val files: FilesRepository,
    private val transfers: TransferManager,
) : ViewModel() {
    private val browse = MutableStateFlow(BrowseState())
    private val _messages = Channel<SpMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private var loadJob: Job? = null

    val ui: StateFlow<FilesUi> = combine(connection.state, browse, transfers.transfers) { c, b, t -> FilesUi(c, b, t.reversed()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUi(connection.state.value))

    private val _preview = MutableStateFlow<PreviewUi?>(null)
    val preview: StateFlow<PreviewUi?> = _preview.asStateFlow()
    private val _share = MutableStateFlow<ShareUi?>(null)
    val share: StateFlow<ShareUi?> = _share.asStateFlow()

    init {
        // 连上（或切换设备）后重新加载当前目录；切换设备时回到根目录
        viewModelScope.launch {
            connection.state
                .map { (it as? ConnectionState.Connected)?.device?.id }
                .distinctUntilChanged()
                .collect { id ->
                    if (id != null) {
                        if (lastDevice != null && lastDevice != id) browse.update { BrowseState(tab = it.tab) }
                        lastDevice = id
                        load()
                    }
                }
        }
    }

    private var lastDevice: String? = null

    fun setTab(tab: FilesTab) = browse.update { it.copy(tab = tab) }

    fun open(path: String) {
        browse.update { it.copy(path = path, files = emptyList(), query = "", searchResults = null, error = null) }
        load()
    }

    /** 返回上一级；已经在根目录时返回 false */
    fun up(): Boolean {
        val b = browse.value
        if (b.searchResults != null) {
            browse.update { it.copy(searchResults = null) }
            return true
        }
        if (b.path == FilesRepository.ROOT) return false
        open(FilesRepository.parent(b.path))
        return true
    }

    fun refresh() = load()

    private fun load() {
        val path = browse.value.path
        loadJob?.cancel()
        browse.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val list = files.list(path)
                browse.update { if (it.path == path) it.copy(files = list.files, loading = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                browse.update { if (it.path == path) it.copy(loading = false, error = e.message ?: "加载失败") else it }
            }
        }
    }

    fun setQuery(q: String) = browse.update { it.copy(query = q, searchResults = if (q.isBlank()) null else it.searchResults) }

    /** 在当前文件夹及子文件夹里搜索 */
    fun deepSearch() {
        val b = browse.value
        if (b.query.isBlank()) return
        browse.update { it.copy(searching = true) }
        viewModelScope.launch {
            try {
                val results = files.search(b.path, b.query.trim())
                browse.update { it.copy(searchResults = results, searching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                browse.update { it.copy(searching = false) }
                _messages.send(SpMessage(e.message ?: "搜索失败"))
            }
        }
    }

    suspend fun thumbnail(path: String, size: String = "small"): ByteArray? = files.thumbnail(path, size)

    private fun act(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _messages.send(SpMessage(success))
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(SpMessage(e.message ?: "操作失败"))
            }
        }
    }

    fun createFolder(name: String) {
        val parent = browse.value.path
        act("已新建文件夹 $name") { files.createFolder(parent, name.trim()) }
    }

    fun rename(file: RemoteFile, newName: String) = act("已重命名") { files.rename(file.path, newName.trim()) }

    fun delete(file: RemoteFile) = act("已删除 ${file.name}") {
        files.delete(listOf(file.path))
        browse.update { b -> b.copy(searchResults = b.searchResults?.filterNot { it.path == file.path }) }
    }

    fun download(file: RemoteFile) {
        transfers.download(listOf(file))
        viewModelScope.launch { _messages.send(SpMessage("已加入下载队列", "查看", action = { setTab(FilesTab.Transfers) })) }
    }

    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        transfers.upload(browse.value.path, uris)
        viewModelScope.launch { _messages.send(SpMessage("${uris.size} 个文件开始上传", "查看", action = { setTab(FilesTab.Transfers) })) }
    }

    fun cancelTransfer(id: String) = transfers.cancel(id)
    fun retryTransfer(id: String) = transfers.retry(id)
    fun clearFinished() = transfers.clearFinished()

    /** 上传完成后刷新当前目录 */
    fun onTransferFinished() = load()

    fun openPreview(file: RemoteFile) {
        val kind = FileKinds.previewKind(file)
        _preview.value = PreviewUi(file, kind, loading = kind != PreviewKind.Other)
        viewModelScope.launch {
            try {
                when (kind) {
                    PreviewKind.Image -> {
                        val bytes = files.thumbnail(file.path, "large") ?: error("无法加载图片")
                        _preview.update { p -> p?.takeIf { it.file == file }?.copy(image = bytes, loading = false) ?: p }
                    }
                    PreviewKind.Text -> {
                        val text = files.readText(file.path)
                        _preview.update { p -> p?.takeIf { it.file == file }?.copy(text = text, loading = false) ?: p }
                    }
                    PreviewKind.Other -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _preview.update { p -> p?.takeIf { it.file == file }?.copy(error = e.message ?: "预览失败", loading = false) ?: p }
            }
        }
    }

    fun closePreview() {
        _preview.value = null
    }

    fun openShare(file: RemoteFile) {
        _share.value = ShareUi(file)
    }

    fun createShare(password: String?, days: Int?) {
        val s = _share.value ?: return
        _share.value = s.copy(creating = true, error = null)
        viewModelScope.launch {
            try {
                val link = files.share(s.file.path, password, days?.let { LocalDate.now().plusDays(it.toLong()) })
                _share.update { it?.copy(creating = false, link = link) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _share.update { it?.copy(creating = false, error = e.message ?: "创建失败") }
            }
        }
    }

    fun closeShare() {
        _share.value = null
    }
}
