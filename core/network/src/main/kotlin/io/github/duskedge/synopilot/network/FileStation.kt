package io.github.duskedge.synopilot.network

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.OutputStream
import java.time.LocalDate

data class RemoteFile(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    /** 修改时间，秒 */
    val modified: Long,
    /** 共享文件夹在磁盘上的真实路径，如 /volume1/video */
    val realPath: String? = null,
) {
    val extension: String get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
}

data class FileList(val files: List<RemoteFile>, val total: Int)

data class ShareLink(val id: String, val url: String, val qrCode: String?, val expires: String?, val hasPassword: Boolean)

/** File Station 接口。路径都是 DSM 里的共享路径，如 /video/Movies。 */
object FileStationApi {
    const val LIST = "SYNO.FileStation.List"
    const val SEARCH = "SYNO.FileStation.Search"
    const val CREATE_FOLDER = "SYNO.FileStation.CreateFolder"
    const val RENAME = "SYNO.FileStation.Rename"
    const val DELETE = "SYNO.FileStation.Delete"
    const val DOWNLOAD = "SYNO.FileStation.Download"
    const val THUMB = "SYNO.FileStation.Thumb"
    const val SHARING = "SYNO.FileStation.Sharing"

    private val ADDITIONAL = JsonArray(listOf("real_path", "size", "time", "type").map(::JsonPrimitive)).toString()

    private fun jsonArray(vararg values: String) = JsonArray(values.map(::JsonPrimitive)).toString()

    suspend fun shares(api: DsmApi, session: DsmSession): FileList {
        val data = api.call(LIST, "list_share", 2, mapOf("additional" to ADDITIONAL, "sort_by" to "name", "offset" to "0", "limit" to "1000"), session)
        return FileStationParsers.list(data, "shares")
    }

    suspend fun list(api: DsmApi, session: DsmSession, folder: String, offset: Int = 0, limit: Int = 1000): FileList {
        val data = api.call(
            LIST, "list", 2,
            mapOf(
                "folder_path" to folder,
                "additional" to ADDITIONAL,
                "sort_by" to "name",
                "sort_direction" to "asc",
                "offset" to offset.toString(),
                "limit" to limit.toString(),
            ),
            session,
        )
        return FileStationParsers.list(data, "files")
    }

    /** 在 [folders] 下递归搜索文件名，最多等 [timeoutMillis]，返回已经找到的结果。 */
    suspend fun search(api: DsmApi, session: DsmSession, folders: List<String>, pattern: String, timeoutMillis: Long = 15_000): List<RemoteFile> {
        val start = api.call(SEARCH, "start", 2, mapOf("folder_path" to jsonArray(*folders.toTypedArray()), "pattern" to pattern, "recursive" to "true"), session)
        val taskId = start.obj().str("taskid") ?: throw DsmException(0, SEARCH, "搜索没有开始")
        try {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (true) {
                delay(500)
                val data = api.call(SEARCH, "list", 2, mapOf("taskid" to taskId, "offset" to "0", "limit" to "500", "additional" to ADDITIONAL), session)
                val finished = data.obj().bool("finished") == true
                if (finished || System.currentTimeMillis() > deadline) return FileStationParsers.list(data, "files").files
            }
        } finally {
            runCatching { api.call(SEARCH, "clean", 2, mapOf("taskid" to taskId), session) }
        }
    }

    suspend fun createFolder(api: DsmApi, session: DsmSession, parent: String, name: String) {
        api.call(CREATE_FOLDER, "create", 2, mapOf("folder_path" to parent, "name" to name, "force_parent" to "false"), session)
    }

    suspend fun rename(api: DsmApi, session: DsmSession, path: String, newName: String) {
        api.call(RENAME, "rename", 2, mapOf("path" to path, "name" to newName), session)
    }

    /** 删除（等待完成）。大目录可能比较慢，给足超时。 */
    suspend fun delete(api: DsmApi, session: DsmSession, paths: List<String>) {
        api.call(DELETE, "delete", 2, mapOf("path" to jsonArray(*paths.toTypedArray()), "recursive" to "true"), session, timeoutMillis = 5 * 60_000)
    }

    suspend fun download(
        api: DsmApi,
        session: DsmSession,
        path: String,
        sink: OutputStream,
        maxBytes: Long? = null,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): Long = api.download(DOWNLOAD, "download", 2, mapOf("path" to path, "mode" to "download"), session, sink, maxBytes, onProgress)

    /** 缩略图：small 120、medium 320、large 640 像素左右 */
    suspend fun thumbnail(api: DsmApi, session: DsmSession, path: String, size: String, sink: OutputStream): Long =
        api.download(THUMB, "get", 2, mapOf("path" to path, "size" to size), session, sink, maxBytes = 8L * 1024 * 1024)

    /** 创建分享链接。[expires] 为 null 表示永久有效。 */
    suspend fun share(api: DsmApi, session: DsmSession, path: String, password: String?, expires: LocalDate?): ShareLink {
        val params = buildMap {
            put("path", path)
            if (!password.isNullOrBlank()) put("password", password)
            if (expires != null) put("date_expired", expires.toString())
        }
        val link = FileStationParsers.links(api.call(SHARING, "create", 3, params, session)).firstOrNull()
            ?: throw DsmException(0, SHARING, "没有生成分享链接")
        return link.copy(hasPassword = !password.isNullOrBlank(), expires = expires?.toString())
    }
}

object FileStationParsers {
    fun list(data: JsonElement, key: String): FileList {
        val o = data.obj()
        val files = o.array(key).map { f ->
            val add = f.child("additional")
            RemoteFile(
                name = f.str("name").orEmpty(),
                path = f.str("path").orEmpty(),
                isDir = f.bool("isdir") == true,
                size = add.long("size") ?: 0,
                modified = add.child("time").long("mtime") ?: 0,
                realPath = add.str("real_path"),
            )
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return FileList(files, o.int("total") ?: files.size)
    }

    fun links(data: JsonElement): List<ShareLink> = data.obj().array("links").map {
        ShareLink(
            id = it.str("id").orEmpty(),
            url = it.str("url").orEmpty(),
            qrCode = it.str("qrcode"),
            expires = it.str("date_expired")?.takeIf { e -> e.isNotBlank() },
            hasPassword = false,
        )
    }
}
