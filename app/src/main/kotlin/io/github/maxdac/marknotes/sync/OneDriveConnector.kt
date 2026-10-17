package io.github.maxdac.marknotes.sync

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import io.github.maxdac.marknotes.BuildConfig
import io.github.maxdac.marknotes.auth.CredentialStore
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import io.github.maxdac.marknotes.core.sync.onedrive.GraphClient
import io.github.maxdac.marknotes.core.sync.onedrive.OneDriveDriveStore
import io.github.maxdac.marknotes.core.sync.onedrive.OneDriveFolderBrowser
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * OneDrive (personal and work/school accounts) through Microsoft Graph. Tokens are managed by MSAL;
 * only the MSAL account id is stored, under the drive account id.
 */
class OneDriveConnector(private val context: Context, private val credentials: CredentialStore) : DriveConnector {
    override val provider = DriveProvider.ONEDRIVE

    private val http by lazy { OkHttpClient() }
    private val appMutex = Mutex()
    private var app: IMultipleAccountPublicClientApplication? = null
    private val stores = HashMap<String, Pair<String, OneDriveDriveStore>>()

    override val unavailableReason: String?
        get() = if (BuildConfig.MSAL_CLIENT_ID == UNCONFIGURED || BuildConfig.MSAL_SIGNATURE_HASH == UNCONFIGURED) {
            "No Microsoft app registration in this build. See the README to set marknotes.msalClientId."
        } else {
            null
        }

    override suspend fun signIn(activity: ComponentActivity): SignedInAccount? {
        val app = application()
        val result = suspendCancellableCoroutine<IAuthenticationResult?> { continuation ->
            val parameters = AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(activity)
                .withScopes(SCOPES)
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        continuation.resume(authenticationResult)
                    }

                    override fun onError(exception: MsalException) {
                        continuation.resumeWithException(exception)
                    }

                    override fun onCancel() {
                        continuation.resume(null)
                    }
                })
                .build()
            app.acquireToken(parameters)
        } ?: return null
        val id = "onedrive-" + UUID.randomUUID()
        credentials.save(id, result.account.id)
        return SignedInAccount(id, provider, result.account.username)
    }

    override suspend fun folderBrowser(accountId: String): RemoteFolderBrowser = OneDriveFolderBrowser(graph(accountId))

    override suspend fun store(account: DriveAccount): RemoteDriveStore {
        stores[account.id]?.takeIf { it.first == account.syncFolder }?.let { return it.second }
        return OneDriveDriveStore(graph(account.id), account.syncFolder).also {
            stores[account.id] = account.syncFolder to it
        }
    }

    override suspend fun signOut(accountId: String) {
        stores.remove(accountId)
        val msalId = credentials.load(accountId)
        credentials.remove(accountId)
        if (msalId == null) return
        withContext(Dispatchers.IO) {
            try {
                val app = application()
                app.getAccount(msalId)?.let { app.removeAccount(it) }
            } catch (e: MsalException) {
                // The account is forgotten locally regardless.
            } catch (e: InterruptedException) {
                // Same as above.
            }
        }
    }

    private fun graph(accountId: String): GraphClient {
        if (credentials.load(accountId) == null) throw RemoteUnavailableException("Sign in to OneDrive again")
        return GraphClient(http, { accessToken(accountId) })
    }

    private suspend fun accessToken(accountId: String): String = withContext(Dispatchers.IO) {
        val msalId = credentials.load(accountId) ?: throw RemoteUnavailableException("Sign in to OneDrive again")
        val app = application()
        val account = app.getAccount(msalId) ?: throw RemoteUnavailableException("Sign in to OneDrive again")
        val parameters = AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(account.authority)
            .withScopes(SCOPES)
            .build()
        try {
            app.acquireTokenSilent(parameters).accessToken
        } catch (e: MsalUiRequiredException) {
            throw RemoteUnavailableException("OneDrive sign-in expired, remove and add the drive again", e)
        }
    }

    private suspend fun application(): IMultipleAccountPublicClientApplication = appMutex.withLock {
        app ?: withContext(Dispatchers.IO) {
            PublicClientApplication.createMultipleAccountPublicClientApplication(context, writeConfig())
        }.also { app = it }
    }

    private fun writeConfig(): File {
        val redirect = "msauth://${context.packageName}/" + Uri.encode(BuildConfig.MSAL_SIGNATURE_HASH)
        val json = JSONObject()
            .put("client_id", BuildConfig.MSAL_CLIENT_ID)
            .put("redirect_uri", redirect)
            .put("account_mode", "MULTIPLE")
            .put("broker_redirect_uri_registered", false)
            .put(
                "authorities",
                JSONArray().put(
                    JSONObject()
                        .put("type", "AAD")
                        .put("audience", JSONObject().put("type", "AzureADandPersonalMicrosoftAccount"))
                        .put("default", true)
                )
            )
        return File(context.filesDir, "msal_config.json").apply { writeText(json.toString()) }
    }

    private companion object {
        const val UNCONFIGURED = "unconfigured"
        val SCOPES = listOf("Files.ReadWrite", "User.Read")
    }
}
