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
rootProject.name = "Farbwerk"

// Das Android-Modul wird nur eingebunden, wenn ein Android-SDK verfügbar ist
// (local.properties mit sdk.dir oder ANDROID_HOME/ANDROID_SDK_ROOT). So kann die
// Windows-Desktop-Version (z. B. in GitHub Actions) ohne Android-SDK gebaut werden.
val hasAndroidSdk = file("local.properties").let { props ->
    props.exists() && props.readText().lines().any { it.trim().startsWith("sdk.dir") }
} || !System.getenv("ANDROID_HOME").isNullOrBlank() || !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank()
val skipAndroid = System.getenv("FARBWERK_SKIP_ANDROID") == "true"

if (hasAndroidSdk && !skipAndroid) {
    include(":app")
}
include(":desktop")
