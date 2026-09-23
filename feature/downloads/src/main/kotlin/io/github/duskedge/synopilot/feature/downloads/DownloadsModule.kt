package io.github.duskedge.synopilot.feature.downloads

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val downloadsModule = module {
    viewModel { DownloadsViewModel(get(), get()) }
    viewModel { DownloadersViewModel(get(), get(), get()) }
}
