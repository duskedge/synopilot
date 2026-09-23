import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** 全工程统一的 SDK / Java 版本。 */
object AndroidConfig {
    /** 编译用的 API 版本：只决定能调用哪些 API，不改变运行时行为。依赖库要求 ≥ 37。 */
    const val COMPILE_SDK = 37

    /** 运行时行为版本：升级会启用新系统的行为变化，需要真机验证后再单独升级。 */
    const val TARGET_SDK = 36
    const val MIN_SDK = 26
    val JAVA = JavaVersion.VERSION_17
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
