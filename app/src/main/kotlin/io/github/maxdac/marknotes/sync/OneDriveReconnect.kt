package io.github.maxdac.marknotes.sync

import kotlinx.coroutines.CancellationException

/** A completed interactive OneDrive sign-in. */
internal class OneDriveSignIn(
    /** The MSAL account ID ([com.microsoft.identity.client.IAccount.getId]), never the username. */
    val msalAccountId: String,
    /** Whether MSAL didn't know the account before this sign-in, so no drive can be using it. */
    val newToMsal: Boolean
)

/**
 * Reconnects a OneDrive drive. A drive's credential is the immutable MSAL account ID of its Microsoft account, and
 * MSAL keeps the tokens: an interactive sign-in to that same account refreshes them, so nothing is stored under
 * the drive. A sign-in to any other account is rejected, and forgotten when no drive was using it.
 */
internal class OneDriveReconnect(
    private val load: (accountId: String) -> String?,
    /** Drops the cached store of a drive, so the next sync uses the new tokens. */
    private val invalidate: (accountId: String) -> Unit,
    /** Removes an MSAL account that only the rejected sign-in added. */
    private val discard: suspend (msalAccountId: String) -> Unit
) {
    /** Returns false when the user cancels [signIn]; throws when the drive can't be reconnected. */
    suspend fun reconnect(accountId: String, signIn: suspend (expected: String) -> OneDriveSignIn?): Boolean {
        val expected = checkNotNull(load(accountId)?.takeIf { it.isNotBlank() }) { MISSING }
        val result = signIn(expected) ?: return false
        if (result.msalAccountId != expected) {
            val failure = IllegalStateException(WRONG_ACCOUNT)
            if (result.newToMsal) {
                try {
                    discard(result.msalAccountId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure.addSuppressed(e)
                }
            }
            throw failure
        }
        invalidate(accountId)
        return true
    }

    companion object {
        const val WRONG_ACCOUNT =
            "Sign in to the same Microsoft account as this drive. The drive and its sync history have been kept."
        const val MISSING = "This OneDrive drive has no saved account on this device. Disconnect it and add it again."
    }
}
