package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SyncEngineTest {
    private lateinit var dir: File
    private lateinit var local: LocalNoteStore
    private lateinit var remote: FakeRemoteDriveStore
    private lateinit var metadata: InMemorySyncMetadataStore
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sync").toFile()
        local = LocalNoteStore(dir)
        remote = FakeRemoteDriveStore()
        metadata = InMemorySyncMetadataStore()
        engine = SyncEngine(local, remote, metadata) { 1000L }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private suspend fun syncClean(): SyncResult {
        val result = engine.sync()
        assertEquals(emptyList<String>(), result.errors)
        return result
    }

    private suspend fun assertStable() {
        remote.calls.clear()
        val result = syncClean()
        assertEquals(emptyList<String>(), remote.calls.filter { !it.startsWith("download:") })
        assertEquals(0, result.uploaded + result.downloaded + result.deletedLocal + result.deletedRemote)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun firstSyncUploadsLocalNotes() = runTest {
        local.write("root.md", "root")
        local.write("a/b/deep.md", "deep")
        local.createFolder("empty")

        val result = syncClean()

        assertEquals(2, result.uploaded)
        assertEquals("root", remote.content("root.md"))
        assertEquals("deep", remote.content("a/b/deep.md"))
        assertTrue(remote.isFolder("empty"))
        assertStable()
    }

    @Test
    fun firstSyncDownloadsRemoteNotes() = runTest {
        remote.putFile("x/note.md", "remote")
        remote.putFolder("y")

        val result = syncClean()

        assertEquals(1, result.downloaded)
        assertEquals("remote", local.read("x/note.md"))
        assertTrue(local.isFolder("y"))
        assertStable()
    }

    @Test
    fun ignoresNonMarkdownFiles() = runTest {
        local.write("image.png", "png")
        remote.putFile("doc.pdf", "pdf")

        syncClean()

        assertFalse(remote.exists("image.png"))
        assertFalse(local.exists("doc.pdf"))
    }

    @Test
    fun identicalContentOnBothSidesIsNotAConflict() = runTest {
        local.write("n.md", "same")
        remote.putFile("n.md", "same")

        val result = syncClean()

        assertTrue(result.conflicts.isEmpty())
        assertStable()
    }

    @Test
    fun differentContentWithoutHistoryIsAConflict() = runTest {
        local.write("n.md", "local")
        remote.putFile("n.md", "remote")

        val result = syncClean()

        assertEquals(1, result.conflicts.size)
        assertEquals("local", result.conflicts[0].localText)
        assertEquals("remote", result.conflicts[0].remoteText)
        assertEquals("local", local.read("n.md"))
        assertEquals("remote", remote.content("n.md"))
    }

    @Test
    fun localEditUploadsRevision() = runTest {
        local.write("n.md", "v1")
        syncClean()
        local.write("n.md", "v2")
        remote.calls.clear()

        val result = syncClean()

        assertEquals(1, result.uploaded)
        assertEquals(listOf("uploadRevision:n.md"), remote.calls)
        assertEquals("v2", remote.content("n.md"))
        assertStable()
    }

    @Test
    fun remoteEditDownloads() = runTest {
        local.write("n.md", "v1")
        syncClean()
        remote.putFile("n.md", "v2")

        val result = syncClean()

        assertEquals(1, result.downloaded)
        assertEquals("v2", local.read("n.md"))
        assertStable()
    }

    @Test
    fun editsOnBothSidesProduceConflictAndKeepLocalResolves() = runTest {
        local.write("n.md", "base")
        syncClean()
        local.write("n.md", "local")
        remote.putFile("n.md", "remote")

        val conflict = syncClean().conflicts.single()
        engine.resolve(conflict, ConflictResolution.KEEP_LOCAL)

        assertEquals("local", local.read("n.md"))
        assertEquals("local", remote.content("n.md"))
        assertStable()
    }

    @Test
    fun keepRemoteResolution() = runTest {
        local.write("n.md", "base")
        syncClean()
        local.write("n.md", "local")
        remote.putFile("n.md", "remote")

        val conflict = syncClean().conflicts.single()
        engine.resolve(conflict, ConflictResolution.KEEP_REMOTE)

        assertEquals("remote", local.read("n.md"))
        assertEquals("remote", remote.content("n.md"))
        assertStable()
    }

    @Test
    fun keepBothResolutionDuplicatesRemoteVersion() = runTest {
        local.write("dir/n.md", "base")
        syncClean()
        local.write("dir/n.md", "local")
        remote.putFile("dir/n.md", "remote")

        val conflict = syncClean().conflicts.single()
        engine.resolve(conflict, ConflictResolution.KEEP_BOTH)

        val copy = "dir/n${SyncEngine.CONFLICT_SUFFIX}.md"
        assertEquals("local", local.read("dir/n.md"))
        assertEquals("remote", local.read(copy))
        assertEquals("local", remote.content("dir/n.md"))
        assertEquals("remote", remote.content(copy))
        assertStable()
    }

    @Test
    fun localDeletionTrashesRemote() = runTest {
        local.write("n.md", "x")
        syncClean()
        local.delete("n.md")

        val result = syncClean()

        assertEquals(1, result.deletedRemote)
        assertFalse(remote.exists("n.md"))
        assertStable()
    }

    @Test
    fun remoteDeletionDeletesLocal() = runTest {
        local.write("n.md", "x")
        syncClean()
        remote.remove("n.md")

        val result = syncClean()

        assertEquals(1, result.deletedLocal)
        assertFalse(local.exists("n.md"))
        assertStable()
    }

    @Test
    fun localDeletionLosesAgainstRemoteEdit() = runTest {
        local.write("n.md", "v1")
        syncClean()
        local.delete("n.md")
        remote.putFile("n.md", "v2")

        syncClean()

        assertEquals("v2", local.read("n.md"))
        assertStable()
    }

    @Test
    fun remoteDeletionLosesAgainstLocalEdit() = runTest {
        local.write("n.md", "v1")
        syncClean()
        remote.remove("n.md")
        local.write("n.md", "v2")

        syncClean()

        assertEquals("v2", remote.content("n.md"))
        assertStable()
    }

    @Test
    fun localFolderDeletionTrashesRemoteFolderOnce() = runTest {
        local.write("a/x.md", "x")
        local.write("a/b/y.md", "y")
        syncClean()
        local.delete("a")
        remote.calls.clear()

        syncClean()

        assertEquals(listOf("delete:a"), remote.calls)
        assertFalse(remote.exists("a"))
        assertTrue(metadata.all().isEmpty())
        assertStable()
    }

    @Test
    fun remoteFolderDeletionDeletesLocalFolder() = runTest {
        local.write("a/x.md", "x")
        local.write("a/b/y.md", "y")
        syncClean()
        remote.remove("a")

        syncClean()

        assertFalse(local.exists("a"))
        assertTrue(metadata.all().isEmpty())
        assertStable()
    }

    @Test
    fun remoteFolderDeletionKeepsLocallyEditedNotes() = runTest {
        local.write("a/x.md", "x")
        local.write("a/y.md", "y")
        syncClean()
        remote.remove("a")
        local.write("a/x.md", "edited")

        syncClean()

        assertTrue(remote.isFolder("a"))
        assertEquals("edited", remote.content("a/x.md"))
        assertFalse(local.exists("a/y.md"))
        assertStable()
    }

    @Test
    fun conflictIsReportedAgainUntilResolved() = runTest {
        local.write("n.md", "base")
        syncClean()
        local.write("n.md", "local")
        remote.putFile("n.md", "remote")

        assertEquals(1, syncClean().conflicts.size)
        assertEquals(1, syncClean().conflicts.size)
    }
}
