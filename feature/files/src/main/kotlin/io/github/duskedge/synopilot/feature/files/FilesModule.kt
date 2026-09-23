package io.github.duskedge.synopilot.feature.files

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val filesModule = module {
    viewModel { FilesViewModel(get(), get(), get()) }
}
