package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.notes.NoteIds
import io.github.maxdac.marknotes.sync.Action
import io.github.maxdac.marknotes.sync.LocalEntry as CoreLocalEntry
import io.github.maxdac.marknotes.sync.ReconcileInput
import io.github.maxdac.marknotes.sync.ReconcileOutcome
import io.github.maxdac.marknotes.sync.RemoteEntry as CoreRemoteEntry
import io.github.maxdac.marknotes.sync.Side
import io.github.maxdac.marknotes.sync.SyncInput
import io.github.maxdac.marknotes.sync.SyncRecord as CoreSyncRecord
import io.github.maxdac.marknotes.sync.buildManifest
import io.github.maxdac.marknotes.sync.isAttachmentPath
import io.github.maxdac.marknotes.sync.isSyncedEntry
import io.github.maxdac.marknotes.sync.plan
import io.github.maxdac.marknotes.sync.reconcile
import kotlinx.coroutines.CancellationException

/**
 * A note edited on both sides whose edits could not be merged. The newest version ([winner]) was kept at [path]
 * and the other one was saved as a new note at [copyPath]; see [ConflictMergeBack] to merge it back.
 */
data class SyncConflict(
    val path: String,
    val copyPath: String,
    val winner: Side,
    /** Text of the note the last time both sides agreed, if known. */
    val baseText: String? = null,
    /** The drive the conflict was found on. */
    val driveId: String = ""
)

data class SyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val deletedLocal: Int,
    val deletedRemote: Int,
    val conflicts: List<SyncConflict>,
    val errors: List<String>,
    val merged: Int = 0
) {
    /** Number of changes made to the local note tree (conflicted copies included), which other drives need. */
    val localChanges: Int get() = downloaded + deletedLocal + merged + conflicts.size

    val summary: String
        get() = buildString {
            append("↑$uploaded ↓$downloaded")
            if (merged > 0) append(", merged $merged")
            if (deletedLocal + deletedRemote > 0) append(", deleted ${deletedLocal + deletedRemote}")
            if (conflicts.isNotEmpty()) append(", ${conflicts.size} conflicted copy(ies)")
            if (errors.isNotEmpty()) append(", ${errors.size} error(s)")
        }
}

