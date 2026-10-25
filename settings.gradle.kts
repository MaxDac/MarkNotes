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
    // gradle/libs.versions.toml only holds the Pro-only dependencies (`pro`); `libs` is the libre app's catalog, so
    // the code shared with marknotes-android builds against the same versions in both repositories.
    defaultLibrariesExtensionName = "pro"
    versionCatalogs {
        create("libs") { from(files("libre/gradle/libs.versions.toml")) }
    }
}

// marknotes-sync is shared with the libre app: its helper routes the sync group exclusively to the pinned source
// build or the GitHub release, checks source pins/receipts, and Gradle verifies the release bytes against this
// root's gradle/verification-metadata.xml (see libre/docs/dependencies.md, "Pro root integration").
extra["marknotesSyncOwner"] = file("libre")
apply(from = file("libre/gradle/marknotes-sync.settings.gradle"))

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
