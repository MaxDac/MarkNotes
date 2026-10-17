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
        // MSAL (OneDrive sign-in) depends on Microsoft's display-mask library, only published here.
        maven("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1") {
            content { includeGroup("com.microsoft.device.display") }
        }
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
