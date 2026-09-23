package io.github.duskedge.synopilot.updater

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 从 GitHub Releases 获取最新的 update.json。
 * - 正式版：releases/latest/download/update.json（静态跳转地址，不占 API 频率限额；latest 不包含预发布）
 * - 测试版：Releases API 列出最近的版本（含预发布），取最新一个带 update.json 的
 */
class UpdateClient(
    private val http: HttpClient,
    private val repo: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchLatest(channel: UpdateChannel, mirrorPrefix: String?): UpdateManifest {
        val manifestUrl = when (channel) {
            UpdateChannel.Stable -> "https://github.com/$repo/releases/latest/download/$MANIFEST_FILE_NAME"
            UpdateChannel.Beta -> latestManifestUrlIncludingPrerelease()
        }
        val response = try {
            http.get(MirrorUrl.apply(manifestUrl, mirrorPrefix))
        } catch (e: Exception) {
            throw UpdateException("连接 GitHub 失败，请检查网络或设置下载镜像", e)
        }
        when {
            response.status == HttpStatusCode.NotFound -> throw UpdateException("还没有发布${if (channel == UpdateChannel.Stable) "正式版" else "任何版本"}")
            !response.status.isSuccess() -> throw UpdateException("获取更新信息失败（HTTP ${response.status.value}）")
        }
        val manifest = try {
            json.decodeFromString<UpdateManifest>(response.bodyAsText())
        } catch (e: SerializationException) {
            throw UpdateException("更新信息格式不对", e)
        } catch (e: IllegalArgumentException) {
            throw UpdateException("更新信息格式不对", e)
        }
        manifest.validate()
        return manifest
    }

    private suspend fun latestManifestUrlIncludingPrerelease(): String {
        val response = try {
            http.get("https://api.github.com/repos/$repo/releases?per_page=10") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
        } catch (e: Exception) {
            throw UpdateException("连接 GitHub 失败，请检查网络", e)
        }
        if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.TooManyRequests) {
            throw UpdateException("GitHub 接口请求太频繁，请稍后再试")
        }
        if (!response.status.isSuccess()) throw UpdateException("获取版本列表失败（HTTP ${response.status.value}）")

        val releases = try {
            json.decodeFromString<List<GitHubRelease>>(response.bodyAsText())
        } catch (e: SerializationException) {
            throw UpdateException("版本列表格式不对", e)
        }
        // API 按创建时间倒序返回
        return releases
            .firstOrNull { !it.draft && it.assets.any { a -> a.name == MANIFEST_FILE_NAME } }
            ?.assets?.first { it.name == MANIFEST_FILE_NAME }?.downloadUrl
            ?: throw UpdateException("还没有发布任何版本")
    }

    @Serializable
    internal data class GitHubRelease(
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    internal data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
    )
}
