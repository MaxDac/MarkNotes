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

rootProject.name = "MarkNotes"

include(":core")
include(":app")

// The Proton Drive SDK bridge is opt-in because the SDK is experimental and ships
// native (NativeAOT) libraries. Enable with: gradle assembleDebug -Pmarknotes.protonSdk=true
val protonSdkEnabled = providers.gradleProperty("marknotes.protonSdk").orNull == "true"
if (protonSdkEnabled) {
    include(":proton-drive")
}
