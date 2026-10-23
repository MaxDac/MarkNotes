package io.github.maxdac.marknotes.core.sync

/**
 * Location of the drive's sync manifest. Its format (parsing, serialising, rebuilding after a sync) lives in the
 * shared sync core: `io.github.maxdac.marknotes.sync.parseManifest`, `serializeManifest` and `buildManifest`.
 */
object SyncManifest {
    const val DIRECTORY = ".marknotes"
    const val PATH = "$DIRECTORY/manifest.json"
}
