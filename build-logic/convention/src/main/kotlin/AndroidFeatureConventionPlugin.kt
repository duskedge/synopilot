import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** 功能模块（feature 目录下）：Compose + 设计系统 + 数据层 + Koin + ViewModel。 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("synopilot.android.library.compose")

        dependencies {
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:data"))
            add("implementation", project(":core:network"))
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
            add("implementation", platform(libs.findLibrary("koin-bom").get()))
            add("implementation", libs.findLibrary("koin-androidx-compose").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
