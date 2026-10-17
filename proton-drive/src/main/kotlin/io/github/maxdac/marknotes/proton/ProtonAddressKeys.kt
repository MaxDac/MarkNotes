package io.github.maxdac.marknotes.proton

import io.github.maxdac.marknotes.core.auth.ProtonAddress
import io.github.maxdac.marknotes.core.auth.ProtonAddressKey
import io.github.maxdac.marknotes.core.auth.ProtonKeysJson
import io.github.maxdac.marknotes.core.auth.ProtonUserKey
import io.github.maxdac.marknotes.core.sync.ProtonSessionSource
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.crypto.common.pgp.PGPCrypto
import me.proton.core.crypto.common.pgp.UnlockedKey
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.drive.sdk.PublicAddressResolver
import me.proton.drive.sdk.UserAddressResolver
import me.proton.drive.sdk.entity.Address

/**
 * Gives the Drive SDK the user's addresses and unlocked address keys. Keys are unlocked on demand with
 * the key passphrases derived at login ([io.github.maxdac.marknotes.core.auth.ProtonSession.keyPassphrases])
 * and closed right after the SDK has used them.
 */
internal class ProtonAddressKeys(
    private val apiProvider: ApiProvider,
    private val sessions: ProtonSessionSource,
    private val userId: UserId,
    private val crypto: PGPCrypto
) : UserAddressResolver, PublicAddressResolver {
    private val mutex = Mutex()
    private var addresses: List<ProtonAddress>? = null
    private var userKeys: List<ProtonUserKey>? = null
    private val publicKeys = ConcurrentHashMap<String, List<ByteArray>>()

    override suspend fun getAddress(id: String): Address = findAddress(id).toSdkAddress()

    override suspend fun getDefaultAddress(): Address {
        val all = addresses()
        val address = all.firstOrNull { it.enabled } ?: all.firstOrNull()
            ?: throw RemoteUnavailableException("This Proton account has no address.")
        return address.toSdkAddress()
    }

    override suspend fun <T> getAddressPrimaryPrivateKey(id: String, block: (ByteArray) -> T): T {
        val key = findAddress(id).primaryKey ?: throw RemoteUnavailableException("Address $id has no active key.")
        return unlockAddressKey(key).use { block(it.value) }
    }

    override suspend fun <T> getAddressPrivateKeys(id: String, block: (List<ByteArray>) -> T): T {
        val keys = findAddress(id).keys.filter { it.active }
        val unlocked = mutableListOf<UnlockedKey>()
        try {
            var failure: Exception? = null
            for (key in keys) {
                try {
                    unlocked += unlockAddressKey(key)
                } catch (e: RemoteUnavailableException) {
                    failure = e
                }
            }
            if (unlocked.isEmpty()) {
                throw failure ?: RemoteUnavailableException("Address $id has no active key.")
            }
            return block(unlocked.map { it.value })
        } finally {
            unlocked.forEach { it.close() }
        }
    }

    override suspend fun getAddressPublicKeys(emailAddress: String): List<ByteArray> =
        publicKeys[emailAddress] ?: run {
            val response = apiProvider.get<KeysApi>(userId).invoke { publicKeys(emailAddress, 0) }.valueOrThrow
            ProtonKeysJson.publicKeys(response).map { it.toByteArray() }.also { publicKeys[emailAddress] = it }
        }

    private suspend fun findAddress(id: String): ProtonAddress =
        addresses().firstOrNull { it.id == id }
            ?: addresses(reload = true).firstOrNull { it.id == id }
            ?: throw RemoteUnavailableException("Unknown Proton address $id.")

    private suspend fun addresses(reload: Boolean = false): List<ProtonAddress> = mutex.withLock {
        addresses?.takeUnless { reload } ?: run {
            val response = apiProvider.get<KeysApi>(userId).invoke { addresses() }.valueOrThrow
            ProtonKeysJson.addresses(response).also { addresses = it }
        }
    }

    private suspend fun userKeys(): List<ProtonUserKey> = mutex.withLock {
        userKeys ?: run {
            val response = apiProvider.get<KeysApi>(userId).invoke { user() }.valueOrThrow
            ProtonKeysJson.userKeys(response).sortedByDescending { it.primary }.also { userKeys = it }
        }
    }

    private suspend fun unlockAddressKey(key: ProtonAddressKey): UnlockedKey {
        val passphrase = addressKeyPassphrase(key)
        try {
            return crypto.unlock(key.privateKey, passphrase)
        } catch (e: Exception) {
            throw RemoteUnavailableException("Couldn't unlock the Proton address key ${key.id}.", e)
        } finally {
            passphrase.fill(0)
        }
    }

    /**
     * Modern address keys are locked with a random passphrase encrypted ([ProtonAddressKey.token]) to the
     * user key; legacy ones with the mailbox-password-derived passphrase, like user keys.
     */
    private suspend fun addressKeyPassphrase(key: ProtonAddressKey): ByteArray {
        val passphrases = sessions.current?.keyPassphrases.orEmpty()
        if (passphrases.isEmpty()) {
            throw RemoteUnavailableException(
                "The Proton session has no key passphrases. Sign out and sign in again to sync with Proton Drive."
            )
        }
        val token = key.token
        val passphrase = if (token == null) {
            (passphrases[key.id] ?: userKeys().firstNotNullOfOrNull { passphrases[it.id] })?.toByteArray()
        } else {
            decryptToken(token, passphrases)
        }
        return passphrase
            ?: throw RemoteUnavailableException(
                "Couldn't get the passphrase of the Proton address key ${key.id}. Sign out and sign in again."
            )
    }

    /** Decrypts an address-key token with the first user key that can be unlocked. */
    private suspend fun decryptToken(token: String, passphrases: Map<String, String>): ByteArray? {
        for (userKey in userKeys()) {
            val userPassphrase = passphrases[userKey.id]?.toByteArray() ?: continue
            val decrypted = runCatching {
                crypto.unlock(userKey.privateKey, userPassphrase).use { crypto.decryptData(token, it.value) }
            }
            userPassphrase.fill(0)
            decrypted.getOrNull()?.let { return it }
        }
        return null
    }

    private fun ProtonAddress.toSdkAddress() = Address(
        addressId = id,
        order = order,
        emailAddress = email,
        status = if (enabled) Address.Status.ENABLED else Address.Status.DISABLED,
        keys = keys.map { key ->
            Address.Key(
                addressId = id,
                keyId = key.id,
                active = key.active,
                allowedForEncryption = key.canEncrypt,
                allowedForVerification = key.canVerify
            )
        },
        primaryKeyIndex = keys.indexOf(primaryKey)
    )
}
