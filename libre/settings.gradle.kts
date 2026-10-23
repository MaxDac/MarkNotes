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
        // -Pmarknotes.sync.fromSource=<marknotes-sync checkout> uses the artifacts built from source in that checkout
        // instead (scripts/build-sync-from-source.sh), which is how F-Droid and the release workflow build the app.
        val syncSource = providers.gradleProperty("marknotes.sync.fromSource").orNull
        if (syncSource != null) {
            ivy(file(syncSource).resolve("bindings/kotlin/build/libs")) {
                patternLayout { artifact("[module]-[revision].[ext]") }
                metadataSources { artifact() }
                content { includeGroup("io.github.maxdac.marknotes") }
            }
        } else {
            ivy("https://github.com/MaxDac/marknotes-sync/releases/download") {
                patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
                metadataSources { artifact() }
                content { includeGroup("io.github.maxdac.marknotes") }
            }
        }
    }
}

rootProject.name = "MarkNotes"

include(":core")
// Everything the libre app shares with MarkNotes Pro (MaxDac/MarkNotes), which includes :core and :app-shared.
include(":app-shared")
include(":app")
