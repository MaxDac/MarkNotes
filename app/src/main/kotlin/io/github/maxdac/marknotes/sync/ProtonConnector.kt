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

/** Each account owns its session source, refresh mutex, SDK stores and cache directory. */
class ProtonConnector(
    private val sessionStorage: ProtonAccountSessionStorage,
    val authClient: ProtonAuthClient,
    private val factory: (String) -> RemoteDriveStoreFactory,
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
                diagnostics = diagnostics,
                cacheDirectory = accountCacheDir(ProtonDriveStoreFactory.cacheDir(context), accountId)
            )
        },
        cacheDir = ProtonDriveStoreFactory.cacheDir(context),
        unavailableReason = if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) "Proton Drive needs a 64-bit device." else null,
        diagnostics = diagnostics
    )

    override val provider = DriveProvider.PROTON
    override val notice = THIRD_PARTY_DISCLAIMER
    override val signInScreen: SignInScreen = { onDone, onBack ->
        LoginScreen(this, onLoggedIn = onDone, onBack = onBack, diagnostics = diagnostics)
    }

    private val storeLock = Mutex()
    private val accounts = run {
        val sessions = sessionStorage.loadAll()
        val userIds = sessionStorage.loadUserIds()
        (sessions.keys + userIds.keys).associateWith { id ->
            Account(id, sessions[id], userIds[id] ?: sessions[id]?.userId.orEmpty())
        }.toMutableMap()
    }

    @Volatile
    private var signedInId: String? = null

    // A completed login is consumed once by AddDriveViewModel when it returns from the login screen.
    override fun needsSignInScreen() = signedInId == null

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

    suspend fun onLoggedIn(session: ProtonSession) = storeLock.withLock {
        val existing = accounts.values.firstOrNull {
            if (session.userId.isNotBlank() && it.userId.isNotBlank()) {
                it.userId == session.userId
            } else {
                session.username.isNotBlank() && it.username.equals(session.username, ignoreCase = true)
            }
        }
        val id = existing?.id ?: "proton-${UUID.randomUUID()}"
        existing?.refreshLock?.withLock {
            existing.retire()
            existing.current = null
        }
        existing?.closeRetired()
        deleteCache(id)
        sessionStorage.save(id, session)
        accounts[id] = Account(id, session, session.userId)
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
        owner.closeRetired()
        if (owner.current == null) {
            deleteCache(account.id)
            throw RemoteUnavailableException(LOGIN_AGAIN)
        }
        owner.cached?.second ?: factory(account.id).create(owner, account.syncFolder).also { created ->
            owner.refreshLock.withLock {
                owner.cached = account.syncFolder to created
                if (owner.current == null) owner.retire()
            }
            if (owner.current == null) {
                owner.closeRetired()
                deleteCache(account.id)
                throw RemoteUnavailableException(LOGIN_AGAIN)
            }
        }
    }

    override suspend fun signOut(accountId: String) = storeLock.withLock {
        removeAccount(accountId)
    }

    override suspend fun deleteLocalData() = storeLock.withLock {
        accounts.keys.toList().forEach { removeAccount(it) }
        withContext(Dispatchers.IO) {
            if (!cacheDir.deleteRecursively() && cacheDir.exists()) {
                throw IOException("Could not delete the Proton Drive cache")
            }
        }
    }

    private suspend fun removeAccount(id: String) {
        val account = accounts[id]
        account?.refreshLock?.withLock {
            account.current?.let { session ->
                try {
                    authClient.logout(session)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    diagnostics.report(DiagnosticCode.SIGN_OUT, e)
                }
            }
            account.clear()
        }
        account?.closeRetired()
        sessionStorage.remove(id)
        accounts.remove(id)
        if (signedInId == id) signedInId = null
        deleteCache(id)
    }

    private suspend fun deleteCache(id: String) = withContext(Dispatchers.IO) {
        val directory = accountCacheDir(cacheDir, id)
        if (!directory.deleteRecursively() && directory.exists()) {
            throw IOException("Could not delete the Proton Drive cache")
        }
        // Before multiple accounts, the SDK database lived directly in the root.
        if (id == ACCOUNT_ID) {
            listOf("cache.sqlite", "cache.sqlite-wal", "cache.sqlite-shm").forEach { name ->
                val file = cacheDir.resolve(name)
                if (!file.delete() && file.exists()) throw IOException("Could not delete the Proton Drive cache")
            }
        }
    }

    private inner class Account(
        val id: String,
        session: ProtonSession?,
        val userId: String
    ) : ProtonSessionSource {
        val refreshLock = Mutex()
        val username = session?.username.orEmpty()

        @Volatile
        override var current: ProtonSession? = session

        @Volatile
        var cached: Pair<String, RemoteDriveStore>? = null
        private val retired = mutableListOf<RemoteDriveStore>()

        override suspend fun refresh(staleAccessToken: String): ProtonSession? = refreshLock.withLock {
            val session = current ?: return@withLock null
            if (session.accessToken != staleAccessToken) return@withLock session
            try {
                authClient.refresh(session).also {
                    sessionStorage.save(id, it)
                    current = it
                }
            } catch (e: ProtonApiException) {
                if (e.httpStatus == 400 || e.httpStatus == 401 || e.httpStatus == 422) {
                    clear()
                    throw RemoteUnavailableException(SESSION_EXPIRED, e)
                }
                throw RemoteUnavailableException(e.userMessage, e)
            }
        }

        override suspend fun invalidate() = refreshLock.withLock { clear() }

        fun clear() {
            if (current == null) return
            sessionStorage.invalidate(id)
            current = null
            retire()
        }

        fun retire() {
            cached?.second?.let { store -> synchronized(retired) { retired += store } }
            cached = null
        }

        // Never close an SDK client from inside its own refresh/invalidate callback.
        fun closeRetired() {
            val stores = synchronized(retired) { retired.toList() }
            stores.forEach { store ->
                (store as? AutoCloseable)?.let {
                    try {
                        it.close()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        diagnostics.report(DiagnosticCode.SDK_ERROR, e)
                        throw IOException("Could not close the Proton Drive cache", e)
                    }
                }
                synchronized(retired) { retired.remove(store) }
            }
        }
    }

    companion object {
        const val ACCOUNT_ID = MarkNotesDatabase.LEGACY_PROTON_ID
        private const val LOGIN_AGAIN = "Log in to Proton Drive again"
        private const val SESSION_EXPIRED = "Proton session expired, please log in again"

        internal fun accountCacheDir(root: File, id: String): File {
            val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return root.resolve(hash)
        }
    }
}
