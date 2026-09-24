plugins {
    id("synopilot.android.library")
}

android {
    namespace = "io.github.duskedge.synopilot.security"
}

dependencies {
    api(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)
}
