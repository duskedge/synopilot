pluginManagement {
    includeBuild("build-logic")
    // 本机可在 local.properties 写一行 mirror=aliyun，改用阿里云镜像（国内 dl.google.com 常常连不上）。
    // CI 上没有这个文件，照常使用官方仓库。
    val useMirror = File(settingsDir, "local.properties").let { f ->
        f.exists() && f.readLines().any { it.trim() == "mirror=aliyun" }
    }
    repositories {
        if (useMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        } else {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val useMirror = File(settingsDir, "local.properties").let { f ->
        f.exists() && f.readLines().any { it.trim() == "mirror=aliyun" }
    }
    repositories {
        if (useMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        } else {
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "SynoPilot"

include(":app")
include(":core:designsystem")
include(":core:updater")
include(":core:security")
include(":core:network")
include(":core:data")
include(":feature:onboarding")
include(":feature:dashboard")
include(":feature:containers")
include(":feature:downloads")
include(":feature:files")
include(":feature:system")
include(":feature:settings")
