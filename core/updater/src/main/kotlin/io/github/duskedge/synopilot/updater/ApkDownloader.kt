package io.github.duskedge.synopilot.updater

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream

sealed interface DownloadProgress {
    data class Running(val downloaded: Long, val total: Long) : DownloadProgress
    data class Finished(val file: File) : DownloadProgress
}

/**
 * 下载 APK 到应用私有缓存目录，支持断点续传（HTTP Range）。
 * 未完成的文件以 .part 结尾，下载完整后才改名。
 */
class ApkDownloader(
    private val http: HttpClient,
    private val directory: File,
) {
    fun download(manifest: UpdateManifest, mirrorPrefix: String?): Flow<DownloadProgress> = flow {
        directory.mkdirs()
        val total = manifest.apk.size
        val target = File(directory, "SynoPilot-${manifest.versionName}.apk")
        val part = File(directory, target.name + ".part")

        // 清理旧版本残留
        directory.listFiles()?.filter { it != target && it != part }?.forEach { it.delete() }

        if (target.exists() && target.length() == total) {
            emit(DownloadProgress.Finished(target))
            return@flow
        }
        target.delete()

        var existing = if (part.exists()) part.length() else 0L
        if (existing > total) {
            part.delete()
            existing = 0
        }

        http.prepareGet(MirrorUrl.apply(manifest.apk.url, mirrorPrefix)) {
            if (existing > 0) header(HttpHeaders.Range, "bytes=$existing-")
        }.execute { response ->
            val append = when (response.status) {
                HttpStatusCode.PartialContent -> true
                HttpStatusCode.OK -> false // 服务器不支持续传，从头下载
                HttpStatusCode.RequestedRangeNotSatisfiable -> false
                else -> throw UpdateException("下载失败（HTTP ${response.status.value}）")
            }
            if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                part.delete()
                throw UpdateException("下载中断，请重试")
            }
            var downloaded = if (append) existing else 0L
            var lastEmitted = -1L
            emit(DownloadProgress.Running(downloaded, total))

            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            FileOutputStream(part, append).use { out ->
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    downloaded += n
                    if (downloaded > total) throw UpdateException("下载的文件比预期大，已停止")
                    if (downloaded - lastEmitted >= PROGRESS_STEP || downloaded == total) {
                        emit(DownloadProgress.Running(downloaded, total))
                        lastEmitted = downloaded
                    }
                }
            }
        }

        if (part.length() != total) throw UpdateException("下载不完整，请重试")
        if (!part.renameTo(target)) throw UpdateException("保存安装包失败")
        emit(DownloadProgress.Finished(target))
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val PROGRESS_STEP = 256L * 1024
    }
}
