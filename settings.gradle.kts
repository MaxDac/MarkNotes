pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val syncSource = providers.gradleProperty("marknotes.sync.fromSource").orNull
        ?: "libre/marknotes-sync".takeIf { file("libre/gradle/marknotes-sync-source.properties").isFile }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MSAL (OneDrive sign-in) depends on Microsoft's display-mask library, only published here.
        maven("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1") {
            content { includeGroup("com.microsoft.device.display") }
        }
        // A development source pin must resolve the artifacts built from that commit, never an unpublished release.
        ivy {
            url = if (syncSource != null) {
                file(syncSource).resolve("bindings/kotlin/build/libs").toURI()
            } else {
                uri("https://github.com/MaxDac/marknotes-sync/releases/download")
            }
            patternLayout {
                artifact(if (syncSource != null) "[module]-[revision].[ext]" else "v[revision]/[module]-[revision].[ext]")
            }
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
