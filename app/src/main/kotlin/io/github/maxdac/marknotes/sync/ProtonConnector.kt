package io.github.maxdac.marknotes.sync

import android.content.Context
import androidx.activity.ComponentActivity
import io.github.maxdac.marknotes.core.auth.ProtonApiException
import io.github.maxdac.marknotes.core.auth.ProtonAuthClient
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.auth.SessionStorage
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteDriveStoreFactory
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import io.github.maxdac.marknotes.data.MarkNotesDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Proton Drive, through the optional SDK bridge loaded by reflection. Sign-in goes through the in-app
 * login screen: [signIn] only returns the account of the session that screen saved. A single Proton
 * account is supported.
 */
class ProtonConnector(
    private val context: Context,
    private val sessionStorage: SessionStorage,
    private val authClient: ProtonAuthClient
) : DriveConnector {
    override val provider = DriveProvider.PROTON

    private val factory: RemoteDriveStoreFactory? by lazy { loadFactory() }
    private var cached: Pair<String, RemoteDriveStore>? = null

    private val _session = MutableStateFlow(sessionStorage.load())
    val session: StateFlow<ProtonSession?> = _session

    override val unavailableReason: String?
        get() = if (factory == null) {
            "Proton Drive SDK not included in this build. See the README to build with -Pmarknotes.protonSdk=true."
        } else {
            null
        }

    fun onLoggedIn(session: ProtonSession) {
        sessionStorage.save(session)
        cached = null
        _session.value = session
    }

    override suspend fun signIn(activity: ComponentActivity): SignedInAccount? {
        val session = _session.value ?: return null
        return SignedInAccount(ACCOUNT_ID, provider, session.username.ifBlank { provider.displayName })
    }

    override suspend fun folderBrowser(accountId: String): RemoteFolderBrowser? = null

    override suspend fun store(account: DriveAccount): RemoteDriveStore {
        val factory = factory ?: throw RemoteUnavailableException(unavailableReason.orEmpty())
        val session = refreshSession()
        val key = session.accessToken + "|" + account.syncFolder
        cached?.takeIf { it.first == key }?.let { return it.second }
        return factory.create(session, account.syncFolder).also { cached = key to it }
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
        sessionStorage.clear()
        cached = null
        _session.value = null
    }

    /** Refreshes the access token, dropping the session when the refresh token is no longer valid. */
    private suspend fun refreshSession(): ProtonSession {
        val session = _session.value ?: throw RemoteUnavailableException("Log in to Proton Drive again")
        return try {
            authClient.refresh(session).also {
                sessionStorage.save(it)
                _session.value = it
            }
        } catch (e: ProtonApiException) {
            // Proton answers 400/401/422 for an invalid refresh token; other errors (e.g. 429) are transient.
            val expired = e.httpStatus == 400 || e.httpStatus == 401 || e.httpStatus == 422
            if (expired) {
                sessionStorage.clear()
                cached = null
                _session.value = null
            }
            val message = if (expired) "Proton session expired, please log in again" else e.userMessage
            throw RemoteUnavailableException(message, e)
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

    companion object {
        const val ACCOUNT_ID = MarkNotesDatabase.LEGACY_PROTON_ID
        private const val FACTORY_CLASS = "io.github.maxdac.marknotes.proton.ProtonDriveStoreFactory"
    }
}
