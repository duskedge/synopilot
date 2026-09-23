pluginManagement {
    val useMirror = File(settingsDir, "../local.properties").let { f ->
        f.exists() && f.readLines().any { it.trim() == "mirror=aliyun" }
    }
    repositories {
        if (useMirror) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
        } else {
            gradlePluginPortal()
            mavenCentral()
        }
    }
}

dependencyResolutionManagement {
    val useMirror = File(settingsDir, "../local.properties").let { f ->
        f.exists() && f.readLines().any { it.trim() == "mirror=aliyun" }
    }
    repositories {
        if (useMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        } else {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
