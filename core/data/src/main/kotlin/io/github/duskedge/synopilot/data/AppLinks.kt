package io.github.duskedge.synopilot.data

import android.content.Context
import android.content.Intent

/** 从通知、小部件、磁贴打开 App 的指定页面 */
object AppLinks {
    const val EXTRA_OPEN = "io.github.duskedge.synopilot.OPEN"
    const val ALERTS = "alerts"
    const val DOWNLOADS = "downloads"
    const val DASHBOARD = "dashboard"

    fun open(context: Context, target: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra(EXTRA_OPEN, target)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
