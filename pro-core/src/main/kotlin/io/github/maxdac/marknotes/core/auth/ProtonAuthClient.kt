package io.github.maxdac.marknotes.core.auth

import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.sync.UserFacingError
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ProtonApiException(
    val code: Int,
    message: String,
    val httpStatus: Int = 0
) : Exception(message),
    UserFacingError {
    val isHumanVerificationRequired: Boolean get() = code == HUMAN_VERIFICATION_REQUIRED

    override val userMessage: String
        get() = when (code) {
            HUMAN_VERIFICATION_REQUIRED ->
                "Proton is asking for human verification (captcha), which this app does not support. " +
                    "Sign in once at proton.me from this network, wait a while and try again."

            WRONG_PASSWORD -> "Incorrect username or password."

            else -> message ?: "Unknown error"
        }

    companion object {
        const val HUMAN_VERIFICATION_REQUIRED = 9001
        const val WRONG_PASSWORD = 8002
    }
}

sealed interface LoginResult {
    data class Success(val session: ProtonSession) : LoginResult
    data class TwoFactorRequired(val session: ProtonSession) : LoginResult
}

/**
 * Minimal client for Proton's authentication API: SRP login, TOTP second factor,
 * token refresh, logout and key salts. Every request carries the x-pm-appversion header.
 */
class ProtonAuthClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val appVersion: String = APP_VERSION,
    httpClient: OkHttpClient = OkHttpClient(),
    private val random: SecureRandom = SecureRandom(),
    private val diagnostics: DiagnosticReporter = DiagnosticReporter.NONE
) {
    private val http: OkHttpClient = httpClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("x-pm-appversion", appVersion)
                    .header("Accept", "application/vnd.protonmail.v1+json")
                    .build()
            )
        }
        .build()

    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val unauthSession = try {
            createUnauthSession()
        } catch (e: ProtonApiException) {
            diagnostics.report(DiagnosticCode.AUTHENTICATION, e)
            null
        }
        val info = post("/core/v4/auth/info", buildJsonObject { put("Username", username) }, unauthSession)
        val version = info.int("Version")
        if (version != 3 && version != 4) {
            throw ProtonApiException(
                0,
                "This account uses a legacy password scheme (version $version). " +
                    "Sign in once at proton.me to upgrade it, then try again."
            )
        }
        val modulus = ProtonSrp.decodeModulus(info.string("Modulus"))
        val serverEphemeral = decode(info.string("ServerEphemeral"))
        val hashedPassword = ProtonSrp.hashPassword(version, password, info.string("Salt"), modulus)
        val proofs = ProtonSrp.generateProofs(modulus, serverEphemeral, hashedPassword, random)

        val auth = post(
            "/core/v4/auth",
            buildJsonObject {
                put("Username", username)
                put("ClientEphemeral", encode(proofs.clientEphemeral))
                put("ClientProof", encode(proofs.clientProof))
                put("SRPSession", info.string("SRPSession"))
            },
            unauthSession
        )
        if (!MessageDigest.isEqual(decode(auth.string("ServerProof")), proofs.expectedServerProof)) {
            throw ProtonApiException(0, "Server proof verification failed")
        }
        val session = ProtonSession(
            uid = auth.string("UID"),
            accessToken = auth.string("AccessToken"),
            refreshToken = auth.string("RefreshToken"),
            username = username,
            userId = auth.stringOrNull("UserID").orEmpty()
        )
        if (auth.intOrNull("PasswordMode") == 2) {
            logoutQuietly(session)
            throw ProtonApiException(0, "Accounts with a separate mailbox password are not supported yet.")
        }
        val twoFactor = twoFactorFlags(auth)
        when {
            (twoFactor and TOTP) != 0 -> LoginResult.TwoFactorRequired(session)

            twoFactor != 0 -> {
                logoutQuietly(session)
                throw ProtonApiException(0, "Only authenticator-app (TOTP) two-factor authentication is supported.")
            }

            else -> LoginResult.Success(session)
        }
    }

    suspend fun submitTwoFactor(session: ProtonSession, code: String): ProtonSession = withContext(Dispatchers.IO) {
        post("/core/v4/auth/2fa", buildJsonObject { put("TwoFactorCode", code.trim()) }, session)
        session
    }

    /** Computes the key passphrases from the login password and the user's key salts. */
    suspend fun fetchKeyPassphrases(session: ProtonSession, password: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val response = get("/core/v4/keys/salts", session)
            val salts = response["KeySalts"]?.jsonArray.orEmpty()
            salts.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj.stringOrNull("ID") ?: return@mapNotNull null
                val salt = obj.stringOrNull("KeySalt") ?: return@mapNotNull null
                id to ProtonSrp.mailboxPassword(password, salt)
            }.toMap()
        }

    suspend fun refresh(session: ProtonSession): ProtonSession = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("UID", session.uid)
            put("RefreshToken", session.refreshToken)
            put("ResponseType", "token")
            put("GrantType", "refresh_token")
            put("RedirectURI", "http://protonmail.ch")
        }
        val request = Request.Builder()
            .url(baseUrl + "/auth/v4/refresh")
            .header("x-pm-uid", session.uid)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val response = execute(request)
        session.copy(
            accessToken = response.string("AccessToken"),
            refreshToken = response.string("RefreshToken")
        )
    }

    suspend fun logout(session: ProtonSession) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + "/core/v4/auth")
            .authorized(session)
            .delete()
            .build()
        execute(request)
        Unit
    }

    private fun logoutQuietly(session: ProtonSession) {
        try {
            execute(Request.Builder().url(baseUrl + "/core/v4/auth").authorized(session).delete().build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: the session expires on its own.
            diagnostics.report(DiagnosticCode.SIGN_OUT, e)
        }
    }

    private fun createUnauthSession(): ProtonSession {
        val response = execute(
            Request.Builder()
                .url(baseUrl + "/auth/v4/sessions")
                .post("{}".toRequestBody(JSON))
                .build()
        )
        return ProtonSession(
            uid = response.string("UID"),
            accessToken = response.string("AccessToken"),
            refreshToken = response.stringOrNull("RefreshToken").orEmpty(),
            username = ""
        )
    }

    private fun post(path: String, body: JsonObject, session: ProtonSession?): JsonObject {
        val builder = Request.Builder().url(baseUrl + path).post(body.toString().toRequestBody(JSON))
        if (session != null) builder.authorized(session)
        return execute(builder.build())
    }

    private fun get(path: String, session: ProtonSession): JsonObject =
        execute(Request.Builder().url(baseUrl + path).authorized(session).get().build())

    private fun Request.Builder.authorized(session: ProtonSession): Request.Builder =
        header("x-pm-uid", session.uid).header("Authorization", "Bearer ${session.accessToken}")

    private fun execute(request: Request): JsonObject {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                null
            }
            val code = json?.intOrNull("Code") ?: 0
            if (!response.isSuccessful || json == null || code !in SUCCESS_CODES) {
                val message = json?.stringOrNull("Error") ?: "HTTP ${response.code}"
                throw ProtonApiException(code, message, response.code)
            }
            return json
        }
    }

    private fun twoFactorFlags(auth: JsonObject): Int {
        val nested = auth["2FA"]?.let { runCatching { it.jsonObject }.getOrNull() }
        return nested?.intOrNull("Enabled") ?: auth.intOrNull("TwoFactor") ?: 0
    }

    private fun JsonObject.stringOrNull(key: String): String? = this[key]?.let {
        runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
    }

    private fun JsonObject.string(key: String): String =
        stringOrNull(key) ?: throw ProtonApiException(0, "Missing \"$key\" in server response")

    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.let {
        runCatching { it.jsonPrimitive.intOrNull }.getOrNull()
    }

    private fun JsonObject.int(key: String): Int =
        intOrNull(key) ?: throw ProtonApiException(0, "Missing \"$key\" in server response")

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        const val DEFAULT_BASE_URL = "https://drive-api.proton.me"
        const val APP_VERSION = "external-drive-marknotes@0.1.0-alpha"
        private const val TOTP = 1
        private val SUCCESS_CODES = setOf(0, 1000, 1001)
        private val JSON = "application/json".toMediaType()
    }
}
