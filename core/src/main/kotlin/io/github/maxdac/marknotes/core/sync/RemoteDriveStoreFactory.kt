package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.auth.ProtonSession

/**
 * Creates the [RemoteDriveStore] for a logged-in session. The Proton Drive SDK implementation lives in the
 * optional :proton-drive module and is discovered at runtime, so the app builds without the SDK.
 */
interface RemoteDriveStoreFactory {
    suspend fun create(session: ProtonSession, remoteFolderName: String): RemoteDriveStore
}
