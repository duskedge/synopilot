package io.github.duskedge.synopilot.feature.settings

import io.github.duskedge.synopilot.data.DSM_HTTP
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val settingsModule = module {
    viewModel { SettingsViewModel(get(), get(), get(DSM_HTTP)) }
    viewModel { ServerAddressViewModel(get(), get()) }
}
