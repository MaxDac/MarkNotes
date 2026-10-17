package io.github.maxdac.marknotes.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** An authenticated Proton API session. */
data class ProtonSession(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    val username: String,
    val userId: String = "",
    /** Passphrases that unlock the user's private keys, by key ID. Needed by the Drive SDK. */
    val keyPassphrases: Map<String, String> = emptyMap()
) {
    fun toJson(): String = buildJsonObject {
        put("uid", uid)
        put("accessToken", accessToken)
        put("refreshToken", refreshToken)
        put("username", username)
        put("userId", userId)
        put("keyPassphrases", JsonObject(keyPassphrases.mapValues { JsonPrimitive(it.value) }))
    }.toString()

    override fun toString(): String = "ProtonSession(uid=${uid.take(6)}…, username=$username)"

    companion object {
        fun fromJson(json: String): ProtonSession {
            val obj = Json.parseToJsonElement(json).jsonObject
            fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            return ProtonSession(
                uid = str("uid"),
                accessToken = str("accessToken"),
                refreshToken = str("refreshToken"),
                username = str("username"),
                userId = str("userId"),
                keyPassphrases = obj["keyPassphrases"]?.jsonObject
                    ?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
                    .orEmpty()
            )
        }
    }
}

/** Persists the session. The Android implementation encrypts it with a Keystore key. */
interface SessionStorage {
    fun load(): ProtonSession?
    fun save(session: ProtonSession)
    fun clear()
}

class InMemorySessionStorage(private var session: ProtonSession? = null) : SessionStorage {
    override fun load(): ProtonSession? = session
    override fun save(session: ProtonSession) {
        this.session = session
    }
    override fun clear() {
        session = null
    }
}
