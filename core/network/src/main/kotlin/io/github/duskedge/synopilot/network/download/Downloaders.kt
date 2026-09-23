package io.github.duskedge.synopilot.network.download

import kotlinx.serialization.Serializable

enum class EngineKind { DownloadStation, QBittorrent, Transmission }

/** 统一的任务状态（和 API_DESIGN 2.3.5 的映射表一致） */
enum class TaskState { Downloading, Queued, Paused, Seeding, Completed, Checking, Error }

@Serializable
data class DownloadTask(
    /** engineId:nativeId，合并列表时保证唯一 */
    val key: String,
    val engineId: String,
    val nativeId: String,
    val name: String,
    val sizeBytes: Long,
    /** 0..1 */
    val progress: Double,
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    /** 剩余秒数，未知为 null */
    val etaSec: Long?,
    val state: TaskState,
    val ratio: Double?,
    val savePath: String,
    val seeds: Int?,
    val peers: Int?,
    val category: String?,
    val error: String?,
)

data class TransferInfo(val downloadSpeed: Long, val uploadSpeed: Long, val downloadLimit: Long?, val uploadLimit: Long?)

data class Category(val name: String, val savePath: String)

data class EngineVersion(val app: String, val api: String)

class DownloaderException(message: String, val authFailed: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

/** 下载器适配器接口：界面只和它打交道。 */
interface Downloader {
    val id: String
    val kind: EngineKind

    suspend fun version(): EngineVersion
    suspend fun list(): List<DownloadTask>
    suspend fun add(urls: List<String>, savePath: String?, category: String?)
    suspend fun pause(ids: List<String>)
    suspend fun resume(ids: List<String>)
    suspend fun remove(ids: List<String>, deleteFiles: Boolean)
    suspend fun transfer(): TransferInfo

    /** 限速，字节/秒；null 表示不限 */
    suspend fun setLimits(download: Long?, upload: Long?)

    /** 支持分类（qBittorrent）时返回分类列表，否则为空 */
    suspend fun categories(): List<Category> = emptyList()
}

object TaskStates {
    fun downloadStation(status: String?): TaskState = when (status?.lowercase()) {
        "downloading", "extracting", "finishing" -> TaskState.Downloading
        "waiting", "filehosting_waiting" -> TaskState.Queued
        "paused" -> TaskState.Paused
        "seeding" -> TaskState.Seeding
        "finished" -> TaskState.Completed
        "hash_checking" -> TaskState.Checking
        "error" -> TaskState.Error
        else -> TaskState.Queued
    }

    fun qbittorrent(state: String?): TaskState = when (state) {
        "downloading", "forcedDL", "metaDL", "stalledDL", "forcedMetaDL" -> TaskState.Downloading
        "queuedDL", "allocating" -> TaskState.Queued
        "pausedDL", "stoppedDL" -> TaskState.Paused
        "uploading", "forcedUP", "stalledUP", "queuedUP" -> TaskState.Seeding
        "pausedUP", "stoppedUP" -> TaskState.Completed
        "checkingDL", "checkingUP", "checkingResumeData", "moving" -> TaskState.Checking
        "error", "missingFiles", "unknown" -> TaskState.Error
        else -> TaskState.Queued
    }

    /** Transmission：0 停止 1 等待校验 2 校验中 3 等待下载 4 下载中 5 等待做种 6 做种中 */
    fun transmission(status: Int?, percentDone: Double, error: Int?): TaskState = when {
        error != null && error != 0 -> TaskState.Error
        status == 4 -> TaskState.Downloading
        status == 3 -> TaskState.Queued
        status == 5 || status == 6 -> TaskState.Seeding
        status == 1 || status == 2 -> TaskState.Checking
        status == 0 -> if (percentDone >= 1.0) TaskState.Completed else TaskState.Paused
        else -> TaskState.Queued
    }
}
