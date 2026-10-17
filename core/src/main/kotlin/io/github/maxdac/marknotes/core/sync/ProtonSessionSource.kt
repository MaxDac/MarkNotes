package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.auth.ProtonSession

/**
 * Live access to the stored Proton session. The Drive SDK keeps its client alive across syncs and asks
 * for a token refresh when the API answers 401, so it must always see the latest tokens.
 */
interface ProtonSessionSource {
    /** The current session, or null when logged out. */
    val current: ProtonSession?

    /**
     * Refreshes the tokens if they are still [staleAccessToken] (another caller may have refreshed
     * already). Returns the up-to-date session, or null when the session is gone.
     */
    suspend fun refresh(staleAccessToken: String): ProtonSession?

    /** Called when the API revoked the session. */
    suspend fun invalidate()
}

/** Creates the Proton Drive [RemoteDriveStore] for the logged-in session (see the :proton-drive module). */
interface RemoteDriveStoreFactory {
    suspend fun create(sessions: ProtonSessionSource, remoteFolderName: String): RemoteDriveStore
}
