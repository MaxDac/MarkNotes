package io.github.maxdac.marknotes.core.sync

/**
 * A file or folder in the synced remote folder.
 *
 * @param path path relative to the synced remote folder, "/"-separated.
 * @param id stable remote identifier (node uid).
 * @param version identifier of the current content version (active revision uid); empty for folders.
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
     */
    suspend fun snapshot(): List<RemoteEntry>

    suspend fun download(entry: RemoteEntry): ByteArray

    /** Uploads a new file at [path], creating missing parent folders. */
    suspend fun uploadNew(path: String, bytes: ByteArray): RemoteEntry

    /** Uploads new content for an existing file. */
    suspend fun uploadRevision(entry: RemoteEntry, bytes: ByteArray): RemoteEntry

    /** Creates a folder at [path], creating missing parent folders. Returns the existing one if present. */
    suspend fun createFolder(path: String): RemoteEntry

    /** Moves the entry to the remote trash. */
    suspend fun delete(entry: RemoteEntry)
}

class RemoteUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
