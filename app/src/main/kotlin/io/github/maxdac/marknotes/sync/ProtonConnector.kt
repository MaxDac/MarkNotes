package io.github.maxdac.marknotes.sync

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import io.github.maxdac.marknotes.core.auth.ProtonApiException
import io.github.maxdac.marknotes.core.auth.ProtonAuthClient
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.auth.SessionStorage
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.ProtonSessionSource
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteDriveStoreFactory
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import io.github.maxdac.marknotes.data.MarkNotesDatabase
import io.github.maxdac.marknotes.proton.ProtonDriveStoreFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Proton Drive, through the Proton Drive SDK (:proton-drive). Sign-in goes through the in-app login
 * screen: [signIn] only returns the account of the session that screen saved. A single Proton account
 * is supported.
 *
 * The SDK client lives across syncs and refreshes the tokens through this [ProtonSessionSource] when the
 * API answers 401, so the stored session stays the only copy of the tokens.
 */
class ProtonConnector(
    context: Context,
    private val sessionStorage: SessionStorage,
    private val authClient: ProtonAuthClient,
    private val factory: RemoteDriveStoreFactory = ProtonDriveStoreFactory(context)
) : DriveConnector, ProtonSessionSource {
    override val provider = DriveProvider.PROTON

    private val storeLock = Mutex()
    private val refreshLock = Mutex()

    @Volatile
    private var cached: Pair<String, RemoteDriveStore>? = null

    /** Replaced stores, closed on the next [store] call: they may be dropped from inside one of their requests. */
    private val retired = mutableListOf<RemoteDriveStore>()

    private val _session = MutableStateFlow(sessionStorage.load())
    val session: StateFlow<ProtonSession?> = _session

    // The SDK ships native libraries for 64-bit ABIs only.
    override val unavailableReason: String? =
        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) "Proton Drive needs a 64-bit device." else null

    override val current: ProtonSession?
        get() = _session.value

    fun onLoggedIn(session: ProtonSession) {
        sessionStorage.save(session)
        _session.value = session
        retireCachedStore()
    }

    override suspend fun signIn(activity: ComponentActivity): SignedInAccount? {
        val session = _session.value ?: return null
        return SignedInAccount(ACCOUNT_ID, provider, session.username.ifBlank { provider.displayName })
    }

    override suspend fun folderBrowser(accountId: String): RemoteFolderBrowser? = null

    override suspend fun store(account: DriveAccount): RemoteDriveStore = storeLock.withLock {
        unavailableReason?.let { throw RemoteUnavailableException(it) }
        val session = _session.value ?: throw RemoteUnavailableException(LOGIN_AGAIN)
        val key = session.uid + "|" + account.syncFolder
        val existing = cached?.takeIf { it.first == key }?.second
        if (existing == null) retireCachedStore()
        closeRetiredStores()
        existing ?: factory.create(this, account.syncFolder).also { cached = key to it }
    }

    override suspend fun signOut(accountId: String) {
        val session = _session.value ?: return
        try {
            authClient.logout(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The local session is dropped regardless.
        }
        clearSession()
    }

    /** Refreshes the tokens unless another caller already did, dropping the session when they're revoked. */
    override suspend fun refresh(staleAccessToken: String): ProtonSession? = refreshLock.withLock {
        val session = _session.value ?: return@withLock null
        if (session.accessToken != staleAccessToken) return@withLock session
        try {
            authClient.refresh(session).also {
                sessionStorage.save(it)
                _session.value = it
            }
        } catch (e: ProtonApiException) {
            // Proton answers 400/401/422 for an invalid refresh token; other errors (e.g. 429) are transient.
            if (e.httpStatus == 400 || e.httpStatus == 401 || e.httpStatus == 422) {
                clearSession()
                throw RemoteUnavailableException(SESSION_EXPIRED, e)
            }
            throw RemoteUnavailableException(e.userMessage, e)
        }
    }

    override suspend fun invalidate() {
        clearSession()
    }

    private fun clearSession() {
        sessionStorage.clear()
        _session.value = null
        retireCachedStore()
    }

    private fun retireCachedStore() {
        val store = cached?.second ?: return
        cached = null
        synchronized(retired) { retired += store }
    }

    private fun closeRetiredStores() {
        val stores = synchronized(retired) { retired.toList().also { retired.clear() } }
        stores.forEach { store -> (store as? AutoCloseable)?.let { runCatching { it.close() } } }
    }

    companion object {
        const val ACCOUNT_ID = MarkNotesDatabase.LEGACY_PROTON_ID
        private const val LOGIN_AGAIN = "Log in to Proton Drive again"
        private const val SESSION_EXPIRED = "Proton session expired, please log in again"
    }
}
