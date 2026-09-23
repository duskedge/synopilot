package io.github.duskedge.synopilot.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

sealed interface VerifyResult {
    data object Ok : VerifyResult
    data class Failed(val reason: String) : VerifyResult
}

/**
 * 安装前的三重校验：文件大小、SHA-256 与 update.json 一致；
 * 包名相同、签名证书与当前安装的应用一致。任何一项不通过都拒绝安装。
 * （系统安装器也会校验签名，这里提前检查是为了给出明确的提示。）
 */
class ApkVerifier(private val context: Context) {

    fun verify(file: File, manifest: UpdateManifest): VerifyResult {
        if (file.length() != manifest.apk.size) {
            return VerifyResult.Failed("安装包大小不对，可能下载不完整")
        }
        val sha = file.inputStream().use(Sha256::hex)
        if (!sha.equals(manifest.apk.sha256, ignoreCase = true)) {
            return VerifyResult.Failed("安装包校验失败（SHA-256 不一致），可能被篡改或损坏")
        }

        val pm = context.packageManager
        val archive = archiveInfo(pm, file) ?: return VerifyResult.Failed("无法解析安装包")
        if (archive.packageName != context.packageName) {
            return VerifyResult.Failed("安装包不是 SynoPilot（${archive.packageName}）")
        }
        val installed = installedInfo(pm)
        val archiveSigners = signerDigests(archive)
        val installedSigners = signerDigests(installed)
        if (archiveSigners.isEmpty() || archiveSigners.intersect(installedSigners).isEmpty()) {
            return VerifyResult.Failed("安装包的签名和当前应用不一致，已拒绝安装")
        }
        return VerifyResult.Ok
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? {
        val flags = signingFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo {
        val flags = signingFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(context.packageName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { Sha256.hex(it.toByteArray()) }.toSet()
    }
}
