package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.NotePaths

enum class DriveProvider(val displayName: String) {
    PROTON("Proton Drive"),
    DROPBOX("Dropbox"),
    ONEDRIVE("OneDrive")
}

/**
 * A connected cloud drive. Every connected drive mirrors the same local note tree.
 *
 * @param remoteFolder folder picked by the user, "/"-separated and relative to the drive root ("" = root).
 * @param createRootFolder whether the app syncs into a [rootFolderName] folder created inside [remoteFolder]
 *   instead of directly into [remoteFolder].
 */
data class DriveAccount(
    val id: String,
    val provider: DriveProvider,
    val label: String,
    val remoteFolder: String,
    val createRootFolder: Boolean,
    val rootFolderName: String = DEFAULT_ROOT_FOLDER
) {
    /** The folder that is mirrored with the local note tree, relative to the drive root. */
    val syncFolder: String
        get() = if (createRootFolder) NotePaths.join(remoteFolder.trim('/'), rootFolderName) else remoteFolder.trim('/')

    companion object {
        const val DEFAULT_ROOT_FOLDER = "MarkNotes"
    }
}

/** A folder shown by the remote folder picker. [path] is relative to the drive root. */
data class RemoteFolder(val path: String, val name: String)

/** Lists folders of a drive, used to pick the folder to sync. */
fun interface RemoteFolderBrowser {
    /** Lists the sub-folders of [path] ("" = drive root). */
    suspend fun listFolders(path: String): List<RemoteFolder>
}
