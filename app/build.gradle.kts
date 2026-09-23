plugins {
    id("synopilot.android.application")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.duskedge.synopilot"

    defaultConfig {
        applicationId = "io.github.duskedge.synopilot"
    }

    productFlavors {
        getByName("github") {
            // 自动更新：从这个仓库的 GitHub Releases 检查新版本
            buildConfigField("String", "UPDATE_REPO", "\"duskedge/synopilot\"")
            buildConfigField("boolean", "UPDATE_ENABLED", "true")
        }
    }

    buildTypes {
        getByName("debug") {
            // debug 包名带 .debug 后缀、签名也不同，不能安装 Release 的 APK，只检查不安装
            buildConfigField("boolean", "UPDATE_INSTALL_ALLOWED", "false")
        }
        getByName("release") {
            buildConfigField("boolean", "UPDATE_INSTALL_ALLOWED", "true")
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:updater"))
    implementation(project(":core:data"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:containers"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.biometric)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
}
