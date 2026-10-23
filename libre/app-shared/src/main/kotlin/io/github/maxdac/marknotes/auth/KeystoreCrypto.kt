package io.github.maxdac.marknotes.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM encryption with a key held in the Android Keystore. Output is "base64(iv):base64(ciphertext)". */
class KeystoreCrypto(private val alias: String = DEFAULT_ALIAS) {
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    /** Returns null when [stored] can't be decrypted (e.g. the key was lost). */
    fun decrypt(stored: String): String? = try {
        val (iv, ciphertext) = stored.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "marknotes_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE = 256
    }
}

/** Encrypted per-account credentials (Dropbox tokens, ...), keyed by drive account id. */
class CredentialStore(context: Context, private val crypto: KeystoreCrypto = KeystoreCrypto()) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    fun load(accountId: String): String? = prefs.getString(accountId, null)?.let { crypto.decrypt(it) }

    fun save(accountId: String, secret: String) {
        prefs.edit().putString(accountId, crypto.encrypt(secret)).apply()
    }

    fun remove(accountId: String) {
        prefs.edit().remove(accountId).apply()
    }
}
