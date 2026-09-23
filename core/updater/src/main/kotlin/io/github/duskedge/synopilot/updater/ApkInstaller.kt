package io.github.duskedge.synopilot.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

sealed interface InstallEvent {
    data object WaitingForUser : InstallEvent
    data object Cancelled : InstallEvent
    data class Failed(val message: String) : InstallEvent
}

/** 用 PackageInstaller Session 安装 APK。安装成功后系统会替换并重启本应用，所以没有「成功」回调。 */
class ApkInstaller(private val context: Context) {

    /** 用户是否已允许本应用「安装未知应用」。 */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 本应用是安装来源时，Android 12+ 可以免确认更新；否则系统仍会弹出确认
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw UpdateException("无法启动安装：${e.message}", e)
        }
    }

    internal companion object {
        const val ACTION_INSTALL_RESULT = "io.github.duskedge.synopilot.updater.INSTALL_RESULT"
        private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<InstallEvent> = _events

        fun emit(event: InstallEvent) {
            _events.tryEmit(event)
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.ACTION_INSTALL_RESULT) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ApkInstaller.emit(InstallEvent.WaitingForUser)
                } else {
                    ApkInstaller.emit(InstallEvent.Failed("系统没有返回安装确认界面"))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // 进程即将被替换
            PackageInstaller.STATUS_FAILURE_ABORTED -> ApkInstaller.emit(InstallEvent.Cancelled)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ApkInstaller.emit(InstallEvent.Failed(installFailureText(status, message)))
            }
        }
    }

    private fun installFailureText(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "和已安装的版本冲突（签名不一致？）"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "这个版本不支持当前设备"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "手机存储空间不足"
        PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被系统阻止"
        else -> "安装失败" + (message?.let { "：$it" } ?: "（$status）")
    }
}
