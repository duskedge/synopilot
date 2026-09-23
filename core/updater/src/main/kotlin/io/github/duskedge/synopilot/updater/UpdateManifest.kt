package io.github.duskedge.synopilot.updater

import kotlinx.serialization.Serializable

/** GitHub Release 附件里的 update.json，由 scripts/make-update-json.sh 生成。 */
@Serializable
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    /** 当前版本低于它时必须更新（不能跳过）。 */
    val minSupportedVersionCode: Long = 0,
    val publishedAt: String? = null,
    val apk: Apk,
    val notes: String = "",
) {
    @Serializable
    data class Apk(
        val url: String,
        val size: Long,
        val sha256: String,
    )

    /** 基本合法性检查，防止格式错误的清单进入下载流程。 */
    fun validate() {
        if (versionCode <= 0) throw UpdateException("更新信息有误：versionCode 无效")
        if (!apk.url.startsWith("https://")) throw UpdateException("更新信息有误：下载地址必须是 https")
        if (apk.size <= 0) throw UpdateException("更新信息有误：文件大小无效")
        if (!SHA256_HEX.matches(apk.sha256)) throw UpdateException("更新信息有误：SHA-256 格式不对")
    }
}

// 不能放进 @Serializable 类的 private companion：序列化插件会在 companion 上生成 serializer()
private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

enum class UpdateChannel { Stable, Beta }

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

const val MANIFEST_FILE_NAME = "update.json"
