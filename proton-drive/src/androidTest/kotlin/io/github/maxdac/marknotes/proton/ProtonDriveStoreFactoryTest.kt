package io.github.maxdac.marknotes.proton

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.sync.ProtonSessionSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val cache = File(context.cacheDir, "proton-routing-test-${UUID.randomUUID()}")
        try {
            val store = ProtonDriveStoreFactory(context, cacheDirectory = cache)
                .create(sessions, "MarkNotes") as ProtonDriveStore
            store.use {
                try {
                    it.snapshot()
                    fail("An invalid token must not list the drive")
                } catch (expected: Exception) {
                    assertEquals(listOf("invalid-token"), sessions.refreshed)
                    assertNull(sessions.current)
                }
            }
        } finally {
            cache.deleteRecursively()
        }

        @Test
        fun separateNativeClientsUseSeparateCacheDatabases() = runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(context.cacheDir, "proton-isolation-test-${UUID.randomUUID()}")
            val a = root.resolve("a")
            val b = root.resolve("b")
            try {
                val sessionsA = FakeSessions()
                val sessionsB = FakeSessions().apply {
                    current = current!!.copy(uid = "uid-b", userId = "user-b")
                }
                val first = ProtonDriveStoreFactory(context, cacheDirectory = a)
                    .create(sessionsA, "MarkNotes") as ProtonDriveStore
                first.use {
                    val second = ProtonDriveStoreFactory(context, cacheDirectory = b)
                        .create(sessionsB, "MarkNotes") as ProtonDriveStore
                    second.use {
                        assertTrue(a.resolve("cache.sqlite").isFile)
                        assertTrue(b.resolve("cache.sqlite").isFile)
                        assertEquals("uid", sessionsA.current?.uid)
                        assertEquals("uid-b", sessionsB.current?.uid)
                    }
                    assertTrue(b.deleteRecursively())
                    assertTrue(a.resolve("cache.sqlite").isFile)
                }
                assertTrue(a.deleteRecursively())
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
