package io.github.duskedge.synopilot.feature.dashboard

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val dashboardModule = module {
    viewModel { DashboardViewModel(get(), get(), get()) }
}
