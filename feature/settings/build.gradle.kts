plugins {
    id("synopilot.android.feature")
}

android {
    namespace = "io.github.duskedge.synopilot.feature.settings"
}

dependencies {
    implementation(project(":core:updater"))
    implementation(libs.androidx.core.ktx)
}
