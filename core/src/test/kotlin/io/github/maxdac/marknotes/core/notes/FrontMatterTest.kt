package io.github.maxdac.marknotes.core.notes

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontMatterTest {
    @Test
    fun generatedIdsUseTheCrockfordAlphabet() {
        val random = Random(42)
        repeat(200) {
            val id = NoteIds.generate(random)
            assertTrue(id, NoteIds.isValid(id))
            assertTrue(id.none { it in "ilou" })
        }
    }

    @Test
    fun parsesHeaderAndBody() {
        val doc = FrontMatter.parse("---\nid: k7f3q2xa\ntags: [a, b]\n---\n# Title\nbody")
        assertEquals("k7f3q2xa", doc.id)
        assertEquals("---\nid: k7f3q2xa\ntags: [a, b]\n---\n", doc.header)
        assertEquals("# Title\nbody", doc.body)
    }

    @Test
    fun noteWithoutFrontMatterIsAllBody() {
        val doc = FrontMatter.parse("# Title\n---\nmore")
        assertEquals("", doc.header)
        assertNull(doc.id)
        assertEquals("# Title\n---\nmore", doc.body)
    }

    @Test
    fun unterminatedFrontMatterIsBody() {
        val text = "---\nid: abc\nno end"
        assertEquals(FrontMatter.parse(text), NoteDocument("", text))
    }

    @Test
    fun headerAtEndOfFileWithoutTrailingNewline() {
        val doc = FrontMatter.parse("---\nid: k7f3q2xa\n---")
        assertEquals("k7f3q2xa", doc.id)
        assertEquals("", doc.body)
    }

    @Test
    fun handlesCrLfAndQuotedIds() {
        val doc = FrontMatter.parse("---\r\nid: \"k7f3q2xa\"\r\n---\r\nbody")
        assertEquals("k7f3q2xa", doc.id)
        assertEquals("body", doc.body)
    }

    @Test
    fun withIdAddsAHeaderToPlainNotes() {
        assertEquals("---\nid: k7f3q2xa\n---\n# Hi", FrontMatter.withId("# Hi", "k7f3q2xa"))
        assertEquals("---\nid: k7f3q2xa\n---\n", FrontMatter.withId("", "k7f3q2xa"))
    }

    @Test
    fun withIdPreservesOtherKeys() {
        val text = "---\ntitle: Hello\naliases:\n  - x\n---\nbody"
        val expected = "---\nid: k7f3q2xa\ntitle: Hello\naliases:\n  - x\n---\nbody"
        assertEquals(expected, FrontMatter.withId(text, "k7f3q2xa"))
    }

    @Test
    fun withIdReplacesExistingId() {
        val text = "---\ntitle: Hello\nid: old\n---\nbody"
        assertEquals("---\ntitle: Hello\nid: k7f3q2xa\n---\nbody", FrontMatter.withId(text, "k7f3q2xa"))
    }

    @Test
    fun bodyAndHeaderRoundTrip() {
        val text = "---\nid: k7f3q2xa\n---\nline 1\n\nline 2\n"
        val doc = FrontMatter.parse(text)
        assertEquals(text, doc.text)
        assertEquals(text, doc.header + doc.body)
    }
}
