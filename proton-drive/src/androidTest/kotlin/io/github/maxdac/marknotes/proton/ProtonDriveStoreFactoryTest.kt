package io.github.maxdac.marknotes.proton

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.sync.ProtonSessionSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads the SDK's native library and checks that its requests go through the app's session: with an
 * invalid token, protoncore must ask the session source to refresh. Needs network access.
 */
@RunWith(AndroidJUnit4::class)
class ProtonDriveStoreFactoryTest {
    private class FakeSessions : ProtonSessionSource {
        val refreshed = mutableListOf<String>()
        override var current: ProtonSession? =
            ProtonSession(
                uid = "uid",
                accessToken = "invalid-token",
                refreshToken = "r",
                username = "test",
                userId = "user"
            )

        override suspend fun refresh(staleAccessToken: String): ProtonSession? {
            refreshed += staleAccessToken
            current = null
            return null
        }

        override suspend fun invalidate() {
            current = null
        }
    }

    @Test
    fun routesSdkRequestsThroughTheSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessions = FakeSessions()
        val store = ProtonDriveStoreFactory(context).create(sessions, "MarkNotes") as ProtonDriveStore
        store.use {
            try {
                it.snapshot()
                fail("An invalid token must not list the drive")
            } catch (expected: Exception) {
                assertEquals(listOf("invalid-token"), sessions.refreshed)
                assertNull(sessions.current)
            }
        }
    }
}
