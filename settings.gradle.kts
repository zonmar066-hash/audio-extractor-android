pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // FFmpegKit 已停更，Maven Central 不提供 AAR
        // 方案：用 JitPack 从 GitHub 源码编译
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "AudioExtractor"
include(":app")
