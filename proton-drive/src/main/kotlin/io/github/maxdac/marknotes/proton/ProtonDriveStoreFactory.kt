package io.github.maxdac.marknotes.proton

import android.content.Context
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteDriveStoreFactory
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import me.proton.drive.sdk.ProtonDriveClient

/**
 * Creates the SDK-backed [RemoteDriveStore]. Instantiated by reflection from the app, so this module
 * stays optional.
 *
 * Remaining work: `ProtonDriveSdk.protonDriveClientCreate(...)` needs a protoncore `ApiProvider`
 * (authenticated with [ProtonSession]'s UID/tokens and the `x-pm-appversion` header) and
 * `UserAddressResolver` / `PublicAddressResolver` implementations able to unlock the user's address
 * keys with [ProtonSession.keyPassphrases]. See the README section "Finishing the Proton Drive SDK
 * wiring". Once a client exists, pass it to [ProtonDriveStore].
 */
class ProtonDriveStoreFactory(@Suppress("unused") private val context: Context) : RemoteDriveStoreFactory {

    override suspend fun create(session: ProtonSession, remoteFolderName: String): RemoteDriveStore {
        val client = createClient(session)
            ?: throw RemoteUnavailableException(
                "Proton Drive sync is not fully wired yet in this build: the SDK client needs a protoncore " +
                    "API provider and address key resolvers. See the README for the remaining steps.",
            )
        return ProtonDriveStore(client, remoteFolderName)
    }

    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    private suspend fun createClient(session: ProtonSession): ProtonDriveClient? = null
}
