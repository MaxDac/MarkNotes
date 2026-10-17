package io.github.maxdac.marknotes.core.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A user (account) private key, armored and locked with the key passphrase. */
data class ProtonUserKey(val id: String, val privateKey: String, val primary: Boolean, val active: Boolean)

/**
 * An address private key. Modern accounts lock it with a passphrase carried in [token], encrypted to the
 * user key; legacy accounts lock it with the key-salt passphrase, like user keys.
 */
data class ProtonAddressKey(
    val id: String,
    val privateKey: String,
    val token: String?,
    val primary: Boolean,
    val active: Boolean,
    val flags: Int
) {
    val canVerify: Boolean get() = flags and FLAG_NOT_COMPROMISED != 0
    val canEncrypt: Boolean get() = flags and FLAG_NOT_OBSOLETE != 0

    companion object {
        const val FLAG_NOT_COMPROMISED = 1
        const val FLAG_NOT_OBSOLETE = 2
    }
}

data class ProtonAddress(
    val id: String,
    val email: String,
    val order: Int,
    val enabled: Boolean,
    val keys: List<ProtonAddressKey>
) {
    val primaryKey: ProtonAddressKey? get() = keys.firstOrNull { it.primary && it.active }
        ?: keys.firstOrNull { it.active }
}

/** Parses the Proton key endpoints (`core/v4/users`, `core/v4/addresses`, `core/v4/keys/all`). */
object ProtonKeysJson {

    fun userKeys(response: JsonObject): List<ProtonUserKey> {
        val user = response["User"]?.asObject() ?: return emptyList()
        return user["Keys"].asArray().mapNotNull { element ->
            val key = element.asObject() ?: return@mapNotNull null
            ProtonUserKey(
                id = key.string("ID") ?: return@mapNotNull null,
                privateKey = key.string("PrivateKey") ?: return@mapNotNull null,
                primary = key.int("Primary") == 1,
                active = (key.int("Active") ?: 1) == 1
            )
        }
    }

    fun addresses(response: JsonObject): List<ProtonAddress> =
        response["Addresses"].asArray().mapNotNull { element ->
            val address = element.asObject() ?: return@mapNotNull null
            ProtonAddress(
                id = address.string("ID") ?: return@mapNotNull null,
                email = address.string("Email").orEmpty(),
                order = address.int("Order") ?: Int.MAX_VALUE,
                enabled = address.int("Status") == 1,
                keys = address["Keys"].asArray().mapNotNull { keyElement ->
                    val key = keyElement.asObject() ?: return@mapNotNull null
                    ProtonAddressKey(
                        id = key.string("ID") ?: return@mapNotNull null,
                        privateKey = key.string("PrivateKey") ?: return@mapNotNull null,
                        token = key.string("Token")?.takeIf { it.isNotBlank() },
                        primary = key.int("Primary") == 1,
                        active = (key.int("Active") ?: 1) == 1,
                        flags = key.int("Flags")
                            ?: (ProtonAddressKey.FLAG_NOT_COMPROMISED or ProtonAddressKey.FLAG_NOT_OBSOLETE)
                    )
                }
            )
        }.sortedBy { it.order }

    /** Armored public keys of an email address, verified keys first. */
    fun publicKeys(response: JsonObject): List<String> {
        val groups = listOfNotNull(response["Address"]?.asObject(), response["Unverified"]?.asObject())
        val keys = if (groups.isEmpty()) response["Keys"].asArray() else groups.flatMap { it["Keys"].asArray() }
        return keys.mapNotNull { it.asObject()?.string("PublicKey") }
    }

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asArray(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
}
