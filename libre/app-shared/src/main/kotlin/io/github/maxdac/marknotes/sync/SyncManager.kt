package io.github.maxdac.marknotes.sync

import android.content.Context
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.sync.ConflictMergeBack
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.DriveSyncTarget
import io.github.maxdac.marknotes.core.sync.FileBaseContentStore
import io.github.maxdac.marknotes.core.sync.FileConflictStore
import io.github.maxdac.marknotes.core.sync.MultiDriveSync
import io.github.maxdac.marknotes.core.sync.SyncConflict
import io.github.maxdac.marknotes.core.sync.UserFacingError
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.DriveAccountDao
import io.github.maxdac.marknotes.data.DriveAccountEntity
import io.github.maxdac.marknotes.data.RoomSyncMetadataStore
import io.github.maxdac.marknotes.data.SyncRecordDao
import io.github.maxdac.marknotes.sync.MergeResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A connected drive and the outcome of its last sync. */
data class DriveStatus(
    val account: DriveAccount,
    val lastSyncAt: Long,
    val message: String?,
    val unavailableReason: String?
)

data class SyncState(
    val drives: List<DriveStatus> = emptyList(),
    val running: Boolean = false,
    val message: String? = null,
    val conflicts: List<SyncConflict> = emptyList()
) {
    val hasDrives: Boolean get() = drives.isNotEmpty()
}

/**
 * Keeps the local notes in sync with every connected drive. Sync is only triggered on app launch and on
 * demand: no background or periodic polling. Editors' pending saves are written first ([beforeSync]), and the
 * conflicts waiting for the merge-back UI are kept across restarts.
 */
