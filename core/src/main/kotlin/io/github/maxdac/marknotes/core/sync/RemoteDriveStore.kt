package io.github.maxdac.marknotes.core.sync

import java.io.IOException

/**
 * A file or folder in the synced remote folder.
 *
 * @param path path relative to the synced remote folder, "/"-separated.
 * @param id stable remote identifier (node uid).
 * @param version identifier of the current content version (active revision uid); empty for folders.
 * @param modifiedMillis the note's local edit time as written by the uploading app (Dropbox `client_modified`,
 *   OneDrive `fileSystemInfo.lastModifiedDateTime`, Proton claimed modification time), falling back to the
 *   server time when the drive has none.
 */
data class RemoteEntry(
    val path: String,
    val id: String,
    val isFolder: Boolean,
    val version: String,
    val modifiedMillis: Long
)

/**
 * Abstraction over the remote drive, scoped to the synced folder.
 * Keeps the Proton Drive SDK contained in a single implementation.
 */
interface RemoteDriveStore {
    /**
     * Returns every folder and file below the synced folder. Implementations should keep a cache and
     * refresh it through change events instead of re-traversing the whole tree on every call.
     *
     * A snapshot is complete or it fails: an entry that can't be read (e.g. a name that can't be decrypted) or an
     * interrupted listing throws instead of leaving entries out, since a missing entry reads as a deletion.
     */
    suspend fun snapshot(): List<RemoteEntry>

    suspend fun download(entry: RemoteEntry): ByteArray

    /**
     * Uploads a new file at [path], creating missing parent folders. [modifiedMillis] is the local edit time,
     * stored as the file's modification time and returned in [RemoteEntry.modifiedMillis].
     */
    suspend fun uploadNew(path: String, bytes: ByteArray, modifiedMillis: Long): RemoteEntry

    /** Uploads new content for an existing file, with [modifiedMillis] as for [uploadNew]. */
    suspend fun uploadRevision(entry: RemoteEntry, bytes: ByteArray, modifiedMillis: Long): RemoteEntry

    /** Creates a folder at [path], creating missing parent folders. Returns the existing one if present. */
    suspend fun createFolder(path: String): RemoteEntry

    /**
     * Moves the entry to the remote trash. Deleting a file is conditional on [RemoteEntry.version]: when the file
     * changed since the snapshot the delete fails with [RemoteChangedException] (the next sync downloads the
     * change). An entry that is already gone is not an error.
     */
    suspend fun delete(entry: RemoteEntry)

    /**
     * Reads the [SyncManifest] file ([SyncManifest.PATH], hidden from [snapshot]); null when missing.
     * Stores that can't access hidden files keep the default and simply don't use a manifest.
     */
    suspend fun readManifest(): ByteArray? = null

    /** Creates or overwrites the [SyncManifest] file. */
    suspend fun writeManifest(bytes: ByteArray) = Unit
}

class RemoteUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A conditional write or delete failed because the remote file changed since the snapshot. */
class RemoteChangedException(path: String) : IOException("$path changed on the drive since the last listing")
