package io.github.maxdac.marknotes.sync

import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.CreateFolderErrorException
import com.dropbox.core.v2.files.DeleteErrorException
import com.dropbox.core.v2.files.DeletedMetadata
import com.dropbox.core.v2.files.DownloadErrorException
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.FolderMetadata
import com.dropbox.core.v2.files.ListFolderContinueErrorException
import com.dropbox.core.v2.files.ListFolderErrorException
import com.dropbox.core.v2.files.ListFolderResult
import com.dropbox.core.v2.files.Metadata
import com.dropbox.core.v2.files.WriteMode
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteEntry
import io.github.maxdac.marknotes.core.sync.RemoteFolder
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser
import io.github.maxdac.marknotes.core.sync.SyncManifest
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dropbox paths are "" for the root and "/a/b" otherwise. */
private fun dropboxPath(path: String): String = path.trim('/').let { if (it.isEmpty()) "" else "/$it" }

class DropboxFolderBrowser(private val client: DbxClientV2) : RemoteFolderBrowser {
    override suspend fun listFolders(path: String): List<RemoteFolder> = withContext(Dispatchers.IO) {
        val folders = mutableListOf<RemoteFolder>()
        var result = client.files().listFolder(dropboxPath(path))
        while (true) {
            result.entries.filterIsInstance<FolderMetadata>().forEach {
                folders += RemoteFolder((it.pathDisplay ?: it.pathLower.orEmpty()).trim('/'), it.name)
            }
            if (!result.hasMore) break
            result = client.files().listFolderContinue(result.cursor)
        }
        folders.sortedBy { it.name.lowercase(Locale.ROOT) }
    }
}

/**
 * Mirrors [syncFolder] of a Dropbox account. The first snapshot lists the folder recursively; later
 * ones only fetch the changes since the saved cursor.
 */
class DropboxDriveStore(private val client: DbxClientV2, syncFolder: String) : RemoteDriveStore {
    private val root = dropboxPath(syncFolder)
    private val rootLower = root.lowercase(Locale.ROOT)
    private var cursor: String? = null

    /** Entries keyed by lower-case relative path, since Dropbox paths are case-insensitive. */
    private val entries = LinkedHashMap<String, RemoteEntry>()

    private fun absolute(path: String) = "$root/${path.trim('/')}"

    override suspend fun snapshot(): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val saved = cursor
        if (saved == null) {
            fullListing()
        } else {
            try {
                applyChanges(client.files().listFolderContinue(saved))
            } catch (e: ListFolderContinueErrorException) {
                if (!e.errorValue.isReset) throw e
                fullListing()
            }
        }
        entries.values.toList()
    }

    private fun fullListing() {
        entries.clear()
        cursor = null
        val first = try {
            client.files().listFolderBuilder(root).withRecursive(true).start()
        } catch (e: ListFolderErrorException) {
            if (!(e.errorValue.isPath && e.errorValue.pathValue.isNotFound) || root.isEmpty()) throw e
            client.files().createFolderV2(root)
            client.files().listFolderBuilder(root).withRecursive(true).start()
        }
        applyChanges(first)
    }

    private fun applyChanges(first: ListFolderResult) {
        var result = first
        while (true) {
            result.entries.forEach(::apply)
            cursor = result.cursor
            if (!result.hasMore) break
            result = client.files().listFolderContinue(result.cursor)
        }
    }

    private fun apply(metadata: Metadata) {
        val lower = metadata.pathLower ?: return
        if (lower != rootLower && !lower.startsWith("$rootLower/")) return
        val key = lower.removePrefix(rootLower).trim('/')
        if (key.isEmpty() || key.split('/').any { it.startsWith(".") }) return
        when (metadata) {
            is DeletedMetadata -> entries.keys.removeAll { it == key || it.startsWith("$key/") }
            else -> entries[key] = toEntry(metadata) ?: return
        }
    }

    private fun toEntry(metadata: Metadata): RemoteEntry? {
        val display = metadata.pathDisplay ?: return null
        // Only the case of the path may differ between pathDisplay and pathLower.
        val relative = display.substring(root.length).trim('/')
        return when (metadata) {
            is FileMetadata -> RemoteEntry(relative, metadata.id, false, metadata.rev, metadata.serverModified.time)
            is FolderMetadata -> RemoteEntry(relative, metadata.id, true, "", 0L)
            else -> null
        }
    }

    private fun remember(metadata: Metadata): RemoteEntry {
        val entry = toEntry(metadata) ?: error("Unexpected Dropbox metadata for ${metadata.pathDisplay}")
        entries[entry.path.lowercase(Locale.ROOT)] = entry
        return entry
    }

    override suspend fun download(entry: RemoteEntry): ByteArray = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        client.files().download(entry.id).download(out)
        out.toByteArray()
    }

    override suspend fun uploadNew(path: String, bytes: ByteArray): RemoteEntry = upload(path, bytes, WriteMode.ADD)

    override suspend fun uploadRevision(entry: RemoteEntry, bytes: ByteArray): RemoteEntry =
        upload(entry.path, bytes, WriteMode.update(entry.version))

    private suspend fun upload(path: String, bytes: ByteArray, mode: WriteMode): RemoteEntry =
        withContext(Dispatchers.IO) {
            val metadata = client.files().uploadBuilder(absolute(path))
                .withMode(mode)
                .withAutorename(false)
                .uploadAndFinish(bytes.inputStream())
            remember(metadata)
        }

    override suspend fun createFolder(path: String): RemoteEntry = withContext(Dispatchers.IO) {
        val metadata = try {
            client.files().createFolderV2(absolute(path)).metadata
        } catch (e: CreateFolderErrorException) {
            if (!(e.errorValue.isPath && e.errorValue.pathValue.isConflict)) throw e
            client.files().getMetadata(absolute(path))
        }
        remember(metadata)
    }

    override suspend fun delete(entry: RemoteEntry): Unit = withContext(Dispatchers.IO) {
        try {
            client.files().deleteV2(absolute(entry.path))
        } catch (e: DeleteErrorException) {
            if (!(e.errorValue.isPathLookup && e.errorValue.pathLookupValue.isNotFound)) throw e
        }
        val key = entry.path.lowercase(Locale.ROOT)
        entries.keys.removeAll { it == key || it.startsWith("$key/") }
    }

    override suspend fun readManifest(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val out = ByteArrayOutputStream()
            client.files().download(absolute(SyncManifest.PATH)).download(out)
            out.toByteArray()
        } catch (e: DownloadErrorException) {
            if (!(e.errorValue.isPath && e.errorValue.pathValue.isNotFound)) throw e
            null
        }
    }

    override suspend fun writeManifest(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        client.files().uploadBuilder(absolute(SyncManifest.PATH))
            .withMode(WriteMode.OVERWRITE)
            .withMute(true)
            .uploadAndFinish(bytes.inputStream())
    }
}
