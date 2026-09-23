package io.github.duskedge.synopilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.duskedge.synopilot.data.DeviceRepository
import io.github.duskedge.synopilot.designsystem.SynoPilotTheme
import io.github.duskedge.synopilot.ui.AppRoot
import io.github.duskedge.synopilot.ui.LockScreen
import io.github.duskedge.synopilot.updater.UpdateManager
import io.github.duskedge.synopilot.updater.UpdateNotifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** 用 FragmentActivity 是因为系统的指纹 / 面容验证（BiometricPrompt）需要它。 */
class MainActivity : FragmentActivity() {

    private val updateManager: UpdateManager by inject()
    private val devices: DeviceRepository by inject()

    private var locked by mutableStateOf(false)
    private var lockChecked by mutableStateOf(false)
    private var stoppedAt = 0L

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响使用 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SynoPilotTheme {
                when {
                    !lockChecked -> Unit
                    locked -> LockScreen(onUnlock = ::authenticate)
                    else -> AppRoot()
                }
            }
        }
        lifecycleScope.launch {
            locked = savedInstanceState?.getBoolean(KEY_UNLOCKED) != true && lockEnabled()
            lockChecked = true
            if (locked) authenticate()
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
            updateManager.checkOnLaunch()
            requestNotificationPermission()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, !locked)
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // 在后台超过 1 分钟后回来需要重新验证
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_AFTER_MS) {
            lifecycleScope.launch {
                if (lockEnabled()) {
                    locked = true
                    authenticate()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private suspend fun lockEnabled(): Boolean =
        devices.appSettings.first().biometricLock &&
            BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked = false
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁 SynoPilot")
                .setSubtitle("验证身份后继续")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }

    /** 从「有新版本」通知点进来时，直接打开更新对话框。 */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateNotifier.EXTRA_OPEN_UPDATE, false) == true) {
            updateManager.showDialog()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        const val RELOCK_AFTER_MS = 60_000L
        const val KEY_UNLOCKED = "unlocked"
    }
}
