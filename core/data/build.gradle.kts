plugins {
    id("synopilot.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.duskedge.synopilot.data"
}

dependencies {
    api(project(":core:network"))
    implementation(project(":core:security"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
