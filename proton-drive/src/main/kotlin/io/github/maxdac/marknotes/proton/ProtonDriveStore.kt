package io.github.maxdac.marknotes.proton

import io.github.maxdac.marknotes.core.notes.Attachments
import io.github.maxdac.marknotes.core.notes.NotePaths
import io.github.maxdac.marknotes.core.sync.RemoteChangedException
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.Channels
import java.time.Instant
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import me.proton.drive.sdk.ProtonDriveClient
import me.proton.drive.sdk.entity.DriveEvent
import me.proton.drive.sdk.entity.DriveEventId
import me.proton.drive.sdk.entity.FileDownloaderRequest
import me.proton.drive.sdk.entity.FileNode
import me.proton.drive.sdk.entity.FileRevisionUploaderRequest
import me.proton.drive.sdk.entity.FileUploaderRequest
import me.proton.drive.sdk.entity.FolderNode
import me.proton.drive.sdk.entity.NodeResultPair
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.RevisionUid
import me.proton.drive.sdk.entity.ScopeId

/**
 * [RemoteDriveStore] backed by the Proton Drive SDK, scoped to a folder in "My files".
 *
 * The tree is traversed once, then kept up to date through the Drive event stream: later snapshots
 * only re-traverse when events report changes inside the synced folder or continuity is lost. Files carry
 * the local edit time as their claimed modification time; the sync manifest lives in the hidden
 * `.marknotes` folder (see [ProtonManifest]).
 */
