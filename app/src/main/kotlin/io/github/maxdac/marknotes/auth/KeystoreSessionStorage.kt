package io.github.maxdac.marknotes.auth

import android.content.Context
import io.github.maxdac.marknotes.core.auth.ProtonAccountSessionStorage
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.auth.SessionStorage
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.data.MarkNotesDatabase

/**
 * Stores the Proton sessions in SharedPreferences, encrypted with [KeystoreCrypto], one entry per drive account.
 * [SessionStorage] is the single-session format that predates multiple accounts: [loadAll] migrates it to the
 * [MarkNotesDatabase.LEGACY_PROTON_ID] account, which keeps the drive and its synced folder.
 */
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

    /** Never throws: it runs when the app starts, and a failed migration is retried on the next start. */
    @Synchronized
    override fun loadAll(): Map<String, ProtonSession> {
        val legacyKey = PREFIX + MarkNotesDatabase.LEGACY_PROTON_ID
        val legacy = load()
        if (legacy != null) {
            val edit = prefs.edit().remove(KEY_SESSION)
            // A newer session already saved for the account (a migration interrupted after a refresh) wins.
            if (!prefs.contains(legacyKey)) edit.putString(legacyKey, crypto.encrypt(legacy.toJson()))
            if (!edit.commit()) diagnostics.report(DiagnosticCode.AUTHENTICATION, null)
        }
        val sessions = prefs.all.keys.filter { it.startsWith(PREFIX) }.mapNotNull { key ->
            val stored = prefs.getString(key, null) ?: return@mapNotNull null
            decode(stored) { prefs.edit().remove(key).apply() }?.let { key.removePrefix(PREFIX) to it }
        }.toMap()
        // Until the migration is committed, the legacy entry still holds the account's session.
        if (legacy == null || MarkNotesDatabase.LEGACY_PROTON_ID in sessions) return sessions
        return sessions + (MarkNotesDatabase.LEGACY_PROTON_ID to legacy)
    }

    @Synchronized
    override fun save(accountId: String, session: ProtonSession) {
        check(prefs.edit().putString(PREFIX + accountId, crypto.encrypt(session.toJson())).commit()) {
            "Could not save the Proton session"
        }
    }

    @Synchronized
    override fun remove(accountId: String) {
        val edit = prefs.edit().remove(PREFIX + accountId)
        if (accountId == MarkNotesDatabase.LEGACY_PROTON_ID) edit.remove(KEY_SESSION)
        check(edit.commit()) { "Could not remove the Proton session" }
    }

    private companion object {
        const val KEY_SESSION = "session"
        const val PREFIX = "account:"
    }
}
