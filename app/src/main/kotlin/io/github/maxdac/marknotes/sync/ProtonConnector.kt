package io.github.maxdac.marknotes.sync

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import io.github.maxdac.marknotes.core.auth.ProtonAccountSessionStorage
import io.github.maxdac.marknotes.core.auth.ProtonApiException
import io.github.maxdac.marknotes.core.auth.ProtonAuthClient
import io.github.maxdac.marknotes.core.auth.ProtonSession
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
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Proton Drive, through the Proton Drive SDK (:proton-drive). Sign-in goes through the in-app login
 * screen ([signInScreen]): [signIn] only returns the account that screen just signed in.
 *
 * Several accounts can be connected, each with its own session, refresh lock, SDK clients and SDK cache. Like the
 * Dropbox and OneDrive connectors, every login gets a new local account ID, even for a Proton user that is already
 * connected: the add-drive flow saves the drive under that ID, so reusing an existing one would hand the old drive's
 * sync records to a drive that may sync another folder, and its files would then look deleted.
 *
 * The SDK clients live across syncs and refresh the tokens through their account's [ProtonSessionSource] when the
 * API answers 401, so the stored session stays the only copy of the tokens.
 */
class ProtonConnector(
    private val sessionStorage: ProtonAccountSessionStorage,
    val authClient: ProtonAuthClient,
    /** The store factory of an account, which keeps the SDK cache in [accountCacheDir]. */
    private val factory: (accountId: String) -> RemoteDriveStoreFactory,
    /** The root of the SDK caches ([ProtonDriveStoreFactory.cacheDir]); an account's cache is deleted with it. */
    private val cacheDir: File,
    override val unavailableReason: String? = null,
    private val diagnostics: DiagnosticReporter = DiagnosticReporter.NONE
) : DriveConnector {
    constructor(
        context: Context,
        sessionStorage: ProtonAccountSessionStorage,
        authClient: ProtonAuthClient,
        diagnostics: DiagnosticReporter = DiagnosticReporter.NONE
    ) : this(
        sessionStorage = sessionStorage,
        authClient = authClient,
        factory = { accountId ->
            ProtonDriveStoreFactory(
                context,
                cacheDirectory = accountCacheDir(ProtonDriveStoreFactory.cacheDir(context), accountId),
                diagnostics = diagnostics
            )
        },
        cacheDir = ProtonDriveStoreFactory.cacheDir(context),
        // The SDK ships native libraries for 64-bit ABIs only.
        unavailableReason = if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) "Proton Drive needs a 64-bit device." else null,
        diagnostics = diagnostics
    )

    override val provider = DriveProvider.PROTON

    // Proton's rules for third-party apps: the disclaimer is shown on the login screen and in Settings.
    override val notice = THIRD_PARTY_DISCLAIMER

    override val signInScreen: SignInScreen = { onDone, onBack ->
        LoginScreen(this, onLoggedIn = onDone, onBack = onBack, diagnostics = diagnostics)
    }

    /** Guards [accounts] and [signedInId], and keeps stores from being created while an account is removed. */
    private val storeLock = Mutex()
    private val accounts: MutableMap<String, Account> =
        sessionStorage.loadAll().mapValuesTo(mutableMapOf()) { (id, session) -> Account(id, session) }

    /** The account the login screen saved, until [signIn] returns it. */
    @Volatile
    private var signedInId: String? = null

    override fun needsSignInScreen() = signedInId == null

    /** v1 synced a single Proton Drive folder: its migrated session becomes a drive account. */
    override suspend fun legacyAccount(settings: AppSettings): DriveAccount? = storeLock.withLock {
        val session = accounts[ACCOUNT_ID]?.current ?: return@withLock null
        DriveAccount(
            id = ACCOUNT_ID,
            provider = provider,
            label = session.username.ifBlank { provider.displayName },
            remoteFolder = settings.legacyRemoteFolder,
            createRootFolder = false
        )
    }

    /** Saves [session] as a new account, returned by the next [signIn]. */
    suspend fun onLoggedIn(session: ProtonSession) = storeLock.withLock {
        // A login that never reached signIn (the add-drive screen was left) would keep its session forever.
        signedInId?.let { abandoned ->
            try {
                removeAccount(abandoned)
            } catch (e: IOException) {
                diagnostics.report(DiagnosticCode.SDK_ERROR, e)
            }
        }
        val id = "proton-${UUID.randomUUID()}"
        sessionStorage.save(id, session)
        accounts[id] = Account(id, session)
        signedInId = id
    }

    override suspend fun signIn(activity: ComponentActivity): SignedInAccount? = takeSignedInAccount()

    internal suspend fun takeSignedInAccount(): SignedInAccount? = storeLock.withLock {
        val id = signedInId ?: return@withLock null
        signedInId = null
        val session = accounts[id]?.current ?: return@withLock null
        SignedInAccount(id, provider, session.username.ifBlank { provider.displayName })
    }

    override suspend fun folderBrowser(accountId: String): RemoteFolderBrowser? = null

    override suspend fun store(account: DriveAccount): RemoteDriveStore = storeLock.withLock {
        unavailableReason?.let { throw RemoteUnavailableException(it) }
        val owner = accounts[account.id] ?: throw RemoteUnavailableException(LOGIN_AGAIN)
        owner.refreshLock.withLock {
            if (owner.cached?.first != account.syncFolder) owner.retire()
        }
        // Closed here rather than when retired: a store may be retired from inside one of its own requests.
        val closed = owner.closeRetired()
        if (owner.current == null) signedOut(account.id, closed)
        owner.cached?.second?.let { return@withLock it }
        if (account.id == ACCOUNT_ID) deleteLegacyCache()
        val created = factory(account.id).create(owner, account.syncFolder)
        // Created without the refresh lock, which the SDK may need meanwhile: the session may be gone now.
        val revoked = owner.refreshLock.withLock {
            owner.cached = account.syncFolder to created
            if (owner.current == null) owner.retire()
            owner.current == null
        }
        if (revoked) signedOut(account.id, owner.closeRetired())
        created
    }

    /** Deletes the cache of an account whose session is gone, unless one of its clients could not be closed. */
    private suspend fun signedOut(id: String, closed: Boolean): Nothing {
        if (closed) {
            try {
                deleteCache(id)
            } catch (e: IOException) {
                diagnostics.report(DiagnosticCode.SDK_ERROR, e)
            }
        }
        throw RemoteUnavailableException(LOGIN_AGAIN)
    }

    override suspend fun signOut(accountId: String) = storeLock.withLock {
        removeAccount(accountId)
    }

    /** Every drive was signed out already: this removes the logins never saved as drives, and the whole cache. */
    override suspend fun deleteLocalData() = storeLock.withLock {
        val failures = mutableListOf<Exception>()
        for (id in accounts.keys.toList()) {
            try {
                removeAccount(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += e
            }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
        withContext(Dispatchers.IO) {
            if (!cacheDir.deleteRecursively() && cacheDir.exists()) {
                throw IOException("Could not delete the Proton Drive cache")
            }
        }
    }

    /**
     * Revokes the session of [id] (best effort), forgets it and deletes its SDK cache once its clients are closed.
     * When a client can't be closed the cache is kept, and deleted by the next sign-out or local data deletion.
     */
    private suspend fun removeAccount(id: String) {
        val account = accounts[id]
        account?.refreshLock?.withLock {
            // A refresh in flight finishes first, so the rotated session is the one revoked.
            account.current?.let { revoke(it) }
            account.clear()
        }
        sessionStorage.remove(id)
        accounts.remove(id)
        if (signedInId == id) signedInId = null
        if (account?.closeRetired() == false) throw IOException("Could not close the Proton Drive cache")
        deleteCache(id)
    }

    private suspend fun revoke(session: ProtonSession) {
        try {
            authClient.logout(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Dropped locally anyway; the server expires it on its own.
            diagnostics.report(DiagnosticCode.SIGN_OUT, e)
        }
    }

    private suspend fun deleteCache(id: String) = withContext(Dispatchers.IO) {
        val directory = accountCacheDir(cacheDir, id)
        if (!directory.deleteRecursively() && directory.exists()) {
            throw IOException("Could not delete the Proton Drive cache")
        }
        if (id == ACCOUNT_ID) deleteLegacyCacheFiles()
    }

    /** Before multiple accounts, the SDK database lived in the root: no client uses it anymore. */
    private suspend fun deleteLegacyCache() = withContext(Dispatchers.IO) {
        try {
            deleteLegacyCacheFiles()
        } catch (e: IOException) {
            diagnostics.report(DiagnosticCode.SDK_ERROR, e)
        }
    }

    private fun deleteLegacyCacheFiles() {
        LEGACY_CACHE_FILES.forEach { name ->
            val file = cacheDir.resolve(name)
            if (!file.delete() && file.exists()) throw IOException("Could not delete the Proton Drive cache")
        }
    }

    /** One account's session: the [ProtonSessionSource] of its SDK clients. */
    private inner class Account(val id: String, session: ProtonSession) : ProtonSessionSource {
        val refreshLock = Mutex()

        @Volatile
        override var current: ProtonSession? = session

        /** The store for a sync folder, set and retired under [refreshLock]. */
        @Volatile
        var cached: Pair<String, RemoteDriveStore>? = null

        /** Stores to close on the next [closeRetired]: they may be dropped from inside one of their requests. */
        private val retired = mutableListOf<RemoteDriveStore>()

        /** Refreshes the tokens unless another caller already did, dropping the session when they're revoked. */
        override suspend fun refresh(staleAccessToken: String): ProtonSession? = refreshLock.withLock {
            val session = current ?: return@withLock null
            if (session.accessToken != staleAccessToken) return@withLock session
            try {
                authClient.refresh(session).also {
                    sessionStorage.save(id, it)
                    current = it
                }
            } catch (e: ProtonApiException) {
                // Proton answers 400/401/422 for an invalid refresh token; other errors (e.g. 429) are transient.
                if (e.httpStatus == 400 || e.httpStatus == 401 || e.httpStatus == 422) {
                    clear()
                    throw RemoteUnavailableException(SESSION_EXPIRED, e)
                }
                throw RemoteUnavailableException(e.userMessage, e)
            }
        }

        override suspend fun invalidate() = refreshLock.withLock { clear() }

        /** Forgets the session; called with [refreshLock] held. */
        fun clear() {
            if (current == null) return
            sessionStorage.remove(id)
            current = null
            retire()
        }

        fun retire() {
            cached?.second?.let { store -> synchronized(retired) { retired += store } }
            cached = null
        }

        /** Closes the retired stores, each attempted once. Returns false when one failed to close. */
        fun closeRetired(): Boolean {
            val stores = synchronized(retired) { retired.toList().also { retired.clear() } }
            var closed = true
            stores.forEach { store ->
                try {
                    (store as? AutoCloseable)?.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    diagnostics.report(DiagnosticCode.SDK_ERROR, e)
                    closed = false
                }
            }
            return closed
        }
    }

    companion object {
        const val ACCOUNT_ID = MarkNotesDatabase.LEGACY_PROTON_ID
        private const val LOGIN_AGAIN = "Log in to Proton Drive again"
        private const val SESSION_EXPIRED = "Proton session expired, please log in again"
        private val LEGACY_CACHE_FILES = listOf("cache.sqlite", "cache.sqlite-wal", "cache.sqlite-shm")

        /** An account's SDK cache directory, named by a hash so account IDs never form paths. */
        internal fun accountCacheDir(root: File, id: String): File {
            val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return root.resolve(hash)
        }
    }
}
