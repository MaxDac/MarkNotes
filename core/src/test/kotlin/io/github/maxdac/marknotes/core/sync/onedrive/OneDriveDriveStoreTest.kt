package io.github.maxdac.marknotes.core.sync.onedrive

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OneDriveDriveStoreTest {
    private lateinit var server: MockWebServer
    private lateinit var store: OneDriveDriveStore
    private lateinit var base: String
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val key = "${request.method} ${request.path}"
                return routes[key]?.invoke() ?: MockResponse().setResponseCode(404)
                    .setBody("""{"error":{"message":"not found: $key"}}""")
            }
        }
        server.start()
        base = server.url("/v1.0").toString().trimEnd('/')
        val graph = GraphClient(OkHttpClient(), { "token" }, base)
        store = OneDriveDriveStore(graph, "Documents/MarkNotes")

        route("GET /v1.0/me/drive/root:/Documents/MarkNotes", folder("root", "MarkNotes", "x"))
        route("GET /v1.0/me/drive/root/delta?token=latest", """{"value":[],"@odata.deltaLink":"$base/delta1"}""")
        route(
            "GET /v1.0/me/drive/items/root/children",
            """{"value":[${folder("f1", "Work", "root")},${file("n1", "Todo.md", "root", "c1")}]}"""
        )
        route("GET /v1.0/me/drive/items/f1/children", """{"value":[${file("n2", "Plan.md", "f1", "c2")}]}""")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun route(key: String, body: String, code: Int = 200) {
        routes[key] = { MockResponse().setResponseCode(code).setBody(body) }
    }

    private fun folder(id: String, name: String, parent: String) =
        """{"id":"$id","name":"$name","folder":{"childCount":0},"parentReference":{"id":"$parent"},""" +
            """"lastModifiedDateTime":"2024-01-01T00:00:00Z"}"""

    private fun file(id: String, name: String, parent: String, cTag: String) =
        """{"id":"$id","name":"$name","file":{},"cTag":"$cTag","eTag":"e-$cTag",""" +
            """"parentReference":{"id":"$parent"},"lastModifiedDateTime":"2024-01-01T00:00:00Z"}"""

    @Test
    fun fullTraversalBuildsRelativePaths() = runTest {
        val entries = store.snapshot().associateBy { it.path }
        assertEquals(setOf("Work", "Todo.md", "Work/Plan.md"), entries.keys)
        assertEquals("c2", entries.getValue("Work/Plan.md").version)
        assertTrue(entries.getValue("Work").isFolder)
        assertEquals("Bearer token", requests.first().getHeader("Authorization"))
    }

    @Test
    fun deltaAppliesAddsRenamesAndDeletes() = runTest {
        store.snapshot()
        // Child listed before its renamed parent; a deleted note; an item outside the synced folder.
        route(
            "GET /v1.0/delta1",
            """{"value":[""" +
                file("n3", "New.md", "f1", "c3") + "," +
                folder("f1", "Projects", "root") + "," +
                """{"id":"n1","deleted":{},"parentReference":{"id":"root"}},""" +
                file("other", "Elsewhere.md", "unrelated", "c9") +
                """],"@odata.deltaLink":"$base/delta2"}"""
        )
        val paths = store.snapshot().map { it.path }.toSet()
        assertEquals(setOf("Projects", "Projects/Plan.md", "Projects/New.md"), paths)

        route("GET /v1.0/delta2", """{"value":[],"@odata.deltaLink":"$base/delta3"}""")
        assertEquals(paths, store.snapshot().map { it.path }.toSet())
    }

    @Test
    fun expiredDeltaTokenTriggersFullResync() = runTest {
        store.snapshot()
        route("GET /v1.0/delta1", """{"error":{"message":"resync"}}""", 410)
        assertEquals(3, store.snapshot().size)
    }

    @Test
    fun uploadsUseConflictBehaviorAndIfMatch() = runTest {
        val entries = store.snapshot().associateBy { it.path }
        // New files are addressed relative to the synced folder.
        route(
            "PUT /v1.0/me/drive/items/root:/Work/My%20Note.md:/content?@microsoft.graph.conflictBehavior=fail",
            file("n4", "My Note.md", "f1", "c4")
        )
        val created = store.uploadNew("Work/My Note.md", "hi".toByteArray())
        assertEquals("c4", created.version)
        assertEquals("Work/My Note.md", created.path)

        route("PUT /v1.0/me/drive/items/n2/content", file("n2", "Plan.md", "f1", "c5"))
        val updated = store.uploadRevision(entries.getValue("Work/Plan.md"), "new".toByteArray())
        assertEquals("c5", updated.version)
        val put = requests.last()
        assertEquals("e-c2", put.getHeader("If-Match"))
        assertEquals("new", put.body.readUtf8())
    }

    @Test
    fun manifestIsMissingUntilWritten() = runTest {
        assertNull(store.readManifest())
        route(
            "PUT /v1.0/me/drive/items/root:/.marknotes/manifest.json:/content",
            file("m", "manifest.json", "x", "c")
        )
        store.writeManifest("{}".toByteArray())
        route("GET /v1.0/me/drive/items/root:/.marknotes/manifest.json:/content", "{}")
        assertEquals("{}", store.readManifest()?.decodeToString())
    }

    @Test
    fun missingSyncFolderIsCreated() = runTest {
        routes.remove("GET /v1.0/me/drive/root:/Documents/MarkNotes")
        route("GET /v1.0/me/drive/root", folder("top", "root", ""))
        route("GET /v1.0/me/drive/root:/Documents", folder("docs", "Documents", "top"))
        route("POST /v1.0/me/drive/items/docs/children", folder("root", "MarkNotes", "docs"), 201)
        assertEquals(3, store.snapshot().size)
        val post = requests.single { it.method == "POST" }
        assertTrue(post.body.readUtf8().contains("\"name\":\"MarkNotes\""))
    }
}
