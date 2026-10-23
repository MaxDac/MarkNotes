package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.sync.Side
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FileConflictStoreTest {
    private val dir = Files.createTempDirectory("conflicts").toFile()
    private val file = dir.resolve("sync-conflicts.json")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun conflictsSurviveARestart() {
        val conflicts = listOf(
            SyncConflict("a/n.md", "a/n (conflicted copy).md", Side.LOCAL, "base\ntext\n", "drive-1"),
            SyncConflict("m.md", "m (conflicted copy).md", Side.REMOTE, null, "drive-2")
        )
        FileConflictStore(file).save(conflicts)

        assertEquals(conflicts, FileConflictStore(file).load())
    }

    @Test
    fun aMissingOrUnreadableFileHasNoConflicts() {
        assertEquals(emptyList<SyncConflict>(), FileConflictStore(file).load())
        file.writeText("not json")
        assertEquals(emptyList<SyncConflict>(), FileConflictStore(file).load())
    }

    @Test
    fun savingNoConflictsRemovesTheFile() {
        val store = FileConflictStore(file)
        store.save(listOf(SyncConflict("n.md", "n (conflicted copy).md", Side.LOCAL)))
        store.save(emptyList())

        assertFalse(file.exists())
    }
}
