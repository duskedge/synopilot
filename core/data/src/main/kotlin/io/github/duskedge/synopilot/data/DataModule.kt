package io.github.duskedge.synopilot.data

import io.github.duskedge.synopilot.network.DsmHttp
import io.github.duskedge.synopilot.network.LanDiscovery
import io.github.duskedge.synopilot.security.KeystoreSecretBox
import io.github.duskedge.synopilot.security.SecretBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

val DSM_HTTP = named("dsmHttp")
private val APP_SCOPE = named("appScope")

fun dataModule(userAgent: String) = module {
    single(APP_SCOPE) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<SecretBox> { KeystoreSecretBox() }
    single { DeviceRepository(androidContext(), get()).also { it.startPinSync(get(APP_SCOPE)) } }
    single(DSM_HTTP) { DsmHttp.create(get<DeviceRepository>().trustedPins, userAgent) }
    single { ConnectionManager(get(), get(DSM_HTTP), get(APP_SCOPE)).also { it.start() } }
    single { DashboardRepository(get(), get(), File(androidContext().cacheDir, "dashboard")) }
    single { ContainersRepository(get()) }
    single { DownloadsRepository(get(), get(), get(DSM_HTTP)) }
    single { LanDiscovery(androidContext(), get(DSM_HTTP)) }
}
