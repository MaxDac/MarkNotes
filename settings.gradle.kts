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
        // marknotes-sync (the sync core shared with the desktop and web apps) is published as GitHub release assets.
        ivy("https://github.com/MaxDac/marknotes-sync/releases/download") {
            patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("io.github.maxdac.marknotes") }
        }
    }
}

rootProject.name = "MarkNotes"

include(":core")
include(":app")
include(":proton-drive")
