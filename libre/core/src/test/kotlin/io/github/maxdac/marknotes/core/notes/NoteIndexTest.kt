package io.github.maxdac.marknotes.core.notes

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NoteIndexTest {
    private lateinit var dir: File
    private lateinit var store: LocalNoteStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("index").toFile()
        store = LocalNoteStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun assignsMissingIdsAndKeepsExistingOnes() {
        store.write("a.md", "---\nid: aaaaaaaa\n---\nA")
        store.write("work/b.md", "plain B")

        val build = NoteIndex.build(store, Random(1))

        assertEquals(setOf("work/b.md"), build.rewritten)
        val b = build.index.notes.single { it.path == "work/b.md" }
        assertTrue(NoteIds.isValid(b.id))
        assertEquals("---\nid: ${b.id}\n---\nplain B", store.read("work/b.md"))
        assertEquals("a.md", build.index.resolve("aaaaaaaa")?.path)
        assertEquals("work/b.md", build.index.resolve(b.id.uppercase())?.path)
        assertNull(build.index.resolve("zzzzzzzz"))
    }

    @Test
    fun duplicateIdIsReassignedToTheLaterPath() {
        store.write("b.md", "---\nid: dupdupdu\n---\nB")
        store.write("a.md", "---\nid: dupdupdu\n---\nA")

        val build = NoteIndex.build(store, Random(2))

        assertEquals(setOf("b.md"), build.rewritten)
        assertEquals("a.md", build.index.resolve("dupdupdu")?.path)
        val newId = FrontMatter.parse(store.read("b.md")).id
        assertNotEquals("dupdupdu", newId)
        assertEquals("b.md", build.index.resolve(newId!!)?.path)
        assertEquals("B", FrontMatter.parse(store.read("b.md")).body)
    }

    @Test
    fun ignoresAttachmentsAndNonNotes() {
        store.write("attachments/x.md", "not a note")
        store.writeBytes("attachments/x.png", byteArrayOf(1))
        store.write("n.md", "---\nid: nnnnnnnn\n---\n")

        val index = NoteIndex.build(store).index

        assertEquals(listOf("n.md"), index.notes.map { it.path })
    }

    @Test
    fun searchMatchesIdPrefixTitleAndPath() {
        val index = NoteIndex(
            listOf(
                IndexedNote("k7f3q2xa", "Work/Roadmap.md"),
                IndexedNote("abc12345", "Personal/Groceries.md"),
                IndexedNote("k7zzzzzz", "Ideas.md")
            )
        )
        assertEquals(listOf("Ideas", "Personal/Groceries", "Work/Roadmap"), index.search("").map { it.displayPath })
        assertEquals(listOf("Ideas.md", "Work/Roadmap.md"), index.search("k7").map { it.path })
        assertEquals(listOf("Work/Roadmap.md"), index.search("ROAD").map { it.path })
        assertEquals(listOf("Work/Roadmap.md"), index.search("work/").map { it.path })
        assertEquals(listOf("Personal/Groceries.md"), index.search("abc").map { it.path })
        assertTrue(index.search("nothing").isEmpty())
        assertEquals("Roadmap", index.notes.last().title)
    }

    @Test
    fun noteLinks() {
        assertEquals("[Road \\[map\\]](marknotes:k7f3q2xa)", NoteLinks.markdown("Road [map]", "k7f3q2xa"))
        assertEquals("k7f3q2xa", NoteLinks.idOf("marknotes:K7F3Q2XA"))
        assertNull(NoteLinks.idOf("https://example.com"))
        assertNull(NoteLinks.idOf("marknotes:"))
    }
}
