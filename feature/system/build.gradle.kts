plugins {
    id("synopilot.android.feature")
}

android {
    namespace = "io.github.duskedge.synopilot.feature.system"
}

dependencies {
    implementation(project(":core:security"))
}
