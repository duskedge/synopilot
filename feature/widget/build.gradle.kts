plugins {
    id("synopilot.android.feature")
}

android {
    namespace = "io.github.duskedge.synopilot.feature.widget"
}

dependencies {
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.core.ktx)
}
