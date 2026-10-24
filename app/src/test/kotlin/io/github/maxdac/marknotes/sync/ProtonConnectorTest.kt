package io.github.maxdac.marknotes.sync

import io.github.maxdac.marknotes.core.auth.ProtonAuthClient
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.auth.SessionStorage
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.ProtonSessionSource
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteDriveStoreFactory
import io.github.maxdac.marknotes.core.sync.RemoteEntry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtonConnectorTest {
    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private val storage = MemorySessionStorage(SESSION)
    private val factory = RecordingFactory()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = Files.createTempDirectory("proton-drive").toFile()
        cacheDir.resolve("cache.sqlite").writeText("cache")
        cacheDir.resolve("cache.sqlite-wal").writeText("wal")
    }

    @After
    fun tearDown() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    private fun connector() = ProtonConnector(
        sessionStorage = storage,
        authClient = ProtonAuthClient(baseUrl = server.url("/").toString().trimEnd('/'), appVersion = APP_VERSION),
        factory = factory,
        cacheDir = cacheDir
    )

    @Test
    fun signOutRevokesTheSessionClosesTheStoresAndDeletesTheCache() = runTest {
        server.enqueue(MockResponse().setBody("""{"Code":1000}"""))
        val connector = connector()
        connector.store(ACCOUNT)

        connector.signOut(ProtonConnector.ACCOUNT_ID)

        val logout = server.takeRequest()
        assertEquals("DELETE", logout.method)
        assertEquals("/core/v4/auth", logout.path)
        assertEquals(APP_VERSION, logout.getHeader("x-pm-appversion"))
        assertEquals(SESSION.uid, logout.getHeader("x-pm-uid"))
        assertSessionAndCacheGone(connector)
    }

    @Test
    fun signOutDropsTheLocalDataWhenTheLogoutFails() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"Code":2000,"Error":"down"}"""))
        val connector = connector()
        connector.store(ACCOUNT)

        connector.signOut(ProtonConnector.ACCOUNT_ID)

        assertEquals(1, server.requestCount)
        assertSessionAndCacheGone(connector)
    }

    @Test
    fun deleteLocalDataAfterSignOutDeletesTheCacheWithoutCallingProton() = runTest {
        server.enqueue(MockResponse().setBody("""{"Code":1000}"""))
        val connector = connector()
        connector.store(ACCOUNT)
        connector.signOut(ProtonConnector.ACCOUNT_ID)
        cacheDir.mkdirs()
        cacheDir.resolve("cache.sqlite").writeText("cache")

        connector.deleteLocalData()

        assertEquals(1, server.requestCount)
        assertSessionAndCacheGone(connector)
    }

    @Test
    fun deleteLocalDataWithASessionRevokesItAndDeletesEverything() = runTest {
        server.enqueue(MockResponse().setBody("""{"Code":1000}"""))
        val connector = connector()
        connector.store(ACCOUNT)

        connector.deleteLocalData()

        assertEquals("/core/v4/auth", server.takeRequest().path)
        assertSessionAndCacheGone(connector)
    }

    @Test
    fun deleteLocalDataWithoutCacheOrSessionSucceeds() = runTest {
        storage.clear()
        cacheDir.deleteRecursively()
        val connector = connector()

        connector.deleteLocalData()

        assertEquals(0, server.requestCount)
        assertFalse(cacheDir.exists())
    }

    @Test
    fun aNewLoginGetsANewStoreAfterSignOut() = runTest {
        server.enqueue(MockResponse().setBody("""{"Code":1000}"""))
        val connector = connector()
        val first = connector.store(ACCOUNT)
        connector.signOut(ProtonConnector.ACCOUNT_ID)

        connector.onLoggedIn(SESSION.copy(uid = "uid-2"))
        val second = connector.store(ACCOUNT)

        assertNotSame(first, second)
        assertEquals(2, factory.created.size)
    }

    private fun assertSessionAndCacheGone(connector: ProtonConnector) {
        assertNull(storage.load())
        assertNull(connector.current)
        assertTrue(connector.needsSignInScreen())
        assertTrue(factory.created.isNotEmpty())
        assertTrue("every SDK store is closed", factory.created.all { it.closed })
        assertFalse("the SDK cache is deleted", cacheDir.exists())
    }

    private class MemorySessionStorage(private var session: ProtonSession?) : SessionStorage {
        override fun load() = session

        override fun save(session: ProtonSession) {
            this.session = session
        }

        override fun clear() {
            session = null
        }
    }

    private class RecordingFactory : RemoteDriveStoreFactory {
        val created = mutableListOf<ClosableStore>()

        override suspend fun create(sessions: ProtonSessionSource, remoteFolderName: String): RemoteDriveStore =
            ClosableStore().also { created += it }
    }

    private class ClosableStore :
        RemoteDriveStore,
        AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }

        override suspend fun snapshot(): List<RemoteEntry> = emptyList()

        override suspend fun download(entry: RemoteEntry): ByteArray = error("unused")

        override suspend fun uploadNew(path: String, bytes: ByteArray, modifiedMillis: Long): RemoteEntry =
            error("unused")

        override suspend fun uploadRevision(entry: RemoteEntry, bytes: ByteArray, modifiedMillis: Long): RemoteEntry =
            error("unused")

        override suspend fun createFolder(path: String): RemoteEntry = error("unused")

        override suspend fun delete(entry: RemoteEntry) = Unit
    }

    private companion object {
        const val APP_VERSION = "external-drive-marknotes@0.1.0-alpha"
        val SESSION = ProtonSession(uid = "uid", accessToken = "access", refreshToken = "refresh", username = "me")
        val ACCOUNT = DriveAccount(
            id = ProtonConnector.ACCOUNT_ID,
            provider = DriveProvider.PROTON,
            label = "me",
            remoteFolder = "MarkNotes",
            createRootFolder = false
        )
    }
}
