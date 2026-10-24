package io.github.maxdac.marknotes.proton

import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.sync.ProtonSessionSource
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.Session
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonSessionBridgeTest {
    private val events = mutableListOf<Pair<DiagnosticCode, Throwable?>>()
    private val diagnostics = DiagnosticReporter { code, failure -> events += code to failure }
    private val session = Session.Authenticated(
        userId = UserId("private-user"),
        sessionId = SessionId("private-session"),
        accessToken = "private-token",
        refreshToken = "private-refresh-token",
        scopes = emptyList()
    )

    @Test
    fun swallowedRefreshFailureReportsAuthentication() = runTest {
        val failure = IOException("private URL and account")
        val bridge = ProtonSessionBridge(failingSessions(failure), diagnostics)

        assertFalse(bridge.refreshSession(session))

        assertEquals(listOf(DiagnosticCode.AUTHENTICATION to failure), events)
    }

    @Test
    fun cancellationPropagatesWithoutAnErrorEvent() = runTest {
        val cancellation = CancellationException("cancelled")
        val bridge = ProtonSessionBridge(failingSessions(cancellation), diagnostics)

        try {
            bridge.refreshSession(session)
            error("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals(cancellation, expected)
        }

        assertTrue(events.isEmpty())
    }

    private fun failingSessions(failure: Exception) = object : ProtonSessionSource {
        override val current: ProtonSession? = null
        override suspend fun refresh(staleAccessToken: String): ProtonSession? = throw failure
        override suspend fun invalidate() = Unit
    }
}
