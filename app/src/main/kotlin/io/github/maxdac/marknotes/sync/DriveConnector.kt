package io.github.maxdac.marknotes.sync

import androidx.activity.ComponentActivity
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser

/** An account that has just signed in, before the user picked the folder to sync. */
data class SignedInAccount(val id: String, val provider: DriveProvider, val label: String)

/** Signs in to a cloud provider and creates [RemoteDriveStore]s for its accounts. */
interface DriveConnector {
    val provider: DriveProvider

    /** Why the provider can't be used in this build, or null when it can. */
    val unavailableReason: String?

    /**
     * Starts an interactive sign-in and suspends until it completes. Credentials are stored under the
     * returned account id. Returns null when the user cancels.
     */
    suspend fun signIn(activity: ComponentActivity): SignedInAccount?

    /** Browser for picking the folder to sync, or null when the provider has no folder listing. */
    suspend fun folderBrowser(accountId: String): RemoteFolderBrowser?

    /** Creates the store syncing [DriveAccount.syncFolder]. Throws RemoteUnavailableException when signed out. */
    suspend fun store(account: DriveAccount): RemoteDriveStore

    /** Forgets the credentials of [accountId]. */
    suspend fun signOut(accountId: String)
}
