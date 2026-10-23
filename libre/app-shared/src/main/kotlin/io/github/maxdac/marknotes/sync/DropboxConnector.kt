package io.github.maxdac.marknotes.sync

import androidx.activity.ComponentActivity
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import io.github.maxdac.marknotes.auth.CredentialStore
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dropbox through the official SDK. Sign-in uses OAuth with PKCE; the refresh token is stored encrypted. */
class DropboxConnector(private val credentials: CredentialStore, private val appKey: String) : DriveConnector {
    override val provider = DriveProvider.DROPBOX

    private val config = DbxRequestConfig.newBuilder("MarkNotes").build()
    private var pendingSignIn: CompletableDeferred<DbxCredential?>? = null
    private val stores = HashMap<String, Pair<String, DropboxDriveStore>>()

    override val unavailableReason: String?
        get() = if (appKey == UNCONFIGURED) {
            "No Dropbox app key in this build. See the README to set marknotes.dropboxAppKey."
        } else {
            null
        }

    override suspend fun signIn(activity: ComponentActivity): SignedInAccount? {
        val deferred = CompletableDeferred<DbxCredential?>()
        pendingSignIn = deferred
        Auth.startOAuth2PKCE(activity, appKey, config, SCOPES)
        val credential = try {
            deferred.await()
        } finally {
            pendingSignIn = null
        } ?: return null
        val label = withContext(Dispatchers.IO) {
            val account = DbxClientV2(config, credential).users().currentAccount
            account.email.ifBlank { account.name.displayName }
        }
        val id = "dropbox-" + UUID.randomUUID()
        credentials.save(id, DbxCredential.Writer.writeToString(credential))
        return SignedInAccount(id, provider, label)
    }

    /** Completes a pending sign-in: the activity resumes when the Dropbox sign-in returns. */
    override fun onActivityResumed() {
        val deferred = pendingSignIn ?: return
        deferred.complete(Auth.getDbxCredential())
    }

    override suspend fun folderBrowser(accountId: String): RemoteFolderBrowser = DropboxFolderBrowser(client(accountId))

    override suspend fun store(account: DriveAccount): RemoteDriveStore {
        stores[account.id]?.takeIf { it.first == account.syncFolder }?.let { return it.second }
        return DropboxDriveStore(client(account.id), account.syncFolder).also {
            stores[account.id] = account.syncFolder to it
        }
    }

    override suspend fun signOut(accountId: String) {
        stores.remove(accountId)
        try {
            withContext(Dispatchers.IO) { client(accountId).auth().tokenRevoke() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The credentials are forgotten regardless.
        }
        credentials.remove(accountId)
    }

    private fun client(accountId: String): DbxClientV2 {
        val stored = credentials.load(accountId) ?: throw RemoteUnavailableException("Sign in to Dropbox again")
        return DbxClientV2(config, DbxCredential.Reader.readFully(stored))
    }

    private companion object {
        const val UNCONFIGURED = "unconfigured"
        val SCOPES = listOf("account_info.read", "files.metadata.read", "files.content.read", "files.content.write")
    }
}
