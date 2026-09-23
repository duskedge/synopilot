import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            compileSdk = AndroidConfig.COMPILE_SDK
            defaultConfig {
                minSdk = AndroidConfig.MIN_SDK
                consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = AndroidConfig.JAVA
                targetCompatibility = AndroidConfig.JAVA
            }
        }

        dependencies {
            add("testImplementation", libs.findLibrary("junit").get())
        }
    }
}
