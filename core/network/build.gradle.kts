plugins {
    id("synopilot.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.duskedge.synopilot.network"
}

dependencies {
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