class ProtonDriveStore(
    private val client: ProtonDriveClient,
    private val rootFolderName: String,
    private val onClose: () -> Unit = {}
) : RemoteDriveStore, AutoCloseable {
    override fun close() {
        try {
            client.close()
        } finally {
            onClose()
        }
    }

    private var rootUid: NodeUid? = null
    private var scopeId: ScopeId? = null
    private var cursor: DriveEventId? = null
    private var stale = true
    private val entries = LinkedHashMap<String, RemoteEntry>()

    override suspend fun snapshot(): List<RemoteEntry> {
        val root = ensureRoot()
        if (!stale) applyEvents()
        if (stale) {
            // Move the cursor first so changes made during the traversal are seen next time.
            applyEvents()
            entries.clear()
            scan(root, "")
            stale = false
        }
        return entries.values.toList()
    }

    override suspend fun download(entry: RemoteEntry): ByteArray = downloadRevision(entry.version)

    override suspend fun uploadNew(path: String, bytes: ByteArray, modifiedMillis: Long): RemoteEntry {
        val parent = ensureFolder(NotePaths.parent(path))
        val modified = modificationTime(modifiedMillis)
        val result = uploadFile(parent, NotePaths.name(path), Attachments.mimeFor(path) ?: MEDIA_TYPE, bytes, modified)
        return RemoteEntry(path, result.nodeUid.value, false, result.revisionUid.value, modified.toEpochMilli())
            .also { entries[path] = it }
    }

    override suspend fun uploadRevision(entry: RemoteEntry, bytes: ByteArray, modifiedMillis: Long): RemoteEntry {
        val modified = modificationTime(modifiedMillis)
        val result = uploadNextRevision(RevisionUid(entry.version), bytes, modified)
        return entry.copy(version = result.revisionUid.value, modifiedMillis = modified.toEpochMilli())
            .also { entries[entry.path] = it }
    }

    private fun modificationTime(modifiedMillis: Long): Instant =
        if (modifiedMillis > 0L) Instant.ofEpochMilli(modifiedMillis) else Instant.now()

    private suspend fun uploadFile(
        parent: NodeUid,
        name: String,
        mediaType: String,
        bytes: ByteArray,
        modified: Instant
    ) =
        client.uploader(
            FileUploaderRequest(
                parentFolderUid = parent,
                name = name,
                mediaType = mediaType,
                fileSize = bytes.size.toLong(),
                lastModificationTime = modified,
                overrideExistingDraftByOtherClient = false
            )
        ).use { uploader ->
            coroutineScope {
                uploader.uploadFromStream(this, Channels.newChannel(ByteArrayInputStream(bytes)))
                    .use { it.awaitCompletion() }
            }
        }

    private suspend fun uploadNextRevision(current: RevisionUid, bytes: ByteArray, modified: Instant) =
        client.uploader(
            FileRevisionUploaderRequest(
                currentActiveRevisionUid = current,
                lastModificationTime = modified,
                size = bytes.size.toLong()
            )
        ).use { uploader ->
            coroutineScope {
                uploader.uploadFromStream(this, Channels.newChannel(ByteArrayInputStream(bytes)))
                    .use { it.awaitCompletion() }
            }
        }

    private suspend fun downloadRevision(revision: String): ByteArray {
        val out = ByteArrayOutputStream()
        client.downloader(FileDownloaderRequest(RevisionUid(revision))).use { downloader ->
            coroutineScope {
                downloader.downloadToStream(this, Channels.newChannel(out)).use { it.awaitCompletion() }
            }
        }
        return out.toByteArray()
    }

    override suspend fun createFolder(path: String): RemoteEntry {
        ensureFolder(path)
        return entries.getValue(NotePaths.normalize(path))
    }

    override suspend fun delete(entry: RemoteEntry) {
        if (!entry.isFolder) {
            // Trash only the revision this device saw: a newer one means another device changed the file.
            val current = client.enumerateNodes(listOf(NodeUid(entry.id))).toList()
                .filterIsInstance<FileNode>().firstOrNull { it.trashTime == null }
                ?: return forget(entry)
            if (current.activeRevision.uid.value != entry.version) throw RemoteChangedException(entry.path)
        }
        val results = client.trashNodes(listOf(NodeUid(entry.id))).toList()
        results.filterIsInstance<NodeResultPair.Failure>().firstOrNull()?.let {
            throw IOException("Could not trash ${entry.path}: ${it.error.message}", it.error)
        }
        forget(entry)
    }

    private fun forget(entry: RemoteEntry) {
        entries.keys.removeAll { it == entry.path || NotePaths.isAncestor(entry.path, it) }
    }

    override suspend fun readManifest(): ByteArray? = manifest.read(ensureRoot().value)

    override suspend fun writeManifest(bytes: ByteArray) = manifest.write(ensureRoot().value, bytes)

    private val manifest = ProtonManifest(
        object : ManifestDrive {
            override suspend fun child(parent: String, name: String): ManifestNode? =
                children(NodeUid(parent)).firstOrNull { it.trashTime == null && it.name.getOrNull() == name }?.let {
                    when (it) {
                        is FolderNode -> ManifestNode(it.uid.value, isFolder = true)
                        is FileNode -> ManifestNode(it.uid.value, isFolder = false, it.activeRevision.uid.value)
                        else -> null
                    }
                }

            override suspend fun download(revision: String): ByteArray = downloadRevision(revision)

            override suspend fun createFolder(parent: String, name: String): ManifestNode =
                ManifestNode(client.createFolder(NodeUid(parent), name).uid.value, isFolder = true)

            override suspend fun uploadNew(parent: String, name: String, bytes: ByteArray) {
                uploadFile(NodeUid(parent), name, MANIFEST_MEDIA_TYPE, bytes, Instant.now())
            }

            override suspend fun uploadRevision(file: ManifestNode, bytes: ByteArray) {
                uploadNextRevision(RevisionUid(file.revision), bytes, Instant.now())
            }
        }
    )

    private suspend fun ensureRoot(): NodeUid {
        rootUid?.let { return it }
        val myFiles = client.getMyFilesFolder()
        scopeId = myFiles.treeEventScopeId
        val existing = children(myFiles.uid).firstOrNull {
            it is FolderNode && it.trashTime == null && it.name.getOrNull() == rootFolderName
        }
        val root = existing?.uid ?: client.createFolder(myFiles.uid, rootFolderName).uid
        rootUid = root
        stale = true
        return root
    }

    private suspend fun ensureFolder(path: String): NodeUid {
        val normalized = NotePaths.normalize(path)
        if (normalized.isEmpty()) return ensureRoot()
        entries[normalized]?.let { existing ->
            if (!existing.isFolder) throw IOException("$normalized is a file in Proton Drive")
            return NodeUid(existing.id)
        }
        val parent = ensureFolder(NotePaths.parent(normalized))
        val folder = client.createFolder(parent, NotePaths.name(normalized))
        entries[normalized] = RemoteEntry(normalized, folder.uid.value, true, "", folder.creationTime.toEpochMilli())
        return folder.uid
    }

    private suspend fun scan(folder: NodeUid, path: String) {
        val visible = children(folder).filter { it.trashTime == null }
        for (node in visible) {
            // A name that can't be decrypted would look like a deletion: fail the snapshot instead.
            val name = decryptedName(node.name, path)
            // The hidden .marknotes folder holds the manifest and is never synced.
            if (path.isEmpty() && name.startsWith(".")) continue
            val childPath = NotePaths.join(path, name)
            when (node) {
                is FolderNode -> {
                    entries[childPath] =
                        RemoteEntry(childPath, node.uid.value, true, "", node.creationTime.toEpochMilli())
                    scan(node.uid, childPath)
                }

                is FileNode -> {
                    val revision = node.activeRevision
                    val modified = revision.claimedModificationTime ?: revision.creationTime
                    entries[childPath] =
                        RemoteEntry(childPath, node.uid.value, false, revision.uid.value, modified.toEpochMilli())
                }

                else -> Unit
            }
        }
    }

    private suspend fun children(folder: NodeUid) =
        client.enumerateNodes(client.enumerateFolderChildrenNodeUids(folder).toList()).toList()

    /** Reads pending Drive events and marks the cache stale when something in the synced folder changed. */
    private suspend fun applyEvents() {
        val scope = scopeId ?: return
        val known = entries.values.map { it.id }.toMutableSet()
        rootUid?.let { known += it.value }
        client.enumerateEvents(scope, cursor).collect { event ->
            when (event) {
                is DriveEvent.NodeUpdated -> if (touches(known, event.nodeUid, event.parentNodeUid)) stale = true

                is DriveEvent.NodeDeleted -> if (touches(known, event.nodeUid, event.parentNodeUid)) stale = true

                is DriveEvent.ContinuityLost -> stale = true

                is DriveEvent.ScopeAccessLost -> {
                    stale = true
                    rootUid = null
                }

                else -> Unit
            }
            cursor = event.id
        }
    }

    private fun touches(known: Set<String>, node: NodeUid, parent: NodeUid?): Boolean =
        node.value in known || (parent != null && parent.value in known)

    private companion object {
        const val MEDIA_TYPE = "text/markdown"
        const val MANIFEST_MEDIA_TYPE = "application/json"
    }
}

/** The decrypted name of an entry in [folder]; a name that can't be decrypted fails the snapshot. */
internal fun decryptedName(name: Result<String>, folder: String): String =
    name.getOrElse { throw IOException("Could not decrypt the name of an entry in /$folder", it) }
