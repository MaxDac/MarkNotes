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
        // marknotes-sync (the sync core shared with the desktop and web apps) is published as GitHub release assets.
        // F-Droid builds it from source instead (see .fdroid.yml and -Pmarknotes.sync.fromSource).
        ivy("https://github.com/MaxDac/marknotes-sync/releases/download") {
            patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("io.github.maxdac.marknotes") }
        }
    }
}

rootProject.name = "MarkNotes"

include(":core")
// Everything the libre app shares with MarkNotes Pro (MaxDac/MarkNotes), which includes :core and :app-shared.
include(":app-shared")
include(":app")
