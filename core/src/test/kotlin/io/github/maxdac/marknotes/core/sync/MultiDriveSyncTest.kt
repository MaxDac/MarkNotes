package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MultiDriveSyncTest {
    private lateinit var dir: File
    private lateinit var local: LocalNoteStore
    private lateinit var driveA: FakeRemoteDriveStore
    private lateinit var driveB: FakeRemoteDriveStore
    private lateinit var targets: List<DriveSyncTarget>
    private lateinit var sync: MultiDriveSync

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("multi").toFile()
        local = LocalNoteStore(dir)
        driveA = FakeRemoteDriveStore()
        driveB = FakeRemoteDriveStore()
        targets = listOf(
            DriveSyncTarget("a", driveA, InMemorySyncMetadataStore(), InMemoryBaseContentStore()),
            DriveSyncTarget("b", driveB, InMemorySyncMetadataStore(), InMemoryBaseContentStore())
        )
        sync = MultiDriveSync(local, deviceId = "device") { 1000L }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private suspend fun syncAll(): MultiDriveSyncResult {
        val result = sync.sync(targets)
        result.outcomes.forEach {
            assertNull(it.error)
            assertEquals(emptyList<String>(), it.result!!.errors)
        }
        return result
    }

    @Test
    fun localNotesArePushedToEveryDrive() = runTest {
        local.write("f/n.md", "hello")

        syncAll()

        assertEquals("hello", driveA.content("f/n.md"))
        assertEquals("hello", driveB.content("f/n.md"))
    }

    @Test
    fun noteAddedOnOneDriveReachesTheOthers() = runTest {
        syncAll()
        driveB.putFile("from-b.md", "b")

        syncAll()

        assertEquals("b", local.read("from-b.md"))
        assertEquals("b", driveA.content("from-b.md"))
    }

    @Test
    fun latestEditOnAnyDriveIsPropagated() = runTest {
        local.write("n.md", "v1")
        syncAll()
        driveA.putFile("n.md", "v2")

        syncAll()

        assertEquals("v2", local.read("n.md"))
        assertEquals("v2", driveB.content("n.md"))
    }

    @Test
    fun deletionOnOneDriveIsPropagated() = runTest {
        local.write("n.md", "x")
        syncAll()
        driveB.remove("n.md")

        syncAll()

        assertFalse(local.exists("n.md"))
        assertFalse(driveA.exists("n.md"))
    }

    @Test
    fun nonOverlappingEditsOnTwoDrivesAreMergedEverywhere() = runTest {
        local.write("n.md", "a\nb\nc\n")
        syncAll()
        driveA.putFile("n.md", "A\nb\nc\n")
        driveB.putFile("n.md", "a\nb\nC\n")

        val result = syncAll()

        assertTrue(result.conflicts.isEmpty())
        assertEquals("A\nb\nC\n", local.read("n.md"))
        assertEquals("A\nb\nC\n", driveA.content("n.md"))
        assertEquals("A\nb\nC\n", driveB.content("n.md"))
    }

    @Test
    fun conflictingEditsAreReportedWithTheirDrive() = runTest {
        local.write("n.md", "x\n")
        syncAll()
        driveA.putFile("n.md", "from a\n")
        driveB.putFile("n.md", "from b\n")

        val conflicts = syncAll().conflicts

        assertEquals(listOf("b"), conflicts.map { it.driveId })
        assertEquals("from a\n", local.read("n.md"))
        assertEquals("from b\n", conflicts.single().remoteText)
    }

    @Test
    fun failingDriveDoesNotBlockTheOthers() = runTest {
        local.write("n.md", "x")
        val broken = object : RemoteDriveStore by driveA {
            override suspend fun snapshot(): List<RemoteEntry> = error("offline")
        }
        val result = sync.sync(
            listOf(
                DriveSyncTarget("broken", broken, InMemorySyncMetadataStore(), InMemoryBaseContentStore()),
                targets[1]
            )
        )

        assertEquals("offline", result.outcomes.first().error)
        assertEquals("x", driveB.content("n.md"))
    }
}
