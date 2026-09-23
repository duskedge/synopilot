package io.github.duskedge.synopilot

import android.app.Application
import io.github.duskedge.synopilot.data.dataModule
import io.github.duskedge.synopilot.feature.dashboard.dashboardModule
import io.github.duskedge.synopilot.feature.onboarding.onboardingModule
import io.github.duskedge.synopilot.feature.settings.settingsModule
import io.github.duskedge.synopilot.updater.UpdateCheckWorker
import io.github.duskedge.synopilot.updater.UpdateNotifier
import io.github.duskedge.synopilot.updater.UpdaterConfig
import io.github.duskedge.synopilot.updater.updaterModule
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class SynoPilotApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val updaterConfig = UpdaterConfig(
            repo = BuildConfig.UPDATE_REPO,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            currentVersionName = BuildConfig.VERSION_NAME,
            enabled = BuildConfig.UPDATE_ENABLED,
            installAllowed = BuildConfig.UPDATE_INSTALL_ALLOWED,
            launchActivity = MainActivity::class.java,
        )
        startKoin {
            androidContext(this@SynoPilotApplication)
            modules(
                updaterModule(updaterConfig),
                dataModule(userAgent = "SynoPilot/${BuildConfig.VERSION_NAME}"),
                onboardingModule,
                dashboardModule,
                settingsModule,
            )
        }
        get<UpdateNotifier>().createChannel()
        if (updaterConfig.enabled) UpdateCheckWorker.schedule(this)
    }
}
