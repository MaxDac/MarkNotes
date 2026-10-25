package io.github.maxdac.marknotes.sync

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import io.github.maxdac.marknotes.BuildConfig
import io.github.maxdac.marknotes.auth.CredentialStore
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
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
class OneDriveConnector(
    private val context: Context,
    private val credentials: CredentialStore,
    private val diagnostics: DiagnosticReporter = DiagnosticReporter.NONE
) : DriveConnector {
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
        val result = interactiveSignIn(activity, application(), null) ?: return null
        val id = "onedrive-" + UUID.randomUUID()
        credentials.save(id, result.account.id)
        return SignedInAccount(id, provider, result.account.username)
    }

    override val supportsReconnect = true

    /**
     * Signs in to the drive's Microsoft account again through MSAL, which keeps the new tokens: the drive keeps its
     * ID, stored account ID, folder and sync records. Another account is rejected and the old credentials kept.
     */
    override suspend fun reconnect(activity: ComponentActivity, account: DriveAccount): Boolean {
        require(account.provider == provider)
        unavailableReason?.let { throw RemoteUnavailableException(it) }
        val reconnect = OneDriveReconnect(
            load = credentials::load,
            invalidate = { stores.remove(it) },
            discard = { msalId ->
                withContext(Dispatchers.IO) {
                    val app = application()
                    app.getAccount(msalId)?.let { app.removeAccount(it) }
                }
            }
        )
        return reconnect.reconnect(account.id) { expected ->
            val app = application()
            val (known, hint) = withContext(Dispatchers.IO) {
                app.accounts.mapTo(HashSet()) { it.id } to app.getAccount(expected)
            }
            interactiveSignIn(activity, app, hint)?.let {
                OneDriveSignIn(it.account.id, newToMsal = it.account.id !in known)
            }
        }
    }

    private suspend fun interactiveSignIn(
        activity: ComponentActivity,
        app: IMultipleAccountPublicClientApplication,
        account: IAccount?
    ): IAuthenticationResult? = suspendCancellableCoroutine { continuation ->
        val parameters = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(SCOPES)
            .apply { if (account != null) forAccount(account) }
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
                diagnostics.report(DiagnosticCode.SIGN_OUT, e)
            } catch (e: InterruptedException) {
                // Same as above.
                diagnostics.report(DiagnosticCode.SIGN_OUT, e)
            }
        }
    }

    /**
     * Removes every MSAL account with its tokens (accounts of drives disconnected without network included) and the
     * MSAL configuration file. Fails when MSAL couldn't remove them.
     */
    override suspend fun deleteLocalData() {
        stores.clear()
        if (unavailableReason == null) {
            withContext(Dispatchers.IO) {
                val app = application()
                app.accounts.forEach { app.removeAccount(it) }
            }
        }
        File(context.filesDir, CONFIG_FILE).delete()
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
            throw RemoteUnavailableException("OneDrive sign-in expired. Use Reconnect in Settings to sign in again", e)
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
        return File(context.filesDir, CONFIG_FILE).apply { writeText(json.toString()) }
    }

    private companion object {
        const val UNCONFIGURED = "unconfigured"
        const val CONFIG_FILE = "msal_config.json"
        val SCOPES = listOf("Files.ReadWrite", "User.Read")
    }
}
