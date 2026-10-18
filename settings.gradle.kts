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
    }
}

rootProject.name = "shodo-ghost"
include(":protocol")

val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val androidSdkReady = !androidHome.isNullOrBlank() && file(androidHome).isDirectory ||
    file("local.properties").takeIf { it.isFile }?.readLines()?.any { line ->
        line.trim().startsWith("sdk.dir=") && line.substringAfter("sdk.dir=").isNotBlank()
    } == true
if (androidSdkReady) {
    include(":glass")
}
