import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import java.io.File
import java.util.Properties

/**
 * App 模块约定：
 * - 版本号：从环境变量 VERSION_TAG 或 -PversionTag 读取 Git 标签计算；没有时使用开发版本；
 * - 签名：从环境变量 SIGNING_*（CI）或根目录 keystore.properties（本机，已被 git 忽略）读取；
 *   没有签名信息时，打包 release 直接失败，不会悄悄换成 debug 签名；
 * - 构建变体：distribution 维度下只有 github 一个 flavor，以后上架商店时再加 store。
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val tag = providers.environmentVariable("VERSION_TAG")
            .orElse(providers.gradleProperty("versionTag"))
            .orNull
            ?.takeIf { it.isNotBlank() }
        val version = tag?.let(Versioning::fromTag) ?: Versioning.DEV
        val signing = readSigning(rootDir)

        extensions.configure<ApplicationExtension> {
            compileSdk = AndroidConfig.COMPILE_SDK
            defaultConfig {
                minSdk = AndroidConfig.MIN_SDK
                targetSdk = AndroidConfig.TARGET_SDK
                versionCode = version.code
                versionName = version.name
            }
            compileOptions {
                sourceCompatibility = AndroidConfig.JAVA
                targetCompatibility = AndroidConfig.JAVA
            }
            buildFeatures {
                compose = true
                buildConfig = true
            }

            flavorDimensions += "distribution"
            productFlavors {
                create("github") { dimension = "distribution" }
            }

            if (signing != null) {
                signingConfigs {
                    create("release") {
                        storeFile = signing.storeFile
                        storePassword = signing.storePassword
                        keyAlias = signing.keyAlias
                        keyPassword = signing.keyPassword
                    }
                }
            }

            buildTypes {
                getByName("debug") {
                    applicationIdSuffix = ".debug"
                    versionNameSuffix = "-debug"
                }
                getByName("release") {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    if (signing != null) signingConfig = signingConfigs.getByName("release")
                }
            }
        }

        // 没有签名信息时，任何 release 打包都直接失败
        val hasSigning = signing != null
        val checkSigning = tasks.register("checkReleaseSigning") {
            group = "verification"
            description = "确认 release 签名信息已配置"
            doLast {
                if (!hasSigning) {
                    throw GradleException(
                        "没有找到 release 签名信息。CI 上请配置 SIGNING_* 这几个 Secrets；" +
                            "本机请在项目根目录创建 keystore.properties（storeFile / storePassword / keyAlias / keyPassword）。",
                    )
                }
            }
        }
        tasks.configureEach {
            if (name.startsWith("package") && name.endsWith("Release")) dependsOn(checkSigning)
        }

        // 给发布脚本用：./gradlew -q :app:printVersionCode -PversionTag=v1.2.0
        val code = version.code
        tasks.register("printVersionCode") {
            group = "help"
            description = "打印当前标签对应的 versionCode"
            doLast { println(code) }
        }

        dependencies {
            add("testImplementation", libs.findLibrary("junit").get())
        }
    }

    private data class Signing(
        val storeFile: File,
        val storePassword: String,
        val keyAlias: String,
        val keyPassword: String,
    )

    private fun Project.readSigning(rootDir: File): Signing? {
        fun env(name: String) = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

        val envStore = env("SIGNING_STORE_FILE")
        if (envStore != null) {
            return Signing(
                storeFile = File(envStore),
                storePassword = env("SIGNING_STORE_PASSWORD") ?: error("缺少 SIGNING_STORE_PASSWORD"),
                keyAlias = env("SIGNING_KEY_ALIAS") ?: error("缺少 SIGNING_KEY_ALIAS"),
                keyPassword = env("SIGNING_KEY_PASSWORD") ?: error("缺少 SIGNING_KEY_PASSWORD"),
            )
        }

        val local = File(rootDir, "keystore.properties")
        if (!local.exists()) return null
        val props = Properties().apply { local.inputStream().use(::load) }
        fun prop(name: String) = props.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: error("keystore.properties 缺少 $name")
        return Signing(
            storeFile = File(prop("storeFile").replaceFirst("~", System.getProperty("user.home"))),
            storePassword = prop("storePassword"),
            keyAlias = prop("keyAlias"),
            keyPassword = prop("keyPassword"),
        )
    }
}
