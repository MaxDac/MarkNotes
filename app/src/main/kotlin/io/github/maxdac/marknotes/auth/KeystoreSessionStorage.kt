package io.github.maxdac.marknotes.auth

import android.content.Context
import io.github.maxdac.marknotes.core.auth.ProtonAccountSessionStorage
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.auth.SessionStorage
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.data.MarkNotesDatabase

/** Stores the Proton session in SharedPreferences, encrypted with [KeystoreCrypto]. */
class KeystoreSessionStorage(
    context: Context,
    private val crypto: KeystoreCrypto = KeystoreCrypto(),
    private val diagnostics: DiagnosticReporter = DiagnosticReporter.NONE
) : SessionStorage, ProtonAccountSessionStorage {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    override fun load(): ProtonSession? {
        val stored = prefs.getString(KEY_SESSION, null) ?: return null
        return decode(stored) { clear() }
    }

    private fun decode(stored: String, remove: () -> Unit): ProtonSession? {
        val session = crypto.decrypt(stored)?.let {
            try {
                ProtonSession.fromJson(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        if (session == null) {
            diagnostics.report(DiagnosticCode.AUTHENTICATION, null)
            remove()
        }
        return session
    }

    override fun save(session: ProtonSession) {
        prefs.edit().putString(KEY_SESSION, crypto.encrypt(session.toJson())).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    @Synchronized
    override fun loadAll(): Map<String, ProtonSession> {
        val legacy = load()
        if (legacy != null) {
            val key = PREFIX + MarkNotesDatabase.LEGACY_PROTON_ID
            val edit = prefs.edit()
            if (!prefs.contains(key)) {
                edit.putString(key, crypto.encrypt(legacy.toJson()))
                if (legacy.userId.isNotBlank()) {
                    edit.putString(IDENTITY_PREFIX + MarkNotesDatabase.LEGACY_PROTON_ID, legacy.userId)
                }
            }
            check(edit.remove(KEY_SESSION).commit()) { "Could not migrate the Proton session" }
        }
        return prefs.all.keys.filter { it.startsWith(PREFIX) }.mapNotNull { key ->
            val stored = prefs.getString(key, null) ?: return@mapNotNull null
            decode(stored) { check(prefs.edit().remove(key).commit()) }?.let {
                key.removePrefix(PREFIX) to it
            }
        }.toMap()
    }

    @Synchronized
    override fun loadUserIds(): Map<String, String> =
        prefs.all.keys.filter { it.startsWith(IDENTITY_PREFIX) }.associate { key ->
            key.removePrefix(IDENTITY_PREFIX) to requireNotNull(prefs.getString(key, null))
        }

    @Synchronized
    override fun save(accountId: String, session: ProtonSession) {
        val identityKey = IDENTITY_PREFIX + accountId
        val savedUserId = prefs.getString(identityKey, null)
        require(savedUserId == null || session.userId.isBlank() || savedUserId == session.userId) {
            "Cannot replace a Proton account with another user"
        }
        val edit = prefs.edit().putString(PREFIX + accountId, crypto.encrypt(session.toJson()))
        if (session.userId.isNotBlank()) edit.putString(identityKey, session.userId)
        check(edit.commit()) {
            "Could not save the Proton session"
        }
    }

    @Synchronized
    override fun invalidate(accountId: String) {
        check(prefs.edit().remove(PREFIX + accountId).commit()) { "Could not invalidate the Proton session" }
    }

    @Synchronized
    override fun remove(accountId: String) {
        check(prefs.edit().remove(PREFIX + accountId).remove(IDENTITY_PREFIX + accountId).commit()) {
            "Could not remove the Proton session"
        }
    }

    private companion object {
        const val KEY_SESSION = "session"
        const val PREFIX = "account:"
        const val IDENTITY_PREFIX = "identity:"
    }
}
