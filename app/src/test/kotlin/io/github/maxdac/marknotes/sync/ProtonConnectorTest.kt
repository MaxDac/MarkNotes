package io.github.maxdac.marknotes.sync

import io.github.maxdac.marknotes.core.auth.ProtonAccountSessionStorage
import io.github.maxdac.marknotes.core.auth.ProtonAuthClient
import io.github.maxdac.marknotes.core.auth.ProtonSession
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.ProtonSessionSource
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteDriveStoreFactory
import io.github.maxdac.marknotes.core.sync.RemoteEntry
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtonConnectorTest {
    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private val storage = MemoryStorage()
    private val factory = RecordingFactory()
    private val events = mutableListOf<Pair<DiagnosticCode, Throwable?>>()
    private val diagnostics = DiagnosticReporter { code, failure -> events += code to failure }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = Files.createTempDirectory("proton-drive").toFile()
        cache(ProtonConnector.ACCOUNT_ID)
        cache("b")
        listOf("cache.sqlite", "cache.sqlite-wal", "cache.sqlite-shm").forEach {
            cacheDir.resolve(it).writeText("legacy")
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    private fun cache(id: String): File = ProtonConnector.accountCacheDir(cacheDir, id).apply {
        mkdirs()
        listOf("cache.sqlite", "cache.sqlite-wal", "cache.sqlite-shm").forEach { resolve(it).writeText(id) }
    }

    private fun connector() = ProtonConnector(
        sessionStorage = storage,
        authClient = ProtonAuthClient(baseUrl = server.url("/").toString().trimEnd('/'), appVersion = APP_VERSION),
        factory = { factory },
        cacheDir = cacheDir,
        diagnostics = diagnostics
    )

    @Test
    fun accountsHaveDistinctSourcesStoresAndCachesAfterRestart() = runTest {
        val connector = connector()
        assertFalse(connector.singleAccount)
        assertTrue(connector.needsSignInScreen())
        val a = connector.store(ACCOUNT)
        val b = connector.store(ACCOUNT.copy(id = "b"))
        assertNotSame(a, b)
        assertSame(a, connector.store(ACCOUNT))
        assertEquals(listOf(SESSION, SESSION_B), factory.sources.map { it.current })
        assertNotEquals(ProtonConnector.accountCacheDir(cacheDir, ACCOUNT.id), cache("b"))

        val restarted = connector()
        restarted.store(ACCOUNT)
        restarted.store(ACCOUNT.copy(id = "b"))
        assertEquals(listOf(SESSION, SESSION_B), factory.sources.takeLast(2).map { it.current })
        assertNull(restarted.takeSignedInAccount())
    }

    @Test
    fun loginGetsDistinctStableLocalIdsAndReloginRetiresOnlyItsOwnSource() = runTest {
        val connector = connector()
        connector.store(ACCOUNT)
        val b = connector.store(ACCOUNT.copy(id = "b"))
        val old = factory.sources.first()
        connector.onLoggedIn(SESSION.copy(uid = "new-uid"))
        assertFalse(connector.needsSignInScreen())
        assertEquals(ACCOUNT.id, connector.takeSignedInAccount()?.id)
        assertTrue(connector.needsSignInScreen())
        assertNull(old.current)
        old.invalidate()
        assertEquals("new-uid", storage.sessions[ACCOUNT.id]?.uid)
        assertSame(b, connector.store(ACCOUNT.copy(id = "b")))
        assertTrue(factory.created.first().closed)
        assertFalse(factory.created[1].closed)
        assertNotSame(factory.created.first(), connector.store(ACCOUNT))

        connector.onLoggedIn(SESSION.copy(uid = "c", userId = "user-c", username = "third"))
        val c = connector.takeSignedInAccount()!!
        assertNotEquals(ACCOUNT.id, c.id)
        assertNotEquals("b", c.id)
        assertEquals(
            c.id,
            connector().run {
                onLoggedIn(SESSION.copy(uid = "c2", userId = "user-c", username = "third"))
                takeSignedInAccount()?.id
            }
        )
    }

    @Test
    fun signOutRevokesOnlySelectedAccountAndDeletesItsSqliteSidecars() = runTest {
        server.enqueue(MockResponse().setBody("""{"Code":1000}"""))
        val connector = connector()
        connector.store(ACCOUNT)
        val b = connector.store(ACCOUNT.copy(id = "b"))

        connector.signOut(ACCOUNT.id)

        val logout = server.takeRequest()
        assertEquals("DELETE", logout.method)
        assertEquals("/core/v4/auth", logout.path)
        assertEquals(APP_VERSION, logout.getHeader("x-pm-appversion"))
        assertEquals(SESSION.uid, logout.getHeader("x-pm-uid"))
        assertNull(storage.sessions[ACCOUNT.id])
        assertNull(factory.sources.first().current)
        assertTrue(factory.created.first().closed)
        assertFalse(ProtonConnector.accountCacheDir(cacheDir, ACCOUNT.id).exists())
        assertFalse(cacheDir.resolve("cache.sqlite-wal").exists())
        assertFalse(cacheDir.resolve("cache.sqlite-shm").exists())
        assertEquals(3, ProtonConnector.accountCacheDir(cacheDir, "b").listFiles()!!.size)
        assertEquals(SESSION_B, storage.sessions["b"])
        assertSame(b, connector.store(ACCOUNT.copy(id = "b")))
        assertTrue(events.isEmpty())
    }

    @Test
    fun failedLogoutStillClearsOnlySelectedAccount() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"Code":2000,"Error":"down"}"""))
        val connector = connector()
        connector.store(ACCOUNT)
        connector.signOut(ACCOUNT.id)
        assertNull(storage.sessions[ACCOUNT.id])
        assertEquals(SESSION_B, storage.sessions["b"])
        assertEquals(listOf(DiagnosticCode.SIGN_OUT), events.map { it.first })
    }

    @Test
    fun failedCloseKeepsCacheForRetryRatherThanDeletingAnOpenDatabase() = runTest {
        server.enqueue(MockResponse().setBody("""{"Code":1000}"""))
        val connector = connector()
        connector.store(ACCOUNT)
        factory.created.single().closeFailure = IOException("close failed")
        try {
            connector.signOut(ACCOUNT.id)
            error("Must report cleanup failure")
        } catch (expected: IOException) {
            assertTrue(ProtonConnector.accountCacheDir(cacheDir, ACCOUNT.id).exists())
        }
        assertEquals(listOf(DiagnosticCode.SDK_ERROR), events.map { it.first })
        factory.created.single().closeFailure = null
        connector.signOut(ACCOUNT.id)
        assertFalse(ProtonConnector.accountCacheDir(cacheDir, ACCOUNT.id).exists())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancelledClosePropagatesWithoutErrorReport() = runTest {
        server.enqueue(MockResponse().setBody("""{"Code":1000}"""))
        val connector = connector()
        connector.store(ACCOUNT)
        factory.created.single().closeFailure = CancellationException("cancelled")
        try {
            connector.signOut(ACCOUNT.id)
            error("Must propagate cancellation")
        } catch (expected: CancellationException) {
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun deleteLocalDataRevokesAllSessionsAndRemovesAllCachesIncludingOrphans() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"Code":1000}""")) }
        val connector = connector()
        connector.store(ACCOUNT)
        connector.store(ACCOUNT.copy(id = "b"))
        cache("orphan")
        connector.deleteLocalData()
        assertEquals(2, server.requestCount)
        assertTrue(storage.sessions.isEmpty())
        assertTrue(factory.created.all { it.closed })
        assertFalse(cacheDir.exists())
        connector.deleteLocalData()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun concurrentRefreshesCoalescePerAccountButNeverShareTokens() = runTest {
        repeat(2) {
            server.enqueue(
                MockResponse().setBody(
                    """{"Code":1000,"AccessToken":"new-$it","RefreshToken":"refresh-$it"}"""
                )
            )
        }
        val connector = connector()
        connector.store(ACCOUNT)
        connector.store(ACCOUNT.copy(id = "b"))
        val a = factory.sources[0]
        val b = factory.sources[1]
        val results = listOf(
            async { a.refresh(SESSION.accessToken) },
            async { a.refresh(SESSION.accessToken) },
            async { b.refresh(SESSION_B.accessToken) }
        ).map { it.await() }
        assertEquals(2, server.requestCount)
        assertEquals(results[0], results[1])
        assertEquals(SESSION.uid, results[0]?.uid)
        assertEquals(SESSION_B.uid, results[2]?.uid)
        assertNotEquals(results[0]?.accessToken, results[2]?.accessToken)
        assertEquals(a.current, storage.sessions[ACCOUNT.id])
        assertEquals(b.current, storage.sessions["b"])
        val uids = List(2) { server.takeRequest().getHeader("x-pm-uid") }.toSet()
        assertEquals(setOf(SESSION.uid, SESSION_B.uid), uids)
    }

    @Test
    fun signOutWaitsForRefreshThenRevokesRotatedSessionWithoutResurrection() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"Code":1000,"AccessToken":"rotated","RefreshToken":"rotated-r"}"""
            )
        )
        server.enqueue(MockResponse().setBody("""{"Code":1000}"""))
        val connector = connector()
        connector.store(ACCOUNT)
        val source = factory.sources.single()
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { source.refresh(SESSION.accessToken) }
        val signOut = async { connector.signOut(ACCOUNT.id) }
        refresh.await()
        signOut.await()
        assertNull(source.current)
        assertNull(source.refresh("rotated"))
        assertNull(storage.sessions[ACCOUNT.id])
        assertEquals(2, server.requestCount)
    }

    @Test
    fun revokedRefreshRetiresOnlyItsAccountAndNextUseClosesIt() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"Code":8002,"Error":"revoked"}"""))
        val connector = connector()
        connector.store(ACCOUNT)
        val b = connector.store(ACCOUNT.copy(id = "b"))
        try {
            factory.sources.first().refresh(SESSION.accessToken)
            error("Must require login")
        } catch (expected: RemoteUnavailableException) {
            assertNull(storage.sessions[ACCOUNT.id])
        }
        assertFalse(factory.created.first().closed)
        try {
            connector.store(ACCOUNT)
            error("Must require login")
        } catch (expected: RemoteUnavailableException) {
            assertTrue(factory.created.first().closed)
            assertFalse(ProtonConnector.accountCacheDir(cacheDir, ACCOUNT.id).exists())
        }
        assertSame(b, connector.store(ACCOUNT.copy(id = "b")))
    }

    @Test
    fun transientRefreshFailurePreservesBothSessionsAndAllowsRetry() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"Code":2000,"Error":"slow down"}"""))
        server.enqueue(MockResponse().setBody("""{"Code":1000,"AccessToken":"retry","RefreshToken":"retry-r"}"""))
        val connector = connector()
        connector.store(ACCOUNT)
        val source = factory.sources.single()
        try {
            source.refresh(SESSION.accessToken)
            error("Must report transient failure")
        } catch (expected: RemoteUnavailableException) {
            assertEquals(SESSION, source.current)
            assertEquals(SESSION, storage.sessions[ACCOUNT.id])
            assertEquals(SESSION_B, storage.sessions["b"])
        }
        assertEquals("retry", source.refresh(SESSION.accessToken)?.accessToken)
    }

    @Test
    fun folderChangeRetiresOnlyItsOwnStoreAndUnknownIdsNeverBorrowCredentials() = runTest {
        val connector = connector()
        connector.store(ACCOUNT)
        val b = connector.store(ACCOUNT.copy(id = "b"))
        connector.store(ACCOUNT.copy(remoteFolder = "Another"))
        assertTrue(factory.created.first().closed)
        assertSame(b, connector.store(ACCOUNT.copy(id = "b")))
        try {
            connector.store(ACCOUNT.copy(id = "unknown"))
            error("Unknown account must require login")
        } catch (expected: RemoteUnavailableException) {
            assertEquals(3, factory.created.size)
        }
        connector.signOut("../b")
        assertEquals(SESSION_B, storage.sessions["b"])
        assertTrue(ProtonConnector.accountCacheDir(cacheDir, "b").exists())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun invalidatedAccountRestoresItsStableIdOnLoginAfterRestart() = runTest {
        val connector = connector()
        connector.store(ACCOUNT)
        factory.sources.single().invalidate()
        assertNull(storage.sessions[ACCOUNT.id])
        assertEquals(SESSION.userId, storage.userIds[ACCOUNT.id])
        val restarted = connector()
        restarted.onLoggedIn(SESSION.copy(uid = "replacement"))
        assertEquals(ACCOUNT.id, restarted.takeSignedInAccount()?.id)
        assertEquals("replacement", storage.sessions[ACCOUNT.id]?.uid)
        assertEquals(SESSION_B, storage.sessions["b"])
    }

    private class MemoryStorage : ProtonAccountSessionStorage {
        val sessions = mutableMapOf(ProtonConnector.ACCOUNT_ID to SESSION, "b" to SESSION_B)
        val userIds = sessions.mapValues { it.value.userId }.toMutableMap()
        override fun loadAll() = sessions.toMap()
        override fun loadUserIds() = userIds.toMap()
        override fun save(accountId: String, session: ProtonSession) {
            sessions[accountId] = session
            userIds[accountId] = session.userId
        }
        override fun invalidate(accountId: String) {
            sessions.remove(accountId)
        }
        override fun remove(accountId: String) {
            sessions.remove(accountId)
            userIds.remove(accountId)
        }
    }

    private class RecordingFactory : RemoteDriveStoreFactory {
        val created = mutableListOf<ClosableStore>()
        val sources = mutableListOf<ProtonSessionSource>()
        override suspend fun create(sessions: ProtonSessionSource, remoteFolderName: String): RemoteDriveStore {
            sources += sessions
            return ClosableStore().also { created += it }
        }
    }

    private class ClosableStore : RemoteDriveStore, AutoCloseable {
        var closed = false
        var closeFailure: Exception? = null
        override fun close() {
            closeFailure?.let { throw it }
            closed = true
        }
        override suspend fun snapshot(): List<RemoteEntry> = emptyList()
        override suspend fun download(entry: RemoteEntry): ByteArray = error("unused")
        override suspend fun uploadNew(
            path: String,
            bytes: ByteArray,
            modifiedMillis: Long
        ): RemoteEntry = error("unused")
        override suspend fun uploadRevision(entry: RemoteEntry, bytes: ByteArray, modifiedMillis: Long): RemoteEntry =
            error("unused")
        override suspend fun createFolder(path: String): RemoteEntry = error("unused")
        override suspend fun delete(entry: RemoteEntry) = Unit
    }

    private companion object {
        const val APP_VERSION = "external-drive-marknotes@0.1.0-alpha"
        val SESSION = ProtonSession("uid", "access", "refresh", "me", userId = "user-a")
        val SESSION_B = ProtonSession("uid-b", "access-b", "refresh-b", "second", userId = "user-b")
        val ACCOUNT = DriveAccount(
            id = ProtonConnector.ACCOUNT_ID,
            provider = DriveProvider.PROTON,
            label = "me",
            remoteFolder = "MarkNotes",
            createRootFolder = false
        )
    }
}
