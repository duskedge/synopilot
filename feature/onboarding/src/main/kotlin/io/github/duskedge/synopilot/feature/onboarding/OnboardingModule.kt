package io.github.duskedge.synopilot.feature.onboarding

import io.github.duskedge.synopilot.data.DSM_HTTP
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val onboardingModule = module {
    viewModel { params -> OnboardingViewModel(get(), get(DSM_HTTP), get(), get(), params.getOrNull()) }
}
