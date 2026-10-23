package io.github.maxdac.marknotes

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.sync.ConflictMergeBack
import io.github.maxdac.marknotes.core.sync.SyncConflict
import io.github.maxdac.marknotes.sync.Action
import io.github.maxdac.marknotes.sync.LocalEntry
import io.github.maxdac.marknotes.sync.Side
import io.github.maxdac.marknotes.sync.SyncInput
import io.github.maxdac.marknotes.sync.merge3
import io.github.maxdac.marknotes.sync.plan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The shared Rust sync core loads from the app's native libraries and runs on the device. */
@RunWith(AndroidJUnit4::class)
class SyncCoreOnDeviceTest {
    @Test
    fun planAndMergeRunOnTheDevice() {
        val actions = plan(SyncInput(listOf(LocalEntry("n.md", false, 1L, "hash")), emptyList(), emptyList(), null))
        assertEquals(listOf("n.md"), actions.actions.filterIsInstance<Action.UploadNew>().map { it.path })

        val merged = merge3("a\nb\nc\n", "A\nb\nc\n", "a\nb\nC\n")
        assertFalse(merged.hasConflicts)
        assertEquals("A\nb\nC\n", merged.text)
    }

    @Test
    fun conflictedCopyIsMergedBack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "merge-back").apply { deleteRecursively() }
        try {
            val notes = LocalNoteStore(dir)
            notes.write("n.md", "A\nb\nc\n")
            notes.write("n (conflicted copy).md", "a\nb\nC\n")
            val conflict = SyncConflict("n.md", "n (conflicted copy).md", Side.LOCAL, "a\nb\nc\n", "drive")
            val mergeBack = ConflictMergeBack(notes)

            mergeBack.apply(conflict, mergeBack.merge(conflict).text)

            assertEquals("A\nb\nC\n", notes.read("n.md"))
            assertFalse(notes.exists(conflict.copyPath))
            assertTrue(notes.exists("n.md"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
