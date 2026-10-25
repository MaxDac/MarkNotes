package io.github.maxdac.marknotes.core.auth

/**
 * Proton sessions keyed by local drive-account IDs, not by rotating Proton session UIDs. Every login gets a new
 * account ID, so one account's credentials are never replaced by another login.
 */
interface ProtonAccountSessionStorage {
    fun loadAll(): Map<String, ProtonSession>

    fun save(accountId: String, session: ProtonSession)

    fun remove(accountId: String)
}