/**
 * Two-way sync between the local note tree and one drive.
 *
 * The plan and the conflict handling come from the shared sync core
 * ([marknotes-sync](https://github.com/MaxDac/marknotes-sync)), which implements the marknotes-spec sync rules for
 * every MarkNotes app. This class only lists both sides, runs the planned actions against the stores and writes the
 * drive's `.marknotes/manifest.json` (see [SyncManifest]).
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

    @Suppress("TooManyFunctions")
    private inner class Run {
        private var uploaded = 0
        private var downloaded = 0
        private var deletedLocal = 0
        private var deletedRemote = 0
        private var merged = 0
        private val conflicts = mutableListOf<SyncConflict>()
        private val errors = mutableListOf<String>()
        private val modified = HashMap<String, Long>()
        private val knownPaths = HashSet<String>()

        suspend fun execute(): SyncResult {
            val localEntries = local.listAll().filter { isSyncedEntry(it.path, it.isFolder) }.map {
                modified[it.path] = it.modifiedMillis
                val hash = if (it.isFolder) null else Hashing.sha256Hex(local.readBytes(it.path))
                CoreLocalEntry(it.path, it.isFolder, it.modifiedMillis, hash)
            }
            val remoteEntries = remote.snapshot()
            knownPaths += localEntries.map { it.path } + remoteEntries.map { it.path }
            val manifest = readManifest()
            val input = SyncInput(
                local = localEntries,
                remote = remoteEntries.map { it.toCore() },
                records = metadata.all().map { it.toCore() },
                manifest = manifest
            )
            for (action in plan(input).actions) {
                guarded(action.path) { apply(action) }
            }
            guarded(SyncManifest.PATH) {
                val records = metadata.all().map { it.toCore() }
                buildManifest(records, deviceId, clock(), manifest)?.let {
                    remote.writeManifest(it.toByteArray(Charsets.UTF_8))
                }
            }
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

        private suspend fun readManifest(): String? = try {
            remote.readManifest()?.toString(Charsets.UTF_8)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The manifest is an optimisation: sync works without it.
            null
        }

        @Suppress("CyclomaticComplexMethod")
        private suspend fun apply(action: Action) {
            when (action) {
                is Action.Error -> errors += "${action.path}: ${action.message}"

                is Action.RecordFolder -> recordFolder(action.path, action.remoteId)

                is Action.CreateRemoteFolder -> recordFolder(action.path, remote.createFolder(action.path).id)

                is Action.CreateLocalFolder -> {
                    local.createFolder(action.path)
                    recordFolder(action.path, action.remoteId)
                }

                is Action.Forget -> forget(action.path)

                is Action.UploadNew -> {
                    val bytes = local.readBytes(action.path)
                    recordFile(action.path, remote.uploadNew(action.path, bytes), bytes)
                    uploaded++
                }

                is Action.UploadRevision -> {
                    val bytes = local.readBytes(action.path)
                    recordFile(action.path, remote.uploadRevision(action.remote.toApp(), bytes), bytes)
                    uploaded++
                }

                is Action.Download -> {
                    val entry = action.remote.toApp()
                    val bytes = remote.download(entry)
                    local.writeBytes(action.path, bytes)
                    recordFile(action.path, entry, bytes)
                    downloaded++
                }

                is Action.Record -> {
                    upsertFile(action.path, action.remote.toApp(), action.sha256)
                    if (action.updateBase) base.put(action.path, local.readBytes(action.path))
                }

                is Action.DeleteLocal -> {
                    local.delete(action.path)
                    forget(action.path)
                    deletedLocal++
                }

                is Action.DeleteRemote -> {
                    remote.delete(action.remote.toApp())
                    forget(action.path)
                    deletedRemote++
                }

                is Action.DeleteLocalFolder -> {
                    local.delete(action.path)
                    forgetTree(action.path, action.forget)
                    deletedLocal++
                }

                is Action.DeleteRemoteFolder -> {
                    remote.delete(action.remote.toApp())
                    forgetTree(action.path, action.forget)
                    deletedRemote++
                }

                is Action.Reconcile -> reconcileNote(action.path, action.remote, action.hasRecord)
            }
        }

        /** The note changed on both sides: keep it, merge it, or keep the newest and save the other as a copy. */
        private suspend fun reconcileNote(path: String, remoteEntry: CoreRemoteEntry, hasRecord: Boolean) {
            val entry = remoteEntry.toApp()
            val localBytes = local.readBytes(path)
            val remoteBytes = remote.download(entry)
            val baseText = if (hasRecord) base.get(path)?.toString(Charsets.UTF_8) else null
            val outcome = reconcile(
                ReconcileInput(
                    path = path,
                    local = localBytes.toString(Charsets.UTF_8),
                    localModifiedMillis = modified[path] ?: 0L,
                    remote = remoteBytes.toString(Charsets.UTF_8),
                    remoteEntry = remoteEntry,
                    base = baseText,
                    existingPaths = knownPaths.toList(),
                    newId = NoteIds.generate()
                )
            )
            when (outcome) {
                is ReconcileOutcome.Same -> recordFile(path, entry, localBytes)

                is ReconcileOutcome.Merged -> {
                    val bytes = outcome.text.toByteArray(Charsets.UTF_8)
                    local.writeBytes(path, bytes)
                    recordFile(path, remote.uploadRevision(entry, bytes), bytes)
                    merged++
                }

                is ReconcileOutcome.NewestWins -> {
                    if (outcome.winner == Side.LOCAL) {
                        recordFile(path, remote.uploadRevision(entry, localBytes), localBytes)
                        uploaded++
                    } else {
                        local.writeBytes(path, remoteBytes)
                        recordFile(path, entry, remoteBytes)
                        downloaded++
                    }
                    val copyBytes = outcome.copyText.toByteArray(Charsets.UTF_8)
                    local.writeBytes(outcome.copyPath, copyBytes)
                    knownPaths += outcome.copyPath
                    recordFile(outcome.copyPath, remote.uploadNew(outcome.copyPath, copyBytes), copyBytes)
                    uploaded++
                    conflicts += SyncConflict(path, outcome.copyPath, outcome.winner, baseText, driveId)
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

        /** Records a synced file and, for notes, keeps [bytes] as the base copy for later merges. */
        private suspend fun recordFile(path: String, entry: RemoteEntry, bytes: ByteArray) {
            upsertFile(path, entry, Hashing.sha256Hex(bytes))
            if (!isAttachmentPath(path)) base.put(path, bytes)
        }

        private suspend fun upsertFile(path: String, entry: RemoteEntry, hash: String) {
            metadata.upsert(SyncRecord(path, false, entry.id, entry.version, hash, clock()))
        }

        private suspend fun recordFolder(path: String, remoteId: String) {
            metadata.upsert(SyncRecord(path, true, remoteId, "", "", clock()))
        }

        private suspend fun forget(path: String) {
            metadata.delete(path)
            base.delete(path)
        }

        private suspend fun forgetTree(folder: String, paths: List<String>) {
            paths.forEach { metadata.delete(it) }
            base.delete(folder)
        }
    }
}

private val Action.path: String
    get() = when (this) {
        is Action.Error -> path
        is Action.RecordFolder -> path
        is Action.CreateRemoteFolder -> path
        is Action.CreateLocalFolder -> path
        is Action.Forget -> path
        is Action.UploadNew -> path
        is Action.UploadRevision -> path
        is Action.Download -> path
        is Action.Record -> path
        is Action.DeleteLocal -> path
        is Action.DeleteRemote -> path
        is Action.DeleteLocalFolder -> path
        is Action.DeleteRemoteFolder -> path
        is Action.Reconcile -> path
    }

private fun RemoteEntry.toCore() = CoreRemoteEntry(path, id, isFolder, version, modifiedMillis)

private fun CoreRemoteEntry.toApp() = RemoteEntry(path, id, isFolder, version, modifiedMillis)

private fun SyncRecord.toCore() = CoreSyncRecord(path, isFolder, remoteId, remoteVersion, localHash, syncedAt)
