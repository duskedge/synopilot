package io.github.duskedge.synopilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.duskedge.synopilot.designsystem.SynoPilotTheme
import io.github.duskedge.synopilot.ui.AppRoot
import io.github.duskedge.synopilot.updater.UpdateManager
import io.github.duskedge.synopilot.updater.UpdateNotifier
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val updateManager: UpdateManager by inject()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响使用 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SynoPilotTheme {
                AppRoot()
            }
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
            updateManager.checkOnLaunch()
            requestNotificationPermission()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
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
}
