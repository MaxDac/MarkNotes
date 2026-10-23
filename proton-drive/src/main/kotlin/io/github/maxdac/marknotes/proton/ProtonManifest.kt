package io.github.maxdac.marknotes.proton

import io.github.maxdac.marknotes.core.notes.NotePaths
import io.github.maxdac.marknotes.core.sync.SyncManifest
import java.io.IOException

/** A node of the Drive tree as seen by [ProtonManifest]: [revision] is the active revision of a file. */
internal data class ManifestNode(val uid: String, val isFolder: Boolean, val revision: String = "")

/** The Drive operations [ProtonManifest] needs, so the logic can be tested without the SDK. */
internal interface ManifestDrive {
    /** The non-trashed child of [parent] named [name], or null. */
    suspend fun child(parent: String, name: String): ManifestNode?
    suspend fun download(revision: String): ByteArray
    suspend fun createFolder(parent: String, name: String): ManifestNode
    suspend fun uploadNew(parent: String, name: String, bytes: ByteArray)
    suspend fun uploadRevision(file: ManifestNode, bytes: ByteArray)
}

/**
 * Reads and writes the sync manifest at [SyncManifest.PATH] (`.marknotes/manifest.json`) below the synced
 * folder, like the desktop app. The hidden folder is never part of a snapshot.
 */
internal class ProtonManifest(private val drive: ManifestDrive) {
    private val folderName = NotePaths.parent(SyncManifest.PATH)
    private val fileName = NotePaths.name(SyncManifest.PATH)

    suspend fun read(root: String): ByteArray? {
        val folder = drive.child(root, folderName)?.takeIf { it.isFolder } ?: return null
        val file = drive.child(folder.uid, fileName)?.takeIf { !it.isFolder } ?: return null
        return drive.download(file.revision)
    }

    suspend fun write(root: String, bytes: ByteArray) {
        val existing = drive.child(root, folderName)
        if (existing != null && !existing.isFolder) throw IOException("$folderName is a file in Proton Drive")
        val folder = existing ?: drive.createFolder(root, folderName)
        val file = drive.child(folder.uid, fileName)
        when {
            file == null -> drive.uploadNew(folder.uid, fileName, bytes)
            file.isFolder -> throw IOException("${SyncManifest.PATH} is a folder in Proton Drive")
            else -> drive.uploadRevision(file, bytes)
        }
    }
}
