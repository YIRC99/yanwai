pluginManagement {
    repositories {
        // 国内直连 dl.google.com 时并发建连容易被中断（TLS handshake terminated），
        // 所以把阿里云镜像放前面兜底，官方源留在后面。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // Xposed API 与部分模块依赖走 JitPack
        maven("https://jitpack.io")
    }
}

rootProject.name = "Yanwai"
include(":app")
