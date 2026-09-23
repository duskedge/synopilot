plugins {
    id("synopilot.android.library.compose")
}

android {
    namespace = "io.github.duskedge.synopilot.designsystem"
}

dependencies {
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.ui)
    api(libs.compose.material.icons)
}
