package io.github.maxdac.marknotes.sync

import android.content.Context
import io.github.maxdac.marknotes.core.auth.ProtonApiException
import io.github.maxdac.marknotes.core.auth.ProtonAuthClient
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.auth.SessionStorage
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.sync.ConflictResolution
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteDriveStoreFactory
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import io.github.maxdac.marknotes.core.sync.SyncConflict
import io.github.maxdac.marknotes.core.sync.SyncEngine
import io.github.maxdac.marknotes.core.sync.SyncMetadataStore
import io.github.maxdac.marknotes.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncState(
    val session: ProtonSession? = null,
    val running: Boolean = false,
    val message: String? = null,
    val lastSyncAt: Long = 0L,
    val conflicts: List<SyncConflict> = emptyList(),
) {
    val loggedIn: Boolean get() = session != null
}

/**
 * Runs the two-way sync with Proton Drive. Sync is only triggered on app launch and on demand:
 * no background or periodic polling.
 */
class SyncManager(
    private val context: Context,
    private val notes: LocalNoteStore,
    private val metadata: SyncMetadataStore,
    private val sessionStorage: SessionStorage,
    private val authClient: ProtonAuthClient,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val onLocalChanged: () -> Unit,
) {
    private val mutex = Mutex()
    private var cachedRemote: Pair<String, RemoteDriveStore>? = null

    private val _state = MutableStateFlow(
        SyncState(
            session = sessionStorage.load(),
            lastSyncAt = settings.lastSyncAt,
            message = settings.lastSyncSummary.ifBlank { null },
        ),
    )
    val state: StateFlow<SyncState> = _state

    /** Whether the optional Proton Drive SDK bridge is part of this build. */
    val sdkAvailable: Boolean by lazy { loadFactory() != null }

    fun onLoggedIn(session: ProtonSession) {
        sessionStorage.save(session)
        cachedRemote = null
        _state.update { it.copy(session = session, message = null) }
    }

    fun logout() {
        val session = _state.value.session ?: return
        scope.launch {
            mutex.withLock {
                try {
                    authClient.logout(session)
                } catch (e: Exception) {
                    // The local session is dropped regardless.
                }
                sessionStorage.clear()
                metadata.clear()
                cachedRemote = null
                _state.update { SyncState(message = "Logged out") }
            }
        }
    }

    fun changeRemoteFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == settings.remoteFolder) return
        scope.launch {
            mutex.withLock {
                settings.remoteFolder = trimmed
                // A different folder means the old sync history no longer applies.
                metadata.clear()
                cachedRemote = null
            }
        }
    }

    fun syncIfLoggedIn() {
        if (_state.value.loggedIn) syncNow()
    }

    fun syncNow() {
        if (_state.value.running) return
        scope.launch { runSync() }
    }

    fun resolve(conflict: SyncConflict, resolution: ConflictResolution) {
        scope.launch {
            mutex.withLock {
                _state.update { it.copy(running = true) }
                try {
                    val remote = remoteStore() ?: return@withLock
                    withContext(Dispatchers.IO) {
                        SyncEngine(notes, remote, metadata).resolve(conflict, resolution)
                    }
                    _state.update { s ->
                        s.copy(conflicts = s.conflicts.filterNot { it.path == conflict.path })
                    }
                    onLocalChanged()
                } catch (e: Exception) {
                    _state.update { it.copy(message = "Could not resolve ${conflict.path}: ${describe(e)}") }
                } finally {
                    _state.update { it.copy(running = false) }
                }
            }
        }
    }

    private suspend fun runSync(): Unit = mutex.withLock {
        if (_state.value.session == null) {
            _state.update { it.copy(message = "Log in to Proton Drive to sync") }
            return@withLock
        }
        _state.update { it.copy(running = true, message = "Syncing…") }
        try {
            refreshSession() ?: return@withLock
            val remote = remoteStore() ?: return@withLock
            val result = withContext(Dispatchers.IO) { SyncEngine(notes, remote, metadata).sync() }
            val now = System.currentTimeMillis()
            val summary = buildString {
                append(result.summary)
                if (result.errors.isNotEmpty()) append("\n").append(result.errors.joinToString("\n"))
            }
            settings.lastSyncAt = now
            settings.lastSyncSummary = summary
            _state.update { it.copy(message = summary, lastSyncAt = now, conflicts = result.conflicts) }
            onLocalChanged()
        } catch (e: Exception) {
            _state.update { it.copy(message = "Sync failed: ${describe(e)}") }
        } finally {
            _state.update { it.copy(running = false) }
        }
    }

    /** Refreshes the access token, logging out when the refresh token is no longer valid. */
    private suspend fun refreshSession(): ProtonSession? {
        val session = _state.value.session ?: return null
        return try {
            val refreshed = authClient.refresh(session)
            sessionStorage.save(refreshed)
            _state.update { it.copy(session = refreshed) }
            if (refreshed.accessToken != session.accessToken) cachedRemote = null
            refreshed
        } catch (e: ProtonApiException) {
            // Proton answers 400/401/422 for an invalid refresh token; other errors (e.g. 429) are transient.
            if (e.httpStatus == 400 || e.httpStatus == 401 || e.httpStatus == 422) {
                sessionStorage.clear()
                cachedRemote = null
                _state.update { it.copy(session = null, message = "Session expired, please log in again") }
                null
            } else {
                throw e
            }
        }
    }

    private suspend fun remoteStore(): RemoteDriveStore? {
        val session = _state.value.session ?: return null
        val key = session.accessToken + "|" + settings.remoteFolder
        cachedRemote?.takeIf { it.first == key }?.let { return it.second }
        val factory = loadFactory()
        if (factory == null) {
            _state.update {
                it.copy(
                    message = "Proton Drive SDK not included in this build. " +
                        "See the README to build with -Pmarknotes.protonSdk=true.",
                )
            }
            return null
        }
        return try {
            factory.create(session, settings.remoteFolder).also { cachedRemote = key to it }
        } catch (e: RemoteUnavailableException) {
            _state.update { it.copy(message = e.message) }
            null
        }
    }

    private fun loadFactory(): RemoteDriveStoreFactory? = try {
        Class.forName(FACTORY_CLASS)
            .getConstructor(Context::class.java)
            .newInstance(context) as RemoteDriveStoreFactory
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: LinkageError) {
        null
    }

    private fun describe(e: Exception): String = when (e) {
        is ProtonApiException -> e.userMessage
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val FACTORY_CLASS = "io.github.maxdac.marknotes.proton.ProtonDriveStoreFactory"
    }
}
