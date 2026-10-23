package io.github.maxdac.marknotes.sync

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser
import io.github.maxdac.marknotes.data.AppSettings

/** An account that has just signed in, before the user picked the folder to sync. */
data class SignedInAccount(val id: String, val provider: DriveProvider, val label: String)

/** An in-app sign-in screen: calls `onDone` once signed in, `onBack` to leave it. */
typealias SignInScreen = @Composable (onDone: () -> Unit, onBack: () -> Unit) -> Unit

/**
 * Signs in to a cloud provider and creates [RemoteDriveStore]s for its accounts. The libre app ships the
 * Dropbox connector; MarkNotes Pro adds its own through [io.github.maxdac.marknotes.MarkNotesApp.extraConnectors].
 */
interface DriveConnector {
    val provider: DriveProvider

    /** Why the provider can't be used in this build, or null when it can. */
    val unavailableReason: String?

    /** Whether at most one account of this provider can be connected. */
    val singleAccount: Boolean get() = false

    /** Text shown in Settings, for example a provider's terms (null for none). */
    val notice: String? get() = null

    /** The in-app screen to show before [signIn] while [needsSignInScreen], or null when sign-in is external. */
    val signInScreen: SignInScreen? get() = null

    /** Whether [signInScreen] must be shown (and completed) before [signIn]. */
    fun needsSignInScreen(): Boolean = false

    /**
     * Starts an interactive sign-in and suspends until it completes. Credentials are stored under the
     * returned account id. Returns null when the user cancels.
     */
    suspend fun signIn(activity: ComponentActivity): SignedInAccount?

    /** Called whenever the activity resumes, which is when browser-based sign-ins return. */
    fun onActivityResumed() {}

    /** Browser for picking the folder to sync, or null when the provider has no folder listing. */
    suspend fun folderBrowser(accountId: String): RemoteFolderBrowser?

    /** Creates the store syncing [DriveAccount.syncFolder]. Throws RemoteUnavailableException when signed out. */
    suspend fun store(account: DriveAccount): RemoteDriveStore

    /** Forgets the credentials of [accountId]. */
    suspend fun signOut(accountId: String)

    /** A drive account to create from a setup that predates drive accounts, or null when there is none. */
    suspend fun legacyAccount(settings: AppSettings): DriveAccount? = null
}
