package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.merge.MergeResult
import io.github.maxdac.marknotes.core.merge.ThreeWayMerge
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.notes.NoteEntry
import io.github.maxdac.marknotes.core.notes.NotePaths
import kotlinx.coroutines.CancellationException

/** A note that changed on both sides since the last sync, with edits that could not be merged automatically. */
class SyncConflict(
    val path: String,
    val localContent: ByteArray,
    val remoteContent: ByteArray,
    val remote: RemoteEntry,
    /** Content of the note the last time it was in sync, if known. */
    val baseContent: ByteArray? = null,
    /** The drive the remote version comes from. */
    val driveId: String = ""
) {
    val localText: String get() = localContent.toString(Charsets.UTF_8)
    val remoteText: String get() = remoteContent.toString(Charsets.UTF_8)
    val baseText: String? get() = baseContent?.toString(Charsets.UTF_8)

    /** Three-way merge of the two versions; only the overlapping edits are left as conflicts. */
    val merge: MergeResult by lazy { ThreeWayMerge.merge(baseText, localText, remoteText) }
}

sealed interface ConflictResolution {
    data object KeepLocal : ConflictResolution
    data object KeepRemote : ConflictResolution

    /** Keeps the local version and saves the remote one as a "(conflicted copy)" sibling. */
    data object KeepBoth : ConflictResolution

    /** Saves [text] (typically the result of a hunk-by-hunk merge or a manual edit) on both sides. */
    data class Merged(val text: String) : ConflictResolution
}

data class SyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val deletedLocal: Int,
    val deletedRemote: Int,
    val conflicts: List<SyncConflict>,
    val errors: List<String>,
    val merged: Int = 0
) {
    /** Number of changes made to the local note tree, which other drives need to receive. */
    val localChanges: Int get() = downloaded + deletedLocal + merged

    val summary: String
        get() = buildString {
            append("↑$uploaded ↓$downloaded")
            if (merged > 0) append(", merged $merged")
            if (deletedLocal + deletedRemote > 0) append(", deleted ${deletedLocal + deletedRemote}")
            if (conflicts.isNotEmpty()) append(", ${conflicts.size} conflict(s)")
            if (errors.isNotEmpty()) append(", ${errors.size} error(s)")
        }
}

/**
 * Two-way sync between the local note tree and a remote folder.
 *
 * For every path it compares the local state, the remote state and the [SyncRecord] saved the last
 * time both sides agreed. Only markdown notes and folders are synced. Notes changed on both sides are
 * merged line by line against the last synced version kept in [base]; edits that overlap are reported
 * as [SyncConflict]s and left untouched until resolved. After each run the remote [SyncManifest] is
 * updated.
 */
