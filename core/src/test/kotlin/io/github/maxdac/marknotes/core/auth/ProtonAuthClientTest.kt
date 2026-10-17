package io.github.maxdac.marknotes.core.auth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class ProtonAuthClientTest {
    private val password = "hunter2-correct"
    private lateinit var server: MockWebServer
    private lateinit var client: ProtonAuthClient
    private val modulus = ProtonSrp.decodeModulus(SrpTestFixtures.SIGNED_MODULUS)
    private val salt = SrpTestFixtures.randomSalt()
    private val srpServer = SrpTestFixtures.Server(modulus, ProtonSrp.hashPassword(4, password, salt, modulus))
    private var twoFactorEnabled = 0
    private var humanVerification = false
    private val seenAppVersions = mutableListOf<String?>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seenAppVersions += request.getHeader("x-pm-appversion")
                val body = request.body.readUtf8()
                return when (request.path) {
                    "/auth/v4/sessions" -> ok("""{"Code":1000,"UID":"unauth","AccessToken":"ua","RefreshToken":"ur"}""")
                    "/core/v4/auth/info" -> if (humanVerification) {
                        MockResponse().setResponseCode(422).setBody("""{"Code":9001,"Error":"Human verification required"}""")
                    } else {
                        ok(
                            """{"Code":1000,"Version":4,"Modulus":${JsonPrimitive(SrpTestFixtures.SIGNED_MODULUS)},""" +
                                """"ServerEphemeral":"${b64(srpServer.serverEphemeral)}","Salt":"$salt","SRPSession":"srp-session"}""",
                        )
                    }
                    "/core/v4/auth" -> {
                        val json = Json.parseToJsonElement(body).jsonObject
                        val proof = srpServer.verify(
                            Base64.getDecoder().decode(json["ClientEphemeral"]!!.jsonPrimitive.content),
                            Base64.getDecoder().decode(json["ClientProof"]!!.jsonPrimitive.content),
                        )
                        if (proof == null) {
                            MockResponse().setResponseCode(422).setBody("""{"Code":8002,"Error":"Incorrect login credentials"}""")
                        } else {
                            ok(
                                """{"Code":1000,"UID":"uid-1","AccessToken":"at","RefreshToken":"rt","UserID":"user",""" +
                                    """"ServerProof":"${b64(proof)}","PasswordMode":1,"2FA":{"Enabled":$twoFactorEnabled}}""",
                            )
                        }
                    }
                    "/core/v4/auth/2fa" -> if (body.contains("123456")) {
                        ok("""{"Code":1000,"Scopes":["full"]}""")
                    } else {
                        MockResponse().setResponseCode(422).setBody("""{"Code":8002,"Error":"Incorrect code"}""")
                    }
                    "/auth/v4/refresh" -> ok("""{"Code":1000,"AccessToken":"at2","RefreshToken":"rt2"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = ProtonAuthClient(baseUrl = server.url("").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loginWithoutTwoFactor() = runTest {
        val result = client.login("alice", password)
        assertTrue(result is LoginResult.Success)
        val session = (result as LoginResult.Success).session
        assertEquals("uid-1", session.uid)
        assertEquals("at", session.accessToken)
        assertTrue(seenAppVersions.all { it == ProtonAuthClient.APP_VERSION })
    }

    @Test
    fun loginWithTotp() = runTest {
        twoFactorEnabled = 1
        val result = client.login("alice", password)
        assertTrue(result is LoginResult.TwoFactorRequired)
        val session = client.submitTwoFactor((result as LoginResult.TwoFactorRequired).session, "123456")
        assertEquals("uid-1", session.uid)
    }

    @Test
    fun wrongPasswordReportsError() = runTest {
        try {
            client.login("alice", "nope")
            fail("Expected failure")
        } catch (e: ProtonApiException) {
            assertEquals(ProtonApiException.WRONG_PASSWORD, e.code)
        }
    }

    @Test
    fun humanVerificationIsReported() = runTest {
        humanVerification = true
        try {
            client.login("alice", password)
            fail("Expected failure")
        } catch (e: ProtonApiException) {
            assertTrue(e.isHumanVerificationRequired)
            assertTrue(e.userMessage.contains("captcha"))
        }
    }

    @Test
    fun refreshRotatesTokens() = runTest {
        val refreshed = client.refresh(ProtonSession("uid-1", "at", "rt", "alice"))
        assertEquals("at2", refreshed.accessToken)
        assertEquals("rt2", refreshed.refreshToken)
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
