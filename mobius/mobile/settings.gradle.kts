pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        // 华为 HMS（agconnect 插件 + HMS push SDK）
        maven("https://developer.huawei.com/repo/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        // 华为 HMS push SDK（JPush 华为厂商通道用）
        maven("https://developer.huawei.com/repo/")
    }
}

rootProject.name = "momo-mobile"
include(":shared")
include(":androidApp")
include(":desktopPreview")
include(":desktopApp")
