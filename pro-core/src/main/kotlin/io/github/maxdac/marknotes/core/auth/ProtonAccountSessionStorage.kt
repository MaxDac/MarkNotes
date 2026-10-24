package io.github.maxdac.marknotes.core.auth

/** Sessions keyed by stable local drive-account IDs, not by rotating Proton session UIDs. */
interface ProtonAccountSessionStorage {
    fun loadAll(): Map<String, ProtonSession>

    /** Nonsecret provider user IDs survive token revocation, so reauthentication can keep local IDs. */
    fun loadUserIds(): Map<String, String>

    fun save(accountId: String, session: ProtonSession)

    /** Drops credentials but retains the stable provider identity. */
    fun invalidate(accountId: String)

    /** Explicit sign-out forgets credentials and identity. */
    fun remove(accountId: String)
}
