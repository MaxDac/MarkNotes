package io.github.maxdac.marknotes.auth

import android.content.Context
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.auth.SessionStorage

/** Stores the Proton session in SharedPreferences, encrypted with [KeystoreCrypto]. */
class KeystoreSessionStorage(context: Context, private val crypto: KeystoreCrypto = KeystoreCrypto()) :
    SessionStorage {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    override fun load(): ProtonSession? {
        val stored = prefs.getString(KEY_SESSION, null) ?: return null
        val session = crypto.decrypt(stored)?.let {
            try {
                ProtonSession.fromJson(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        if (session == null) clear()
        return session
    }

    override fun save(session: ProtonSession) {
        prefs.edit().putString(KEY_SESSION, crypto.encrypt(session.toJson())).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private companion object {
        const val KEY_SESSION = "session"
    }
}
