package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.notes.NoteEntry
import io.github.maxdac.marknotes.core.notes.NotePaths
import kotlinx.coroutines.CancellationException

/** A note that changed on both sides since the last sync. */
class SyncConflict(
    val path: String,
    val localContent: ByteArray,
    val remoteContent: ByteArray,
    val remote: RemoteEntry
) {
    val localText: String get() = localContent.toString(Charsets.UTF_8)
    val remoteText: String get() = remoteContent.toString(Charsets.UTF_8)
}

enum class ConflictResolution { KEEP_LOCAL, KEEP_REMOTE, KEEP_BOTH }

data class SyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val deletedLocal: Int,
    val deletedRemote: Int,
    val conflicts: List<SyncConflict>,
    val errors: List<String>
) {
    val summary: String
        get() = buildString {
            append("↑$uploaded ↓$downloaded")
            if (deletedLocal + deletedRemote > 0) append(", deleted ${deletedLocal + deletedRemote}")
            if (conflicts.isNotEmpty()) append(", ${conflicts.size} conflict(s)")
            if (errors.isNotEmpty()) append(", ${errors.size} error(s)")
        }
}

/**
 * Two-way sync between the local note tree and a remote folder.
 *
 * For every path it compares the local state, the remote state and the [SyncRecord] saved the last
 * time both sides agreed. Only markdown notes and folders are synced. Changes on both sides that end
 * up with different content are reported as [SyncConflict]s and left untouched until resolved.
 */
