package io.github.duskedge.synopilot.updater

import java.io.InputStream
import java.security.MessageDigest

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val manifest: UpdateManifest, val forced: Boolean) : UpdateCheck
}

object UpdatePolicy {
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun evaluate(currentVersionCode: Long, manifest: UpdateManifest): UpdateCheck =
        if (manifest.versionCode <= currentVersionCode) {
            UpdateCheck.UpToDate
        } else {
            UpdateCheck.Available(manifest, forced = currentVersionCode < manifest.minSupportedVersionCode)
        }

    /** 自动检查（打开 App、后台任务）时是否提醒用户：跳过的版本不再提醒，强制更新例外。 */
    fun shouldPrompt(check: UpdateCheck, skippedVersionCode: Long): Boolean =
        check is UpdateCheck.Available && (check.forced || check.manifest.versionCode != skippedVersionCode)

    fun isAutoCheckDue(lastCheckAt: Long, now: Long): Boolean =
        lastCheckAt <= 0 || now - lastCheckAt >= AUTO_CHECK_INTERVAL_MS || now < lastCheckAt
}

/**
 * 镜像地址前缀：GitHub 在国内下载慢时，用户可以填一个前缀，
 * 例如 https://mirror.example.com/ → https://mirror.example.com/https://github.com/…
 * 只改写 GitHub 的下载地址；不管从哪里下载，安装前都会校验 SHA-256 和签名证书。
 */
object MirrorUrl {
    private val GITHUB_HOSTS = listOf("https://github.com/", "https://objects.githubusercontent.com/")

    fun apply(url: String, prefix: String?): String {
        val p = prefix?.trim().orEmpty()
        if (p.isEmpty() || GITHUB_HOSTS.none { url.startsWith(it) }) return url
        return p.trimEnd('/') + "/" + url
    }

    fun isValidPrefix(prefix: String): Boolean {
        val p = prefix.trim()
        return p.isEmpty() || (p.startsWith("https://") && p.length > "https://".length && ' ' !in p)
    }
}

object Sha256 {
    fun hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().toHex()
    }

    fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
