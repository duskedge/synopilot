package io.github.duskedge.synopilot.feature.system

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val systemModule = module {
    viewModel { SystemViewModel(get(), get(), get(), get()) }
    viewModel { ScheduleViewModel(get()) }
    viewModel { BackupViewModel(get()) }
    viewModel { MonitorViewModel(get()) }
    viewModel { SecurityViewModel(get()) }
    viewModel { NotificationsViewModel(get(), get(), get()) }
}
