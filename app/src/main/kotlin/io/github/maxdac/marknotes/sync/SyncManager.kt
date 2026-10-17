package io.github.maxdac.marknotes.sync

import android.content.Context
import io.github.maxdac.marknotes.core.auth.ProtonApiException
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.sync.ConflictResolution
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.DriveSyncTarget
import io.github.maxdac.marknotes.core.sync.FileBaseContentStore
import io.github.maxdac.marknotes.core.sync.MultiDriveSync
import io.github.maxdac.marknotes.core.sync.SyncConflict
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.DriveAccountDao
import io.github.maxdac.marknotes.data.DriveAccountEntity
import io.github.maxdac.marknotes.data.RoomSyncMetadataStore
import io.github.maxdac.marknotes.data.SyncRecordDao
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * demand: no background or periodic polling.
 */
class SyncManager(
    private val context: Context,
    notes: LocalNoteStore,
    private val records: SyncRecordDao,
    private val accounts: DriveAccountDao,
    val proton: ProtonConnector,
    private val connectors: Map<DriveProvider, DriveConnector>,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val onLocalChanged: () -> Unit
) {
    private val mutex = Mutex()
    private val multiSync = MultiDriveSync(notes, settings.deviceId)
    private val migrated = CompletableDeferred<Unit>()

    private val _state = MutableStateFlow(SyncState(message = settings.lastSyncSummary.ifBlank { null }))
    val state: StateFlow<SyncState> = _state

    init {
        scope.launch {
            try {
                migrateLegacyProton()
            } finally {
                migrated.complete(Unit)
            }
            accounts.observe().collect { entities ->
                val drives = entities.mapNotNull { entity ->
                    entity.toAccount()?.let {
                        DriveStatus(it, entity.lastSyncAt, entity.lastMessage, connector(it.provider).unavailableReason)
                    }
                }
                _state.update { it.copy(drives = drives) }
            }
        }
    }

    fun connector(provider: DriveProvider): DriveConnector = connectors.getValue(provider)

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

    /** Applies [resolution] on the conflict's drive, then syncs again so the other drives receive it. */
    fun resolve(conflict: SyncConflict, resolution: ConflictResolution) {
        scope.launch {
            val resolved = mutex.withLock {
                _state.update { it.copy(running = true) }
                try {
                    val account = accounts.get(conflict.driveId)?.toAccount()
                        ?: error("The drive of this conflict is no longer connected")
                    val target = target(account)
                    withContext(Dispatchers.IO) { multiSync.engine(target).resolve(conflict, resolution) }
                    _state.update { s -> s.copy(conflicts = s.conflicts.filterNot { it === conflict }) }
                    onLocalChanged()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(message = "Could not resolve ${conflict.path}: ${describe(e)}") }
                    false
                } finally {
                    _state.update { it.copy(running = false) }
                }
            }
            if (resolved) runSync()
        }
    }

    private suspend fun runSync(): Unit = mutex.withLock {
        val drives = accounts.all().mapNotNull { it.toAccount() }
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
            _state.update { it.copy(message = summary, conflicts = result.conflicts) }
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

    /** v1 synced a single Proton Drive folder: turn a saved Proton session into a drive account. */
    private suspend fun migrateLegacyProton() {
        val session = proton.session.value ?: return
        if (accounts.get(ProtonConnector.ACCOUNT_ID) != null) return
        val account = DriveAccount(
            id = ProtonConnector.ACCOUNT_ID,
            provider = DriveProvider.PROTON,
            label = session.username.ifBlank { DriveProvider.PROTON.displayName },
            remoteFolder = settings.legacyRemoteFolder,
            createRootFolder = false
        )
        accounts.upsert(
            DriveAccountEntity.from(
                account,
                settings.lastSyncAt,
                settings.lastSyncSummary.ifBlank {
                    null
                }
            )
        )
    }

    private fun describe(e: Exception): String = when (e) {
        is ProtonApiException -> e.userMessage
        else -> e.message ?: e.javaClass.simpleName
    }
}
