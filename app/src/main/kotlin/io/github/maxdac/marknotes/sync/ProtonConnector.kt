package io.github.maxdac.marknotes.sync

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import io.github.maxdac.marknotes.core.auth.ProtonApiException
import io.github.maxdac.marknotes.core.auth.ProtonAuthClient
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.auth.SessionStorage
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.ProtonSessionSource
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteDriveStoreFactory
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.MarkNotesDatabase
import io.github.maxdac.marknotes.proton.ProtonDriveStoreFactory
import io.github.maxdac.marknotes.ui.login.LoginScreen
import io.github.maxdac.marknotes.ui.login.THIRD_PARTY_DISCLAIMER
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Proton Drive, through the Proton Drive SDK (:proton-drive). Sign-in goes through the in-app login
 * screen ([signInScreen]): [signIn] only returns the account of the session that screen saved. A single Proton
 * account is supported.
 *
 * The SDK client lives across syncs and refreshes the tokens through this [ProtonSessionSource] when the
 * API answers 401, so the stored session stays the only copy of the tokens.
 */
class ProtonConnector(
    private val sessionStorage: SessionStorage,
    val authClient: ProtonAuthClient,
    private val factory: RemoteDriveStoreFactory,
    /** The SDK's cache ([ProtonDriveStoreFactory.cacheDir]), deleted with the session. */
    private val cacheDir: File,
    override val unavailableReason: String? = null,
    private val diagnostics: DiagnosticReporter = DiagnosticReporter.NONE
) : DriveConnector, ProtonSessionSource {
    constructor(
        context: Context,
        sessionStorage: SessionStorage,
        authClient: ProtonAuthClient,
        diagnostics: DiagnosticReporter = DiagnosticReporter.NONE
    ) : this(
        sessionStorage = sessionStorage,
        authClient = authClient,
        factory = ProtonDriveStoreFactory(context, diagnostics = diagnostics),
        cacheDir = ProtonDriveStoreFactory.cacheDir(context),
        // The SDK ships native libraries for 64-bit ABIs only.
        unavailableReason = if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) "Proton Drive needs a 64-bit device." else null,
        diagnostics = diagnostics
    )

    override val provider = DriveProvider.PROTON

    private val storeLock = Mutex()
    private val refreshLock = Mutex()

    @Volatile
    private var cached: Pair<String, RemoteDriveStore>? = null

    /** Replaced stores, closed on the next [store] call: they may be dropped from inside one of their requests. */
    private val retired = mutableListOf<RemoteDriveStore>()

    private val _session = MutableStateFlow(sessionStorage.load())
    val session: StateFlow<ProtonSession?> = _session

    override val current: ProtonSession?
        get() = _session.value

    override val singleAccount = true

    // Proton's rules for third-party apps: the disclaimer is shown on the login screen and in Settings.
    override val notice = THIRD_PARTY_DISCLAIMER

    override val signInScreen: SignInScreen = { onDone, onBack ->
        LoginScreen(this, onLoggedIn = onDone, onBack = onBack, diagnostics = diagnostics)
    }

    override fun needsSignInScreen() = _session.value == null

    /** v1 synced a single Proton Drive folder: a saved Proton session becomes a drive account. */
    override suspend fun legacyAccount(settings: AppSettings): DriveAccount? {
        val session = _session.value ?: return null
        return DriveAccount(
            id = ACCOUNT_ID,
            provider = provider,
            label = session.username.ifBlank { provider.displayName },
            remoteFolder = settings.legacyRemoteFolder,
            createRootFolder = false
        )
    }

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
        logOut()
        deleteSessionData()
    }

    /** Same as [signOut], but fails when the SDK cache could not be deleted. */
    override suspend fun deleteLocalData() {
        logOut()
        if (!deleteSessionData()) throw IOException("Could not delete the Proton Drive cache")
    }

    /** Revokes the session on Proton's side, best effort: the local session is dropped regardless. */
    private suspend fun logOut() {
        val session = _session.value ?: return
        try {
            authClient.logout(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Dropped locally anyway; the server expires it on its own.
            diagnostics.report(DiagnosticCode.SIGN_OUT, e)
        }
    }

    /**
     * Forgets the session, closes the SDK clients and deletes their cache. Holds [storeLock] so no store is created
     * meanwhile, and closes the stores first so the SDK no longer uses the cache database. Returns whether the cache
     * is gone.
     */
    private suspend fun deleteSessionData(): Boolean {
        clearSession()
        return storeLock.withLock {
            closeRetiredStores()
            withContext(Dispatchers.IO) { cacheDir.deleteRecursively() || !cacheDir.exists() }
        }
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
        stores.forEach { store ->
            (store as? AutoCloseable)?.let {
                runCatching { it.close() }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    diagnostics.report(DiagnosticCode.SDK_ERROR, failure)
                }
            }
        }
    }

    companion object {
        const val ACCOUNT_ID = MarkNotesDatabase.LEGACY_PROTON_ID
        private const val LOGIN_AGAIN = "Log in to Proton Drive again"
        private const val SESSION_EXPIRED = "Proton session expired, please log in again"
    }
}
