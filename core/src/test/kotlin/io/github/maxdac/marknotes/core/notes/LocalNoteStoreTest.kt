package io.github.maxdac.marknotes.core.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class LocalNoteStoreTest {
    private lateinit var dir: File
    private lateinit var store: LocalNoteStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("notes").toFile()
        store = LocalNoteStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun createNoteAddsExtensionAndIsEmpty() {
        val path = store.createNote("", "Shopping")
        assertEquals("Shopping.md", path)
        assertEquals("", store.read(path))
    }

    @Test
    fun writeAndReadInNestedFolders() {
        store.write("a/b/note.md", "# Hello")
        assertEquals("# Hello", store.read("a/b/note.md"))
        assertTrue(store.isFolder("a/b"))
    }

    @Test
    fun listSortsFoldersFirstAndHidesTempFiles() {
        store.write("zeta.md", "z")
        store.write("Alpha.md", "a")
        store.createFolder("", "folder")
        File(dir, ".hidden.tmp").writeText("x")
        val names = store.list("").map { it.name }
        assertEquals(listOf("folder", "Alpha.md", "zeta.md"), names)
    }

    @Test
    fun listAllIsRecursive() {
        store.write("a/one.md", "1")
        store.write("a/b/two.md", "2")
        val paths = store.listAll().map { it.path }.toSet()
        assertEquals(setOf("a", "a/one.md", "a/b", "a/b/two.md"), paths)
    }

    @Test
    fun renameKeepsExtensionAndFolder() {
        store.write("a/old.md", "x")
        val newPath = store.rename("a/old.md", "new")
        assertEquals("a/new.md", newPath)
        assertFalse(store.exists("a/old.md"))
        assertEquals("x", store.read(newPath))
    }

    @Test
    fun renameFolder() {
        store.write("a/note.md", "x")
        assertEquals("b", store.rename("a", "b"))
        assertEquals("x", store.read("b/note.md"))
    }

    @Test(expected = IOException::class)
    fun createNoteFailsWhenExisting() {
        store.createNote("", "dup")
        store.createNote("", "dup.md")
    }

    @Test
    fun deleteFolderRecursively() {
        store.write("a/b/c.md", "x")
        store.delete("a")
        assertFalse(store.exists("a"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversal() {
        store.read("../secret.md")
    }

    @Test
    fun overwriteReplacesContent() {
        store.write("n.md", "one")
        store.write("n.md", "two")
        assertEquals("two", store.read("n.md"))
    }

    @Test
    fun uniqueSiblingAddsCounter() {
        store.write("a/n.md", "x")
        assertEquals("a/n (copy).md", store.uniqueSibling("a/n.md", " (copy)"))
        store.write("a/n (copy).md", "y")
        assertEquals("a/n (copy) 2.md", store.uniqueSibling("a/n.md", " (copy)"))
    }
}
