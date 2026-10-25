package io.github.maxdac.marknotes.ui.login

import io.github.maxdac.marknotes.core.auth.ProtonAccountSessionStorage
import io.github.maxdac.marknotes.core.auth.ProtonAuthClient
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.sync.ProtonSessionSource
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteDriveStoreFactory
import io.github.maxdac.marknotes.sync.ProtonConnector
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val events = mutableListOf<Pair<DiagnosticCode, Throwable?>>()
    private val diagnostics = DiagnosticReporter { code, failure -> events += code to failure }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun failedLoginReportsAuthenticationAndPreservesTheUiError() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"Code":2000,"Error":"unavailable"}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"Code":8002,"Error":"wrong password"}"""))
            val client = ProtonAuthClient(baseUrl = server.url("/").toString().trimEnd('/'))
            val vm = LoginViewModel(connector(client), diagnostics)

            vm.login("private-account", "private-password")
            val state = withTimeout(10_000) { vm.state.first { it.error != null && !it.busy } }

            assertFalse(state.busy)
            assertFalse(state.done)
            assertEquals("Incorrect username or password.", state.error)
            assertEquals(listOf(DiagnosticCode.AUTHENTICATION), events.map { it.first })
        }
    }

    @Test
    fun cancellationIsNotRecordedOrShownAsAnAuthenticationFailure() = runBlocking {
        val http = OkHttpClient.Builder()
            .addInterceptor { throw CancellationException("cancelled private request") }
            .build()
        val vm = LoginViewModel(connector(ProtonAuthClient(httpClient = http)), diagnostics)

        vm.login("private-account", "private-password")
        val state = withTimeout(10_000) { vm.state.first { !it.busy } }

        assertNull(state.error)
        assertFalse(state.done)
        assertTrue(events.isEmpty())
    }

    private fun connector(client: ProtonAuthClient) = ProtonConnector(
        sessionStorage = object : ProtonAccountSessionStorage {
            override fun loadAll(): Map<String, ProtonSession> = emptyMap()
            override fun save(accountId: String, session: ProtonSession) = error("Not used")
            override fun remove(accountId: String) = Unit
        },
        authClient = client,
        factory = {
            object : RemoteDriveStoreFactory {
                override suspend fun create(sessions: ProtonSessionSource, remoteFolderName: String): RemoteDriveStore =
                    error("Not used")
            }
        },
        cacheDir = File("unused-login-test-cache"),
        diagnostics = diagnostics
    )
}