class SyncEngine(
    private val local: LocalNoteStore,
    private val remote: RemoteDriveStore,
    private val metadata: SyncMetadataStore,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun sync(): SyncResult = Run().execute()

    suspend fun resolve(conflict: SyncConflict, resolution: ConflictResolution) {
        when (resolution) {
            ConflictResolution.KEEP_LOCAL -> keepLocal(conflict)

            ConflictResolution.KEEP_REMOTE -> {
                local.writeBytes(conflict.path, conflict.remoteContent)
                recordFile(conflict.path, conflict.remote, conflict.remoteContent)
            }

            ConflictResolution.KEEP_BOTH -> {
                val copyPath = local.uniqueSibling(conflict.path, CONFLICT_SUFFIX)
                local.writeBytes(copyPath, conflict.remoteContent)
                val copy = remote.uploadNew(copyPath, conflict.remoteContent)
                recordFile(copyPath, copy, conflict.remoteContent)
                keepLocal(conflict)
            }
        }
    }

    private suspend fun keepLocal(conflict: SyncConflict) {
        val bytes = local.readBytes(conflict.path)
        val updated = remote.uploadRevision(conflict.remote, bytes)
        recordFile(conflict.path, updated, bytes)
    }

    private suspend fun recordFile(path: String, entry: RemoteEntry, bytes: ByteArray) {
        metadata.upsert(
            SyncRecord(
                path = path,
                isFolder = false,
                remoteId = entry.id,
                remoteVersion = entry.version,
                localHash = Hashing.sha256Hex(bytes),
                syncedAt = clock()
            )
        )
    }

    private suspend fun recordFolder(path: String, remoteId: String) {
        metadata.upsert(SyncRecord(path, true, remoteId, "", "", clock()))
    }

    private inner class Run {
        private var uploaded = 0
        private var downloaded = 0
        private var deletedLocal = 0
        private var deletedRemote = 0
        private val conflicts = mutableListOf<SyncConflict>()
        private val errors = mutableListOf<String>()
        private val localHashes = HashMap<String, String>()

        private lateinit var localAll: List<NoteEntry>
        private lateinit var remoteAll: List<RemoteEntry>
        private lateinit var records: Map<String, SyncRecord>
        private lateinit var localFolders: Set<String>
        private lateinit var localFiles: Set<String>
        private lateinit var remoteFolders: Map<String, RemoteEntry>
        private lateinit var remoteFiles: Map<String, RemoteEntry>
        private val skipped = mutableSetOf<String>()
        private val pendingLocalFolderDeletes = mutableSetOf<String>()
        private val pendingRemoteFolderDeletes = linkedMapOf<String, RemoteEntry>()

        suspend fun execute(): SyncResult {
            localAll = local.listAll().filter { it.isFolder || NotePaths.isNote(it.path) }
            remoteAll =
                remote.snapshot().filter { isSyncablePath(it.path) && (it.isFolder || NotePaths.isNote(it.path)) }
            records = metadata.all().associateBy { it.path }

            localFolders = localAll.filter { it.isFolder }.map { it.path }.toSet()
            localFiles = localAll.filter { !it.isFolder }.map { it.path }.toSet()
            remoteFolders = remoteAll.filter { it.isFolder }.associateBy { it.path }
            remoteFiles = remoteAll.filter { !it.isFolder }.associateBy { it.path }

            (localFolders.filter { it in remoteFiles } + localFiles.filter { it in remoteFolders }).forEach {
                skipped += it
                errors += "$it: is a file on one side and a folder on the other"
            }

            // Phase 1: folders, parents first.
            val recordedFolders = records.values.filter { it.isFolder }.map { it.path }
            val folderPaths = (localFolders + remoteFolders.keys + recordedFolders)
                .toSortedSet(compareBy<String>({ NotePaths.depth(it) }, { it }))
            for (path in folderPaths) {
                if (path !in skipped) guarded(path) { syncFolder(path) }
            }

            // Phase 2: notes.
            val recordedFiles = records.values.filter { !it.isFolder }.map { it.path }
            val filePaths = (localFiles + remoteFiles.keys + recordedFiles).toSortedSet()
            for (path in filePaths) {
                if (path !in skipped) guarded(path) { syncNote(path) }
            }

            // Phase 3: folder deletions, top-most folders only.
            applyFolderDeletes()

            return SyncResult(uploaded, downloaded, deletedLocal, deletedRemote, conflicts.toList(), errors.toList())
        }

        private suspend fun syncFolder(path: String) {
            val inLocal = path in localFolders
            val remoteFolder = remoteFolders[path]
            val record = records[path]?.takeIf { it.isFolder }
            when {
                inLocal && remoteFolder != null -> {
                    if (record == null || record.remoteId != remoteFolder.id) {
                        recordFolder(path, remoteFolder.id)
                    }
                }

                inLocal -> {
                    if (record == null || hasLocalSurvivors(path)) {
                        val created = remote.createFolder(path)
                        recordFolder(path, created.id)
                    } else {
                        pendingLocalFolderDeletes += path
                    }
                }

                remoteFolder != null -> {
                    if (record == null || hasRemoteSurvivors(path)) {
                        local.createFolder(path)
                        recordFolder(path, remoteFolder.id)
                    } else {
                        pendingRemoteFolderDeletes[path] = remoteFolder
                    }
                }

                else -> metadata.delete(path)
            }
        }

        private suspend fun syncNote(path: String) {
            val inLocal = path in localFiles
            val remoteFile = remoteFiles[path]
            val record = records[path]?.takeIf { !it.isFolder }
            when {
                inLocal && remoteFile != null -> syncNoteOnBothSides(path, remoteFile, record)
                inLocal -> syncLocalOnlyNote(path, record)
                remoteFile != null -> syncRemoteOnlyNote(path, remoteFile, record)
                else -> metadata.delete(path)
            }
        }

        private suspend fun syncNoteOnBothSides(path: String, remoteFile: RemoteEntry, record: SyncRecord?) {
            if (record == null) {
                compareOrConflict(path, remoteFile)
                return
            }
            val localChanged = localHash(path) != record.localHash
            val remoteChanged = remoteChanged(remoteFile, record)
            when {
                localChanged && remoteChanged -> compareOrConflict(path, remoteFile)
                localChanged -> uploadRevision(path, remoteFile)
                remoteChanged -> download(path, remoteFile)
            }
        }

        private suspend fun syncLocalOnlyNote(path: String, record: SyncRecord?) {
            when {
                record == null || localHash(path) != record.localHash -> uploadNew(path)

                hasPendingAncestor(path, pendingLocalFolderDeletes) -> metadata.delete(path)

                else -> {
                    local.delete(path)
                    metadata.delete(path)
                    deletedLocal++
                }
            }
        }

        private suspend fun syncRemoteOnlyNote(path: String, remoteFile: RemoteEntry, record: SyncRecord?) {
            when {
                record == null || remoteChanged(remoteFile, record) -> download(path, remoteFile)

                hasPendingAncestor(path, pendingRemoteFolderDeletes.keys) -> metadata.delete(path)

                else -> {
                    remote.delete(remoteFile)
                    metadata.delete(path)
                    deletedRemote++
                }
            }
        }

        private suspend fun applyFolderDeletes() {
            for (path in pendingLocalFolderDeletes) {
                if (hasPendingAncestor(path, pendingLocalFolderDeletes)) continue
                guarded(path) {
                    local.delete(path)
                    forgetTree(path)
                }
            }
            for ((path, entry) in pendingRemoteFolderDeletes) {
                if (hasPendingAncestor(path, pendingRemoteFolderDeletes.keys)) continue
                guarded(path) {
                    remote.delete(entry)
                    forgetTree(path)
                }
            }
        }

        private inline fun guarded(path: String, block: () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "$path: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        private fun localHash(path: String): String =
            localHashes.getOrPut(path) { Hashing.sha256Hex(local.readBytes(path)) }

        private fun remoteChanged(entry: RemoteEntry, record: SyncRecord): Boolean =
            entry.version != record.remoteVersion || entry.id != record.remoteId

        private fun hasLocalSurvivors(folder: String): Boolean = localAll.any { entry ->
            if (!NotePaths.isAncestor(folder, entry.path)) return@any false
            val record = records[entry.path]
            when {
                entry.isFolder -> record == null
                else -> record == null || localHash(entry.path) != record.localHash
            }
        }

        private fun hasRemoteSurvivors(folder: String): Boolean = remoteAll.any { entry ->
            if (!NotePaths.isAncestor(folder, entry.path)) return@any false
            val record = records[entry.path]
            when {
                entry.isFolder -> record == null
                else -> record == null || remoteChanged(entry, record)
            }
        }

        private fun hasPendingAncestor(path: String, pending: Set<String>): Boolean =
            pending.any { it != path && NotePaths.isAncestor(it, path) }

        private suspend fun forgetTree(folder: String) {
            records.keys.filter { it == folder || NotePaths.isAncestor(folder, it) }.forEach { metadata.delete(it) }
        }

        private suspend fun uploadNew(path: String) {
            val bytes = local.readBytes(path)
            val entry = remote.uploadNew(path, bytes)
            recordFile(path, entry, bytes)
            uploaded++
        }

        private suspend fun uploadRevision(path: String, remoteFile: RemoteEntry) {
            val bytes = local.readBytes(path)
            val entry = remote.uploadRevision(remoteFile, bytes)
            recordFile(path, entry, bytes)
            uploaded++
        }

        private suspend fun download(path: String, remoteFile: RemoteEntry) {
            val bytes = remote.download(remoteFile)
            local.writeBytes(path, bytes)
            recordFile(path, remoteFile, bytes)
            downloaded++
        }

        private suspend fun compareOrConflict(path: String, remoteFile: RemoteEntry) {
            val remoteBytes = remote.download(remoteFile)
            val localBytes = local.readBytes(path)
            if (Hashing.sha256Hex(remoteBytes) == Hashing.sha256Hex(localBytes)) {
                recordFile(path, remoteFile, localBytes)
            } else {
                conflicts += SyncConflict(path, localBytes, remoteBytes, remoteFile)
            }
        }
    }

    companion object {
        const val CONFLICT_SUFFIX = " (conflicted copy)"

        fun isSyncablePath(path: String): Boolean = try {
            path.isNotEmpty() && NotePaths.normalize(path) == path
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
