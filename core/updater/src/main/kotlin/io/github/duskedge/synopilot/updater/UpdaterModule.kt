package io.github.duskedge.synopilot.updater

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

private val UPDATER_HTTP = named("updaterHttp")

fun updaterModule(config: UpdaterConfig) = module {
    single { config }
    single(UPDATER_HTTP) {
        HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = true
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
            defaultRequest {
                // GitHub API 要求带 User-Agent
                headers.append(HttpHeaders.UserAgent, "SynoPilot/${config.currentVersionName}")
            }
        }
    }
    single { UpdateClient(get(UPDATER_HTTP), config.repo) }
    single { ApkDownloader(get(UPDATER_HTTP), File(androidContext().cacheDir, "updates")) }
    single { ApkVerifier(androidContext()) }
    single { ApkInstaller(androidContext()) }
    single { UpdatePreferences(androidContext()) }
    single { UpdateNotifier(androidContext(), config) }
    single {
        UpdateManager(
            config = config,
            client = get(),
            downloader = get(),
            verifier = get(),
            installer = get(),
            preferences = get(),
            notifier = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}
