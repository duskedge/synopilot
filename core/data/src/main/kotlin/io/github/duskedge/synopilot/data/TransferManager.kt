package io.github.duskedge.synopilot.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.github.duskedge.synopilot.network.FileStationApi
import io.github.duskedge.synopilot.network.RemoteFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID

enum class TransferDirection { Upload, Download }

enum class TransferStatus { Queued, Running, Done, Failed, Cancelled }

data class Transfer(
    val id: String,
    val direction: TransferDirection,
    val name: String,
    /** 上传：目标文件夹；下载：NAS 上的文件路径 */
    val remotePath: String,
    val size: Long?,
    val done: Long = 0,
    val status: TransferStatus = TransferStatus.Queued,
    val error: String? = null,
    /** 上传的来源（content:// Uri） */
    val source: String? = null,
    /** 下载完成后的文件（content:// 或文件路径），用来打开 */
    val result: String? = null,
    val bytesPerSec: Long = 0,
) {
    val progress: Float get() = size?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val finished: Boolean get() = status == TransferStatus.Done || status == TransferStatus.Failed || status == TransferStatus.Cancelled
}

/**
 * 上传 / 下载队列：一次处理一个，App 在前台期间持续进行。
 * 下载保存到「下载/SynoPilot」（Android 10 起通过 MediaStore，不需要存储权限）。
 */
class TransferManager(
    private val context: Context,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
) {
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var current: Pair<String, Job>? = null

    init {
        scope.launch {
            while (true) {
                val next = _transfers.value.firstOrNull { it.status == TransferStatus.Queued }
                if (next == null) {
                    wake.receive()
                    continue
                }
                val job = scope.launch { run(next) }
                current = next.id to job
                job.join()
                current = null
            }
        }
    }

    private fun enqueue(items: List<Transfer>) {
        _transfers.update { it + items }
        wake.trySend(Unit)
    }

    fun upload(folder: String, uris: List<Uri>) {
        val resolver = context.contentResolver
        enqueue(
            uris.map { uri ->
                var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                var size: Long? = null
                runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { name = it }
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                }
                Transfer(UUID.randomUUID().toString(), TransferDirection.Upload, name, folder, size, source = uri.toString())
            },
        )
    }

    fun download(files: List<RemoteFile>) {
        enqueue(files.filterNot { it.isDir }.map { Transfer(UUID.randomUUID().toString(), TransferDirection.Download, it.name, it.path, it.size) })
    }

    fun cancel(id: String) {
        val running = current
        if (running?.first == id) running.second.cancel()
        _transfers.update { list -> list.map { if (it.id == id && !it.finished) it.copy(status = TransferStatus.Cancelled) else it } }
    }

    fun retry(id: String) {
        _transfers.update { list -> list.map { if (it.id == id) it.copy(status = TransferStatus.Queued, done = 0, error = null, bytesPerSec = 0) else it } }
        wake.trySend(Unit)
    }

    fun clearFinished() = _transfers.update { list -> list.filterNot { it.finished } }

    private fun set(id: String, transform: (Transfer) -> Transfer) = _transfers.update { list -> list.map { if (it.id == id) transform(it) else it } }

    private suspend fun run(t: Transfer) {
        set(t.id) { it.copy(status = TransferStatus.Running, done = 0, error = null) }
        val meter = SpeedMeter()
        val progress: (Long, Long?) -> Unit = { done, total ->
            if (meter.shouldEmit(done)) set(t.id) { it.copy(done = done, size = total ?: it.size, bytesPerSec = meter.speed) }
        }
        try {
            val result = when (t.direction) {
                TransferDirection.Upload -> {
                    val uri = Uri.parse(t.source)
                    connection.request { api, s ->
                        api.upload(t.remotePath, t.name, t.size, s, overwrite = false, open = {
                            context.contentResolver.openInputStream(uri) ?: error("无法读取文件")
                        }, onProgress = progress)
                    }
                    null
                }
                TransferDirection.Download -> download(t, progress)
            }
            set(t.id) { it.copy(status = TransferStatus.Done, done = it.size ?: it.done, result = result, bytesPerSec = 0) }
        } catch (e: CancellationException) {
            set(t.id) { it.copy(status = TransferStatus.Cancelled, bytesPerSec = 0) }
        } catch (e: Exception) {
            set(t.id) { it.copy(status = TransferStatus.Failed, error = e.message ?: "传输失败", bytesPerSec = 0) }
        }
    }

    private suspend fun download(t: Transfer, progress: (Long, Long?) -> Unit): String = withContext(Dispatchers.IO) {
        val target = openTarget(t.name)
        try {
            target.stream.use { out ->
                connection.request { api, s -> FileStationApi.download(api, s, t.remotePath, out, onProgress = progress) }
            }
            target.commit()
            target.location
        } catch (e: Throwable) {
            target.discard()
            throw e
        }
    }

    private class Target(val stream: OutputStream, val location: String, val commit: () -> Unit, val discard: () -> Unit)

    private fun openTarget(name: String): Target {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SynoPilot")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法在手机上创建文件")
            return Target(
                stream = resolver.openOutputStream(uri) ?: error("无法写入文件"),
                location = uri.toString(),
                commit = { resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) },
                discard = { runCatching { resolver.delete(uri, null, null) } },
            )
        }
        // Android 8–9：保存到 App 专属的下载目录（不需要存储权限）
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SynoPilot").apply { mkdirs() }
        val file = uniqueFile(dir, name)
        return Target(file.outputStream(), file.absolutePath, commit = {}, discard = { file.delete() })
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (f.exists()) f = File(dir, "$base (${i++})$ext")
        return f
    }

    /** 每 250ms 最多更新一次进度，顺便算速度 */
    private class SpeedMeter {
        private var lastTime = 0L
        private var lastBytes = 0L
        var speed = 0L
            private set

        fun shouldEmit(done: Long): Boolean {
            val now = System.currentTimeMillis()
            if (lastTime == 0L) {
                lastTime = now
                lastBytes = done
                return true
            }
            val dt = now - lastTime
            if (dt < 250) return false
            speed = ((done - lastBytes) * 1000 / dt).coerceAtLeast(0)
            lastTime = now
            lastBytes = done
            return true
        }
    }
}
