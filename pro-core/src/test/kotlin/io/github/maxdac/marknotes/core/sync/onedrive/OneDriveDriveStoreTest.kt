package io.github.maxdac.marknotes.core.sync.onedrive

import io.github.maxdac.marknotes.core.sync.RemoteChangedException
import io.github.maxdac.marknotes.core.sync.RemoteEntry
import java.io.IOException
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

    private fun file(id: String, name: String, parent: String, cTag: String, modifiedMillis: Long? = null) =
        """{"id":"$id","name":"$name","file":{},"cTag":"$cTag","eTag":"e-$cTag",""" +
            """"parentReference":{"id":"$parent"},"lastModifiedDateTime":"2024-01-01T00:00:00Z"""" +
            (
                modifiedMillis?.let {
                    ""","fileSystemInfo":{"lastModifiedDateTime":"${java.time.Instant.ofEpochMilli(it)}"}"""
                } ?: ""
                ) + "}"

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
        route("PATCH /v1.0/me/drive/items/n4", file("n4", "My Note.md", "f1", "c4", 0L))
        val created = store.uploadNew("Work/My Note.md", "hi".toByteArray(), 0L)
        assertEquals("c4", created.version)
        assertEquals("Work/My Note.md", created.path)

        route("PUT /v1.0/me/drive/items/n2/content", file("n2", "Plan.md", "f1", "c5"))
        route("PATCH /v1.0/me/drive/items/n2", file("n2", "Plan.md", "f1", "c5", 0L))
        val updated = store.uploadRevision(entries.getValue("Work/Plan.md"), "new".toByteArray(), 0L)
        assertEquals("c5", updated.version)
        val put = requests.last { it.method == "PUT" }
        assertEquals("e-c2", put.getHeader("If-Match"))
        assertEquals("new", put.body.readUtf8())
    }

    @Test
    fun epochZeroIsPreservedRatherThanReplacedByTheUploadTime() = runTest {
        store.snapshot()
        route(
            "PUT /v1.0/me/drive/items/root:/Copy.md:/content?@microsoft.graph.conflictBehavior=fail",
            file("copy", "Copy.md", "root", "c-copy")
        )
        route("PATCH /v1.0/me/drive/items/copy", file("copy", "Copy.md", "root", "c-copy", 0L))

        val created = store.uploadNew("Copy.md", "losing note".toByteArray(), 0L)

        assertEquals(0L, created.modifiedMillis)
        assertTrue(requests.last().body.readUtf8().contains("1970-01-01T00:00:00Z"))
        assertEquals("e-c-copy", requests.last().getHeader("If-Match"))
    }

    @Test
    fun uploadsStampTheLocalEditTimeAndSnapshotsReadIt() = runTest {
        val entries = store.snapshot().associateBy { it.path }
        route("PUT /v1.0/me/drive/items/n2/content", file("n2", "Plan.md", "f1", "c5"))
        route(
            "PATCH /v1.0/me/drive/items/n2",
            """{"id":"n2","name":"Plan.md","file":{},"cTag":"c5","eTag":"e-c5b","parentReference":{"id":"f1"},""" +
                """"lastModifiedDateTime":"2024-06-01T00:00:00Z",""" +
                """"fileSystemInfo":{"lastModifiedDateTime":"2024-05-01T10:00:00Z"}}"""
        )
        val edited = java.time.Instant.parse("2024-05-01T10:00:00Z").toEpochMilli()

        val updated = store.uploadRevision(entries.getValue("Work/Plan.md"), "new".toByteArray(), edited)

        assertEquals(edited, updated.modifiedMillis)
        val patch = requests.last()
        assertEquals("PATCH", patch.method)
        assertEquals("e-c5", patch.getHeader("If-Match"))
        assertTrue(patch.body.readUtf8().contains("\"lastModifiedDateTime\":\"2024-05-01T10:00:00Z\""))
        // The PATCH changed the eTag: a later delete must use the new one.
        route("DELETE /v1.0/me/drive/items/n2", "", 204)
        store.delete(updated)
        assertEquals("e-c5b", requests.last().getHeader("If-Match"))
    }

    @Test
    fun deletingAFileChangedOnTheDriveFails() = runTest {
        val entries = store.snapshot().associateBy { it.path }
        route("DELETE /v1.0/me/drive/items/n1", """{"error":{"message":"precondition"}}""", 412)

        val error = runCatching { store.delete(entries.getValue("Todo.md")) }.exceptionOrNull()

        assertTrue(error is RemoteChangedException)
        assertEquals("e-c1", requests.last().getHeader("If-Match"))
    }

    @Test
    fun caseOnlyFileRenameUsesStableIdentityIfMatchAndNoClobber() = runTest {
        val entries = store.snapshot().associateBy { it.path }
        route(
            "PATCH /v1.0/me/drive/items/n1?@microsoft.graph.conflictBehavior=fail",
            file("n1", "TODO.md", "root", "c1")
        )

        val renamed = store.rename(entries.getValue("Todo.md"), "TODO.md")

        assertEquals("TODO.md", renamed.path)
        assertEquals("n1", renamed.id)
        assertEquals("c1", renamed.version)
        assertEquals("e-c1", requests.last().getHeader("If-Match"))
        assertEquals("""{"name":"TODO.md"}""", requests.last().body.readUtf8())
        route("GET /v1.0/delta1", """{"value":[],"@odata.deltaLink":"$base/delta2"}""")
        assertTrue(store.snapshot().any { it.path == "TODO.md" && it.id == "n1" })
    }

    @Test
    fun aRacingEditPreventsAFileRename() = runTest {
        val entry = store.snapshot().first { it.path == "Todo.md" }
        route(
            "PATCH /v1.0/me/drive/items/n1?@microsoft.graph.conflictBehavior=fail",
            """{"error":{"message":"changed"}}""",
            412
        )

        val error = runCatching { store.rename(entry, "TODO.md") }.exceptionOrNull()

        assertTrue(error is RemoteChangedException)
        assertEquals("e-c1", requests.last().getHeader("If-Match"))
    }

    @Test
    fun aConflictingRenameDoesNotRetryWithAnOverwriteOrTemporaryName() = runTest {
        val entry = store.snapshot().first { it.path == "Todo.md" }
        route(
            "PATCH /v1.0/me/drive/items/n1?@microsoft.graph.conflictBehavior=fail",
            """{"error":{"message":"name conflict"}}""",
            409
        )
        val before = requests.size

        val error = runCatching { store.rename(entry, "TODO.md") }.exceptionOrNull()

        assertTrue(error is GraphException && error.httpStatus == 409)
        assertEquals(before + 1, requests.size)
    }

    @Test
    fun aRenameWithoutTheSnapshotsIdentityAndVersionFailsBeforeMutation() = runTest {
        val entry = store.snapshot().first { it.path == "Todo.md" }
        val before = requests.size

        val changed = runCatching { store.rename(entry.copy(version = "other"), "TODO.md") }.exceptionOrNull()
        val missing = runCatching { store.rename(entry.copy(id = "missing"), "TODO.md") }.exceptionOrNull()

        assertTrue(changed is RemoteChangedException)
        assertTrue(missing is RemoteChangedException)
        assertEquals(before, requests.size)
    }

    @Test
    fun aRenameWithoutAnEtagFailsBeforeMutation() = runTest {
        route(
            "GET /v1.0/me/drive/items/root/children",
            """{"value":[{"id":"n1","name":"Todo.md","file":{},"cTag":"c1"}]}"""
        )
        val entry = store.snapshot().single()
        val before = requests.size

        val error = runCatching { store.rename(entry, "TODO.md") }.exceptionOrNull()

        assertTrue(error is RemoteChangedException)
        assertEquals(before, requests.size)
    }

    @Test
    fun aFolderRenameFailsWithoutATreeGuardOrProviderCall() = runTest {
        val before = requests.size

        val error = runCatching {
            store.rename(RemoteEntry("Work", "folder", true, "", 0L), "WORK")
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(before, requests.size)
    }

    @Test
    fun deletingAFolderFailsWithoutSendingARecursiveDelete() = runTest {
        val entries = store.snapshot().associateBy { it.path }
        val before = requests.size

        val error = runCatching { store.delete(entries.getValue("Work")) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("Safe empty-folder deletion"))
        assertEquals(before, requests.size)
    }

    @Test
    fun deletingAMissingFileIsAlreadyGone() = runTest {
        val entries = store.snapshot().associateBy { it.path }
        store.delete(entries.getValue("Todo.md"))
        assertEquals("e-c1", requests.last().getHeader("If-Match"))
    }

    @Test
    fun plannedFolderCreationRejectsAnExistingFolder() = runTest {
        store.snapshot()
        val before = requests.size

        val error = runCatching { store.createFolder("Work") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(before, requests.size)
    }

    @Test
    fun plannedFolderCreationDoesNotAdoptAConcurrentFolder() = runTest {
        store.snapshot()
        route("POST /v1.0/me/drive/items/root/children", """{"error":{"message":"conflict"}}""", 409)
        val before = requests.size

        val error = runCatching { store.createFolder("New") }.exceptionOrNull()

        assertTrue(error is GraphException && error.httpStatus == 409)
        assertEquals(before + 1, requests.size)
        assertTrue(requests.last().body.readUtf8().contains("\"@microsoft.graph.conflictBehavior\":\"fail\""))
    }

    @Test
    fun plannedFolderCreationUsesExistingParentsButCreatesTheLeaf() = runTest {
        store.snapshot()
        route("POST /v1.0/me/drive/items/f1/children", folder("new", "New", "f1"), 201)

        val created = store.createFolder("Work/New")

        assertEquals("Work/New", created.path)
        assertEquals("new", created.id)
        assertTrue(requests.last().body.readUtf8().contains("\"name\":\"New\""))
    }

    @Test
    fun newFileCreationDoesNotRetryAConflictAsAnOverwrite() = runTest {
        store.snapshot()
        route(
            "PUT /v1.0/me/drive/items/root:/Todo.md:/content?@microsoft.graph.conflictBehavior=fail",
            """{"error":{"message":"conflict"}}""",
            409
        )
        val before = requests.size

        val error = runCatching { store.uploadNew("Todo.md", "new".toByteArray(), 0L) }.exceptionOrNull()

        assertTrue(error is GraphException && error.httpStatus == 409)
        assertEquals(before + 1, requests.size)
    }

    @Test
    fun aFailedModificationTimeWriteIsAnError() = runTest {
        val entries = store.snapshot().associateBy { it.path }
        route("PUT /v1.0/me/drive/items/n2/content", file("n2", "Plan.md", "f1", "c5"))
        route("PATCH /v1.0/me/drive/items/n2", """{"error":{"message":"timestamp failed"}}""", 503)

        val error = runCatching {
            store.uploadRevision(entries.getValue("Work/Plan.md"), "new".toByteArray(), 1234L)
        }.exceptionOrNull()

        assertTrue(error is GraphException && error.httpStatus == 503)
    }

    @Test
    fun aNewConflictCopyWithAnUnstoredEditTimeIsNotReportedAsSuccessful() = runTest {
        store.snapshot()
        route(
            "PUT /v1.0/me/drive/items/root:/Copy.md:/content?@microsoft.graph.conflictBehavior=fail",
            file("copy", "Copy.md", "root", "c-copy")
        )
        route("PATCH /v1.0/me/drive/items/copy", """{"error":{"message":"timestamp failed"}}""", 503)

        val error = runCatching { store.uploadNew("Copy.md", "losing note".toByteArray(), 1234L) }.exceptionOrNull()

        assertTrue(error is GraphException && error.httpStatus == 503)
        assertEquals("PATCH", requests.last().method)
    }

    @Test
    fun aSuccessfulResponseWithTheWrongModificationTimeIsAnError() = runTest {
        val entry = store.snapshot().first { it.path == "Todo.md" }
        route("PUT /v1.0/me/drive/items/n1/content", file("n1", "Todo.md", "root", "c2"))
        route("PATCH /v1.0/me/drive/items/n1", file("n1", "Todo.md", "root", "c2"))

        val error = runCatching { store.uploadRevision(entry, "new".toByteArray(), 1234L) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("did not preserve the edit time"))
    }

    @Test
    fun mutationsWithoutAnEtagDoNotFallBackToUnconditionalRequests() = runTest {
        route(
            "GET /v1.0/me/drive/items/root/children",
            """{"value":[{"id":"n1","name":"Todo.md","file":{},"cTag":"c1"}]}"""
        )
        val entry = store.snapshot().single()
        val before = requests.size

        val upload = runCatching { store.uploadRevision(entry, "new".toByteArray(), 0L) }.exceptionOrNull()
        val delete = runCatching { store.delete(entry) }.exceptionOrNull()

        assertTrue(upload is IOException)
        assertTrue(delete is IOException)
        assertEquals(before, requests.size)
    }

    @Test
    fun aFailedTraversalDoesNotKeepTheDeltaLink() = runTest {
        val children = routes.remove("GET /v1.0/me/drive/items/f1/children")!!
        assertTrue(runCatching { store.snapshot() }.isFailure)
        routes["GET /v1.0/me/drive/items/f1/children"] = children

        assertEquals(3, store.snapshot().size)
        assertEquals(2, requests.count { it.path == "/v1.0/me/drive/root/delta?token=latest" })
        assertTrue(requests.none { it.path == "/v1.0/delta1" })
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
