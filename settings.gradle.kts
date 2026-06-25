// [FIX#17] dependencyResolution → dependencyResolutionManagement，修复 Gradle 构建 API 错误
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ZeroTierBrowser"
include(":app")
