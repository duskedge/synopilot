package io.github.duskedge.synopilot.data

import android.util.LruCache
import io.github.duskedge.synopilot.network.FileList
import io.github.duskedge.synopilot.network.FileStationApi
import io.github.duskedge.synopilot.network.RemoteFile
import io.github.duskedge.synopilot.network.ShareLink
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** 文件浏览：路径为 "/" 时列出共享文件夹。 */
class FilesRepository(private val connection: ConnectionManager) {
    private val thumbs = object : LruCache<String, ByteArray>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val thumbLimiter = Semaphore(3)

    suspend fun list(path: String): FileList = connection.request { api, s ->
        if (path == ROOT) FileStationApi.shares(api, s) else FileStationApi.list(api, s, path)
    }

    suspend fun search(path: String, pattern: String): List<RemoteFile> = connection.request { api, s ->
        val folders = if (path == ROOT) FileStationApi.shares(api, s).files.map { it.path } else listOf(path)
        FileStationApi.search(api, s, folders, "*$pattern*")
    }

    suspend fun createFolder(parent: String, name: String) = connection.request { api, s -> FileStationApi.createFolder(api, s, parent, name) }

    suspend fun rename(path: String, newName: String) = connection.request { api, s -> FileStationApi.rename(api, s, path, newName) }

    suspend fun delete(paths: List<String>) = connection.request { api, s -> FileStationApi.delete(api, s, paths) }

    suspend fun share(path: String, password: String?, expires: LocalDate?): ShareLink =
        connection.request { api, s -> FileStationApi.share(api, s, path, password, expires) }

    /** 缩略图字节（缓存在内存里） */
    suspend fun thumbnail(path: String, size: String = "small"): ByteArray? {
        val key = "$size:$path"
        thumbs.get(key)?.let { return it }
        return thumbLimiter.withPermit {
            runCatching {
                connection.request { api, s ->
                    ByteArrayOutputStream().also { FileStationApi.thumbnail(api, s, path, size, it) }.toByteArray()
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.also { thumbs.put(key, it) }
        }
    }

    /** 读取文本文件的开头部分用于预览 */
    suspend fun readText(path: String, maxBytes: Long = 256L * 1024): String = connection.request { api, s ->
        val out = ByteArrayOutputStream()
        FileStationApi.download(api, s, path, out, maxBytes)
        out.toByteArray().toString(Charsets.UTF_8)
    }

    companion object {
        const val ROOT = "/"
        private const val THUMB_CACHE_BYTES = 16 * 1024 * 1024

        fun parent(path: String): String = path.substringBeforeLast('/', "").ifBlank { ROOT }

        fun join(folder: String, name: String): String = if (folder == ROOT) "/$name" else "$folder/$name"
    }
}
