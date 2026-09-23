package io.github.duskedge.synopilot.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.duskedge.synopilot.data.ConnectionManager
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.DownloadsData
import io.github.duskedge.synopilot.data.DownloadsRepository
import io.github.duskedge.synopilot.designsystem.component.SpMessage
import io.github.duskedge.synopilot.network.download.Category
import io.github.duskedge.synopilot.network.download.DownloadTask
import io.github.duskedge.synopilot.network.download.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TaskTab { Active, Queued, Seeding, Done }

fun TaskState.tab(): TaskTab = when (this) {
    TaskState.Downloading, TaskState.Paused, TaskState.Checking, TaskState.Error -> TaskTab.Active
    TaskState.Queued -> TaskTab.Queued
    TaskState.Seeding -> TaskTab.Seeding
    TaskState.Completed -> TaskTab.Done
}

data class DownloadsUi(
    val connection: ConnectionState = ConnectionState.NoDevice,
    val data: DownloadsData = DownloadsData(),
    /** null = 全部下载器 */
    val engine: String? = null,
    val tab: TaskTab = TaskTab.Active,
    val clipboardLink: String? = null,
) {
    val pool: List<DownloadTask> get() = data.tasks.filter { engine == null || it.engineId == engine }
    fun count(t: TaskTab) = pool.count { it.state.tab() == t }
    val visible: List<DownloadTask> get() = pool.filter { it.state.tab() == tab }
    /** 只统计当前可用的下载器（断线时缓存里的速度没有意义） */
    private val live get() = data.engines.filter { (engine == null || it.config.id == engine) && it.problem == null }
    val downloadSpeed: Long get() = live.sumOf { e -> e.transfer?.downloadSpeed ?: e.tasks.sumOf { it.downloadSpeed } }
    val uploadSpeed: Long get() = live.sumOf { e -> e.transfer?.uploadSpeed ?: e.tasks.sumOf { it.uploadSpeed } }
    fun engineName(id: String) = data.engines.firstOrNull { it.config.id == id }?.config?.name.orEmpty()
}

class DownloadsViewModel(
    connection: ConnectionManager,
    private val repository: DownloadsRepository,
) : ViewModel() {
    private val local = MutableStateFlow(DownloadsUi())
    private val _messages = Channel<SpMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    /** 已经提示过的剪贴板链接，不重复提示 */
    private var seenClip: String? = null

    val ui: StateFlow<DownloadsUi> = combine(connection.state, repository.observe(), local) { c, d, l ->
        val engine = l.engine?.takeIf { id -> d.engines.any { it.config.id == id } }
        l.copy(connection = c, data = d, engine = engine)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUi(connection.state.value))

    fun setEngine(id: String?) = local.update { it.copy(engine = id) }
    fun setTab(t: TaskTab) = local.update { it.copy(tab = t) }

    /** 回到页面时检查剪贴板里有没有磁力链接 / 种子地址 */
    fun onClipboard(text: String?) {
        val link = text?.trim()?.lineSequence()?.firstOrNull { isLink(it.trim()) }?.trim()
        if (link != null && link != seenClip) local.update { it.copy(clipboardLink = link) }
    }

    fun dismissClipboard() {
        seenClip = local.value.clipboardLink
        local.update { it.copy(clipboardLink = null) }
    }

    private fun launch(success: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                if (success != null) _messages.send(SpMessage(success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(SpMessage(e.message ?: "操作失败"))
            }
        }
    }

    fun pause(t: DownloadTask) = launch("已暂停") { repository.pause(listOf(t)) }
    fun resume(t: DownloadTask) = launch("已继续") { repository.resume(listOf(t)) }
    fun remove(t: DownloadTask, deleteFiles: Boolean) = launch(if (deleteFiles) "已删除任务和文件" else "已删除任务") { repository.remove(listOf(t), deleteFiles) }

    /** 清除已完成的任务记录（不删除文件） */
    fun clearDone() {
        val done = ui.value.pool.filter { it.state == TaskState.Completed }
        if (done.isEmpty()) return
        launch("已清除 ${done.size} 条记录") { repository.remove(done, deleteFiles = false) }
    }

    fun add(engineId: String, urls: List<String>, savePath: String?, category: String?, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                repository.add(engineId, urls, savePath, category)
                if (urls.any { it == local.value.clipboardLink }) dismissClipboard()
                _messages.send(SpMessage(if (urls.size > 1) "已添加 ${urls.size} 个任务" else "已添加任务"))
                local.update { it.copy(tab = TaskTab.Active) }
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(SpMessage(e.message ?: "添加失败"))
                onResult(false)
            }
        }
    }

    suspend fun categories(engineId: String): List<Category> = repository.categories(engineId)

    fun setLimits(engineId: String, download: Long?, upload: Long?, onDone: () -> Unit) {
        launch(if (download == null && upload == null) "已取消限速" else "已设置限速") {
            repository.setLimits(engineId, download, upload)
            onDone()
        }
    }

    companion object {
        fun isLink(s: String): Boolean =
            s.startsWith("magnet:?", ignoreCase = true) ||
                ((s.startsWith("http://") || s.startsWith("https://")) && s.substringBefore('?').endsWith(".torrent", ignoreCase = true)) ||
                s.startsWith("ed2k://", ignoreCase = true)
    }
}