@Suppress("LongParameterList")
class SyncEngine(
    private val local: LocalNoteStore,
    private val remote: RemoteDriveStore,
    private val metadata: SyncMetadataStore,
    private val base: BaseContentStore = InMemoryBaseContentStore(),
    private val driveId: String = "",
    private val deviceId: String = "",
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun sync(): SyncResult = Run().execute()

    suspend fun resolve(conflict: SyncConflict, resolution: ConflictResolution) {
        when (resolution) {
            ConflictResolution.KeepLocal -> keepLocal(conflict)

            ConflictResolution.KeepRemote -> {
                local.writeBytes(conflict.path, conflict.remoteContent)
                recordFile(conflict.path, conflict.remote, conflict.remoteContent)
            }

            ConflictResolution.KeepBoth -> {
                val copyPath = local.uniqueSibling(conflict.path, CONFLICT_SUFFIX)
                local.writeBytes(copyPath, conflict.remoteContent)
                val copy = remote.uploadNew(copyPath, conflict.remoteContent)
                recordFile(copyPath, copy, conflict.remoteContent)
                keepLocal(conflict)
            }

            is ConflictResolution.Merged -> {
                val bytes = resolution.text.toByteArray(Charsets.UTF_8)
                local.writeBytes(conflict.path, bytes)
                val updated = remote.uploadRevision(conflict.remote, bytes)
                recordFile(conflict.path, updated, bytes)
            }
        }
    }

    private suspend fun keepLocal(conflict: SyncConflict) {
        val bytes = local.readBytes(conflict.path)
        val updated = remote.uploadRevision(conflict.remote, bytes)
        recordFile(conflict.path, updated, bytes)
    }

    private suspend fun recordFile(path: String, entry: RemoteEntry, bytes: ByteArray) {
        recordFile(path, entry, Hashing.sha256Hex(bytes))
        base.put(path, bytes)
    }

    private suspend fun recordFile(path: String, entry: RemoteEntry, hash: String) {
        metadata.upsert(
            SyncRecord(
                path = path,
                isFolder = false,
                remoteId = entry.id,
                remoteVersion = entry.version,
                localHash = hash,
                syncedAt = clock()
            )
        )
    }

    private suspend fun forget(path: String) {
        metadata.delete(path)
        base.delete(path)
    }

    private suspend fun recordFolder(path: String, remoteId: String) {
        metadata.upsert(SyncRecord(path, true, remoteId, "", "", clock()))
    }

    private inner class Run {
        private var uploaded = 0
        private var downloaded = 0
        private var deletedLocal = 0
        private var deletedRemote = 0
        private var merged = 0
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
        private var manifest: SyncManifest? = null
        private val skipped = mutableSetOf<String>()
        private val pendingLocalFolderDeletes = mutableSetOf<String>()
        private val pendingRemoteFolderDeletes = linkedMapOf<String, RemoteEntry>()

        suspend fun execute(): SyncResult {
            localAll = local.listAll().filter { it.isFolder || NotePaths.isNote(it.path) }
            remoteAll =
                remote.snapshot().filter { isSyncablePath(it.path) && (it.isFolder || NotePaths.isNote(it.path)) }
            records = metadata.all().associateBy { it.path }
            manifest = readManifest()

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

            // Phase 4: publish the map of synced notes.
            guarded(SyncManifest.PATH) { writeManifest() }

            return SyncResult(
                uploaded,
                downloaded,
                deletedLocal,
                deletedRemote,
                conflicts.toList(),
                errors.toList(),
                merged
            )
        }

        private suspend fun readManifest(): SyncManifest? = try {
            remote.readManifest()?.let { SyncManifest.parse(it.toString(Charsets.UTF_8)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The manifest is an optimisation: sync works without it.
            null
        }

        private suspend fun writeManifest() {
            val files = metadata.all().filter { !it.isFolder }.associate {
                it.path to SyncManifest.Entry(it.localHash, it.remoteVersion, it.syncedAt)
            }
            val updated = SyncManifest(deviceId, clock(), files)
            if (!updated.sameFilesAs(manifest)) remote.writeManifest(updated.toJson().toByteArray(Charsets.UTF_8))
        }

        /** Hash of the remote file content according to the manifest, when it describes this exact version. */
        private fun manifestHash(remoteFile: RemoteEntry): String? =
            manifest?.files?.get(remoteFile.path)?.takeIf { it.version == remoteFile.version }?.sha256

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
                else -> forget(path)
            }
        }

        private suspend fun syncNoteOnBothSides(path: String, remoteFile: RemoteEntry, record: SyncRecord?) {
            if (record == null) {
                compareOrMerge(path, remoteFile, record)
                return
            }
            val localChanged = localHash(path) != record.localHash
            var remoteChanged = remoteChanged(remoteFile, record)
            if (remoteChanged && manifestHash(remoteFile) == record.localHash) {
                // A new remote version with the same content (e.g. re-uploaded by another device).
                recordFile(path, remoteFile, record.localHash)
                remoteChanged = false
            }
            when {
                localChanged && remoteChanged -> compareOrMerge(path, remoteFile, record)
                localChanged -> uploadRevision(path, remoteFile)
                remoteChanged -> download(path, remoteFile)
            }
        }

        private suspend fun syncLocalOnlyNote(path: String, record: SyncRecord?) {
            when {
                record == null || localHash(path) != record.localHash -> uploadNew(path)

                hasPendingAncestor(path, pendingLocalFolderDeletes) -> forget(path)

                else -> {
                    local.delete(path)
                    forget(path)
                    deletedLocal++
                }
            }
        }

        private suspend fun syncRemoteOnlyNote(path: String, remoteFile: RemoteEntry, record: SyncRecord?) {
            when {
                record == null || remoteChanged(remoteFile, record) -> download(path, remoteFile)

                hasPendingAncestor(path, pendingRemoteFolderDeletes.keys) -> forget(path)

                else -> {
                    remote.delete(remoteFile)
                    forget(path)
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
            base.delete(folder)
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

        /** Both sides changed: identical content is just recorded, otherwise a three-way merge is attempted. */
        private suspend fun compareOrMerge(path: String, remoteFile: RemoteEntry, record: SyncRecord?) {
            val localBytes = local.readBytes(path)
            if (manifestHash(remoteFile) == localHash(path)) {
                recordFile(path, remoteFile, localBytes)
                return
            }
            val remoteBytes = remote.download(remoteFile)
            if (Hashing.sha256Hex(remoteBytes) == localHash(path)) {
                recordFile(path, remoteFile, localBytes)
                return
            }
            val baseBytes = if (record != null) base.get(path) else null
            val conflict = SyncConflict(path, localBytes, remoteBytes, remoteFile, baseBytes, driveId)
            if (baseBytes != null && !conflict.merge.hasConflicts) {
                val mergedBytes = conflict.merge.text().toByteArray(Charsets.UTF_8)
                local.writeBytes(path, mergedBytes)
                val entry = remote.uploadRevision(remoteFile, mergedBytes)
                recordFile(path, entry, mergedBytes)
                merged++
            } else {
                conflicts += conflict
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
