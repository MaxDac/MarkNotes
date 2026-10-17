package io.github.maxdac.marknotes.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.auth.SessionStorage
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the Proton session in SharedPreferences, encrypted with an AES-GCM key held in the Android Keystore. */
class KeystoreSessionStorage(context: Context) : SessionStorage {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    override fun load(): ProtonSession? {
        val stored = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            val (iv, ciphertext) = stored.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            ProtonSession.fromJson(cipher.doFinal(ciphertext).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            clear()
            null
        }
    }

    override fun save(session: ProtonSession) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(session.toJson().toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(KEY_SESSION, encoded).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "marknotes_session"
        const val KEY_SESSION = "session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
