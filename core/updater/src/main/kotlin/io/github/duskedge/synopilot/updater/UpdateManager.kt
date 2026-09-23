package io.github.duskedge.synopilot.updater

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class UpdaterConfig(
    /** GitHub 仓库，形如 owner/repo */
    val repo: String,
    val currentVersionCode: Long,
    val currentVersionName: String,
    /** 是否启用自动更新（以后上架商店的构建关闭） */
    val enabled: Boolean,
    /** 是否允许安装（debug 包名不同、签名不同，只检查不安装） */
    val installAllowed: Boolean,
    /** 点击更新通知时打开的界面 */
    val launchActivity: Class<out Activity>,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val manifest: UpdateManifest, val forced: Boolean) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val forced: Boolean, val downloaded: Long, val total: Long) : UpdateState {
        val fraction: Float get() = if (total <= 0) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
    }
    data class ReadyToInstall(val manifest: UpdateManifest, val forced: Boolean, val file: File) : UpdateState
    data class Installing(val manifest: UpdateManifest, val forced: Boolean) : UpdateState
    data class Failed(val message: String, val manifest: UpdateManifest? = null, val forced: Boolean = false) : UpdateState
}

/**
 * 串起「检查 → 下载 → 校验 → 安装」整个流程，给界面提供状态。
 * 应用级单例，生命周期跟随进程。
 */
class UpdateManager(
    val config: UpdaterConfig,
    private val client: UpdateClient,
    private val downloader: ApkDownloader,
    private val verifier: ApkVerifier,
    private val installer: ApkInstaller,
    private val preferences: UpdatePreferences,
    private val notifier: UpdateNotifier,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 是否显示更新对话框 */
    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    val settings = preferences.settings

    private var job: Job? = null

    init {
        scope.launch {
            ApkInstaller.events.collect { event ->
                val current = _state.value as? UpdateState.Installing ?: return@collect
                when (event) {
                    InstallEvent.WaitingForUser -> Unit
                    InstallEvent.Cancelled -> _state.value = UpdateState.Available(current.manifest, current.forced)
                    is InstallEvent.Failed -> _state.value = UpdateState.Failed(event.message, current.manifest, current.forced)
                }
            }
        }
    }

    /** 打开 App 时调用：距离上次检查超过 24 小时才检查，在后台静默进行。 */
    fun checkOnLaunch() {
        if (!config.enabled) return
        scope.launch {
            val s = preferences.settings.first()
            if (UpdatePolicy.isAutoCheckDue(s.lastCheckAt, clock())) check(manual = false)
        }
    }

    /** 「设置 → 检查更新」 */
    fun checkNow() {
        if (!config.enabled) return
        check(manual = true)
    }

    private fun check(manual: Boolean) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            val s = preferences.settings.first()
            try {
                val manifest = client.fetchLatest(s.channel, s.mirrorPrefix)
                preferences.setLastCheckAt(clock())
                when (val result = UpdatePolicy.evaluate(config.currentVersionCode, manifest)) {
                    UpdateCheck.UpToDate -> _state.value = UpdateState.UpToDate(clock())
                    is UpdateCheck.Available -> {
                        _state.value = UpdateState.Available(result.manifest, result.forced)
                        if (manual || UpdatePolicy.shouldPrompt(result, s.skippedVersionCode)) _dialogVisible.value = true
                    }
                }
            } catch (e: UpdateException) {
                _state.value = if (manual) UpdateState.Failed(e.message ?: "检查更新失败") else UpdateState.Idle
            }
        }
    }

    /** 后台任务调用：有新版本且没通知过、没被跳过时发一条通知。 */
    suspend fun backgroundCheck() {
        if (!config.enabled) return
        val s = preferences.settings.first()
        val manifest = client.fetchLatest(s.channel, s.mirrorPrefix)
        preferences.setLastCheckAt(clock())
        val result = UpdatePolicy.evaluate(config.currentVersionCode, manifest)
        if (result is UpdateCheck.Available &&
            UpdatePolicy.shouldPrompt(result, s.skippedVersionCode) &&
            manifest.versionCode != s.notifiedVersionCode
        ) {
            notifier.notifyAvailable(manifest, result.forced)
            preferences.setNotified(manifest.versionCode)
        }
    }

    fun showDialog() {
        if (_state.value is UpdateState.Available || _state.value is UpdateState.Downloading ||
            _state.value is UpdateState.ReadyToInstall
        ) {
            _dialogVisible.value = true
        } else {
            checkNow()
        }
    }

    /** 稍后：强制更新时不能关闭 */
    fun dismissDialog() {
        if (isForced()) return
        _dialogVisible.value = false
    }

    fun skipThisVersion() {
        val available = _state.value as? UpdateState.Available ?: return
        if (available.forced) return
        scope.launch { preferences.setSkipped(available.manifest.versionCode) }
        _dialogVisible.value = false
    }

    fun startDownload() {
        val (manifest, forced) = when (val s = _state.value) {
            is UpdateState.Available -> s.manifest to s.forced
            is UpdateState.Failed -> (s.manifest ?: return) to s.forced
            else -> return
        }
        if (!config.installAllowed) {
            _state.value = UpdateState.Failed("调试版不能直接安装正式版，请到 GitHub Releases 下载安装", manifest, forced)
            return
        }
        job?.cancel()
        job = scope.launch {
            val mirror = preferences.settings.first().mirrorPrefix
            try {
                downloader.download(manifest, mirror).collect { p ->
                    when (p) {
                        is DownloadProgress.Running -> _state.value = UpdateState.Downloading(manifest, forced, p.downloaded, p.total)
                        is DownloadProgress.Finished -> {
                            _state.value = when (val v = verifier.verify(p.file, manifest)) {
                                VerifyResult.Ok -> UpdateState.ReadyToInstall(manifest, forced, p.file)
                                is VerifyResult.Failed -> {
                                    p.file.delete()
                                    UpdateState.Failed(v.reason, manifest, forced)
                                }
                            }
                        }
                    }
                }
            } catch (e: UpdateException) {
                _state.value = UpdateState.Failed(e.message ?: "下载失败", manifest, forced)
            } catch (e: java.io.IOException) {
                _state.value = UpdateState.Failed("下载中断，可以点「重试」继续", manifest, forced)
            }
        }
    }

    fun cancelDownload() {
        val s = _state.value as? UpdateState.Downloading ?: return
        job?.cancel()
        _state.value = UpdateState.Available(s.manifest, s.forced)
    }

    /** 是否需要先去系统设置里允许「安装未知应用」 */
    fun needsInstallPermission(): Boolean = !installer.canInstall()

    fun installPermissionIntent() = installer.permissionSettingsIntent()

    fun install() {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        if (!installer.canInstall()) return
        _state.value = UpdateState.Installing(ready.manifest, ready.forced)
        scope.launch {
            try {
                installer.install(ready.file)
            } catch (e: UpdateException) {
                _state.value = UpdateState.Failed(e.message ?: "安装失败", ready.manifest, ready.forced)
            }
        }
    }

    suspend fun setBetaChannel(enabled: Boolean) = preferences.setBetaChannel(enabled)

    suspend fun setMirrorPrefix(prefix: String) = preferences.setMirrorPrefix(prefix)

    private fun isForced(): Boolean = when (val s = _state.value) {
        is UpdateState.Available -> s.forced
        is UpdateState.Downloading -> s.forced
        is UpdateState.ReadyToInstall -> s.forced
        is UpdateState.Installing -> s.forced
        is UpdateState.Failed -> s.forced
        else -> false
    }
}
