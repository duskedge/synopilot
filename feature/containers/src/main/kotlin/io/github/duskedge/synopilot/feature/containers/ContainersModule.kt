package io.github.duskedge.synopilot.feature.containers

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val containersModule = module {
    viewModel { ContainersViewModel(get(), get()) }
}