class SyncManager(
    private val context: Context,
    private val notes: LocalNoteStore,
    private val records: SyncRecordDao,
    private val accounts: DriveAccountDao,
    private val connectors: Map<DriveProvider, DriveConnector>,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val beforeSync: suspend () -> Unit = {},
    private val onLocalChanged: () -> Unit
) {
    private val mutex = Mutex()
    private val multiSync = MultiDriveSync(notes, settings.deviceId)
    private val mergeBack = ConflictMergeBack(notes)
    private val migrated = CompletableDeferred<Unit>()
    private val conflictStore = FileConflictStore(File(context.filesDir, "sync-conflicts.json"))

    private val _state = MutableStateFlow(SyncState(message = settings.lastSyncSummary.ifBlank { null }))
    val state: StateFlow<SyncState> = _state

    init {
        scope.launch {
            try {
                restoreConflicts()
                migrateLegacyAccounts()
            } finally {
                migrated.complete(Unit)
            }
            launch {
                state.map { it.conflicts }.distinctUntilChanged().drop(1).collect { conflicts ->
                    withContext(Dispatchers.IO) { runCatching { conflictStore.save(conflicts) } }
                }
            }
            accounts.observe().collect { entities ->
                val drives = entities.mapNotNull { entity ->
                    // Accounts of a provider this build doesn't ship (e.g. a Pro database restored in libre).
                    val account = entity.toAccount()?.takeIf { it.provider in connectors } ?: return@mapNotNull null
                    DriveStatus(
                        account,
                        entity.lastSyncAt,
                        entity.lastMessage,
                        connector(account.provider).unavailableReason
                    )
                }
                _state.update { it.copy(drives = drives) }
            }
        }
    }

    fun connector(provider: DriveProvider): DriveConnector = connectors.getValue(provider)

    /** The providers this build can connect, in [DriveProvider] order. */
    val providers: List<DriveProvider> get() = DriveProvider.entries.filter { it in connectors }

    /** Called whenever the activity resumes, to complete browser-based sign-ins. */
    fun onActivityResumed() = connectors.values.forEach { it.onActivityResumed() }

    /** Saves a newly connected drive and syncs it. */
    fun addDrive(account: DriveAccount) {
        scope.launch {
            mutex.withLock { accounts.upsert(DriveAccountEntity.from(account)) }
            runSync()
        }
    }

    /** Forgets an account that signed in but was never saved as a drive (the user cancelled). */
    fun discardSignIn(signedIn: SignedInAccount) {
        scope.launch {
            mutex.withLock {
                if (accounts.get(signedIn.id) == null) signOut(signedIn.provider, signedIn.id)
            }
        }
    }

    /** Disconnects a drive. Local notes are kept and nothing is deleted from the drive. */
    fun removeDrive(account: DriveAccount) {
        scope.launch {
            mutex.withLock {
                signOut(account.provider, account.id)
                records.clear(account.id)
                withContext(Dispatchers.IO) { baseStore(account.id).clear() }
                accounts.delete(account.id)
                _state.update { s ->
                    s.copy(
                        conflicts = s.conflicts.filterNot { it.driveId == account.id },
                        message = "Disconnected ${account.label}"
                    )
                }
            }
        }
    }

    fun syncIfConnected() {
        scope.launch {
            migrated.await()
            if (accounts.all().isNotEmpty()) runSync()
        }
    }

    fun syncNow() {
        if (_state.value.running) return
        scope.launch { runSync() }
    }

    /** Three-way merge of a conflicted copy into its note, for the merge-back UI. */
    suspend fun mergePreview(conflict: SyncConflict): MergeResult =
        withContext(Dispatchers.IO) { mergeBack.merge(conflict) }

    /** Saves [text] as the note, deletes the conflicted copy, then syncs so every drive receives both changes. */
    fun mergeBack(conflict: SyncConflict, text: String) = settle(conflict, "merge") { mergeBack.apply(conflict, text) }

    /** Deletes the conflicted copy (locally, then on every drive at the next sync). */
    fun discardCopy(
        conflict: SyncConflict
    ) = settle(conflict, "discard the copy of") { mergeBack.discardCopy(conflict) }

    /** Keeps both files as they are and stops listing the conflict. */
    fun keepBoth(conflict: SyncConflict) {
        _state.update { s -> s.copy(conflicts = s.conflicts.filterNot { it.copyPath == conflict.copyPath }) }
    }

    private fun settle(conflict: SyncConflict, verb: String, action: () -> Unit) {
        scope.launch {
            val settled = mutex.withLock {
                try {
                    withContext(Dispatchers.IO) { action() }
                    keepBoth(conflict)
                    onLocalChanged()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(message = "Could not $verb ${conflict.path}: ${describe(e)}") }
                    false
                }
            }
            if (settled) runSync()
        }
    }

    /** Conflicts from before a restart whose copy is still there. */
    private suspend fun restoreConflicts() {
        val saved = withContext(Dispatchers.IO) {
            runCatching { conflictStore.load().filter { mergeBack.isPending(it) } }.getOrDefault(emptyList())
        }
        if (saved.isNotEmpty()) {
            _state.update { s -> s.copy(conflicts = (saved + s.conflicts).distinctBy { it.copyPath }) }
        }
    }

    private suspend fun runSync() {
        migrated.await()
        syncLocked()
    }

    private suspend fun syncLocked(): Unit = mutex.withLock {
        beforeSync()
        val drives = accounts.all().mapNotNull { it.toAccount() }.filter { it.provider in connectors }
        if (drives.isEmpty()) {
            _state.update { it.copy(message = "Add a drive in Settings to sync", conflicts = emptyList()) }
            return@withLock
        }
        _state.update { it.copy(running = true, message = "Syncing…") }
        try {
            val messages = linkedMapOf<String, String>()
            val targets = drives.mapNotNull { account ->
                try {
                    target(account)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    messages[account.id] = describe(e)
                    null
                }
            }
            val result = withContext(Dispatchers.IO) { multiSync.sync(targets) }
            for (outcome in result.outcomes) {
                messages[outcome.driveId] = outcome.result
                    ?.let { (listOf(it.summary) + it.errors).joinToString("\n") }
                    ?: "Sync failed: ${outcome.error}"
            }
            val now = System.currentTimeMillis()
            for ((id, message) in messages) accounts.updateStatus(id, now, message)
            val summary = drives.joinToString("\n") {
                it.label + ": " + messages[it.id].orEmpty().lineSequence().first()
            }
            settings.lastSyncAt = now
            settings.lastSyncSummary = summary
            val pending = withContext(Dispatchers.IO) { _state.value.conflicts.filter { mergeBack.isPending(it) } }
            _state.update {
                it.copy(message = summary, conflicts = (pending + result.conflicts).distinctBy { c -> c.copyPath })
            }
            onLocalChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(message = "Sync failed: ${describe(e)}") }
        } finally {
            _state.update { it.copy(running = false) }
        }
    }

    private suspend fun target(account: DriveAccount): DriveSyncTarget {
        val connector = connector(account.provider)
        connector.unavailableReason?.let { error(it) }
        return DriveSyncTarget(
            driveId = account.id,
            remote = connector.store(account),
            metadata = RoomSyncMetadataStore(records, account.id),
            base = baseStore(account.id)
        )
    }

    private fun baseStore(driveId: String) = FileBaseContentStore(File(context.filesDir, "sync-base/$driveId"))

    private suspend fun signOut(provider: DriveProvider, accountId: String) {
        try {
            connector(provider).signOut(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Signing out is best effort: the local data is removed regardless.
        }
    }

    /** Turns setups that predate drive accounts (v1 synced a single Proton Drive folder) into drive accounts. */
    private suspend fun migrateLegacyAccounts() {
        val known = connectors.values.mapNotNull { it.legacyAccount(settings) }
            .filter { accounts.get(it.id) == null }
        for (account in known) {
            accounts.upsert(
                DriveAccountEntity.from(account, settings.lastSyncAt, settings.lastSyncSummary.ifBlank { null })
            )
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is UserFacingError -> e.userMessage
        else -> e.message ?: e.javaClass.simpleName
    }
}
