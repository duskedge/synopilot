plugins {
    id("synopilot.android.feature")
}

android {
    namespace = "io.github.duskedge.synopilot.feature.onboarding"
}

dependencies {
    implementation(libs.ktor.client.core)
}
