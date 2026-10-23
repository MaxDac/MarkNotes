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
    // gradle/libs.versions.toml only holds the Pro-only dependencies (`pro`); `libs` is the libre app's catalog, so
    // the code shared with marknotes-android builds against the same versions in both repositories.
    defaultLibrariesExtensionName = "pro"
    versionCatalogs {
        create("libs") { from(files("libre/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "MarkNotes"

// Shared with the F-Droid app: the libre/ submodule is MaxDac/marknotes-android.
include(":core")
include(":app-shared")
project(":core").projectDir = file("libre/core")
project(":app-shared").projectDir = file("libre/app-shared")

// Pro-only modules.
include(":pro-core")
include(":proton-drive")
include(":app")
