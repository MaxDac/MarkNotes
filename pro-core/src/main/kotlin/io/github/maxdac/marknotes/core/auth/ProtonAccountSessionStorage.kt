package io.github.maxdac.marknotes.core.auth

/**
 * Proton sessions keyed by local drive-account IDs, not by rotating Proton session UIDs. Every login gets a new
 * account ID, so one account's credentials are never replaced by another login.
 */
interface ProtonAccountSessionStorage {
    fun loadAll(): Map<String, ProtonSession>

    fun save(accountId: String, session: ProtonSession)

    /** Forgets the session of [accountId] (when it expires or the drive is removed), not its [userIds] entry. */
    fun remove(accountId: String)

    /**
     * The immutable Proton user ID of each account. Unlike the session it is kept when the session expires, so a
     * reconnect can check that the new login belongs to the same Proton user.
     */
    fun userIds(): Map<String, String>

    fun saveUserId(accountId: String, userId: String)

    fun removeUserId(accountId: String)
}
