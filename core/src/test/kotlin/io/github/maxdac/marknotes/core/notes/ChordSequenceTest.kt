package io.github.maxdac.marknotes.core.notes

import io.github.maxdac.marknotes.core.notes.StringSelection.Fretted
import io.github.maxdac.marknotes.core.notes.StringSelection.Muted
import io.github.maxdac.marknotes.core.notes.StringSelection.Open
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grammar, validation and numbering are covered by the spec fixtures (ChordParity); these test the rest. */
class ChordSequenceTest {
    private val cMajor = Chord(
        "C",
        Instrument.GUITAR,
        listOf(Muted, Fretted(3, 3), Fretted(2, 2), Open, Fretted(1, 1), Open),
        listOf("open", "beginner"),
        "First position.\nLet the high E ring."
    )
    private val eBass = Chord("E5", Instrument.BASS, listOf(Open, Fretted(2, 3), Fretted(2, 4), Muted))

    private fun parsed(body: String): ChordSequence = (ChordSequences.parse(body) as ChordParseResult.Ok).sequence

    @Test
    fun roundTripsWithBothLineEndings() {
        val sequence = ChordSequence(listOf(cMajor, eBass, cMajor.copy(name = "C (add9)", note = "")), "Song")
        listOf("\n", "\r\n").forEach { lineEnding ->
            val body = ChordSequences.serialize(sequence, lineEnding)
            assertEquals(sequence, parsed(body))
            assertEquals(body, ChordSequences.serialize(parsed(body), lineEnding))
        }
    }

    @Test
    fun lineEndingFollowsTheOpeningFence() {
        assertEquals("\r\n", ChordSequences.lineEnding("---\r\nid: abcdefgh\r\n---\r\n"))
        assertEquals("\n", ChordSequences.lineEnding("---\nid: abcdefgh\n---\n"))
        assertEquals("\n", ChordSequences.lineEnding(""))
    }

    @Test
    fun conflictMarkersBeforeTheFirstChordFailToParse() {
        val body = "<<<<<<< local\n## C (guitar)\nfrets: x 3/3 2/2 0 1/1 0\n=======\n>>>>>>> remote\n"
        assertEquals(ChordParseResult.Error(1, "Expected a chord heading."), ChordSequences.parse(body))
    }

    @Test
    fun validatesSingleChords() {
        assertEquals(emptyList<String>(), ChordSequences.validate(cMajor))
        assertEquals(
            listOf("bass requires 4 strings."),
            ChordSequences.validate(cMajor.copy(instrument = Instrument.BASS))
        )
    }

    @Test
    fun normalizesTagsAndNames() {
        assertEquals(listOf("Jazz", "open"), ChordSequences.normalizeTags(listOf(" Jazz", "", "jazz", "open ")))
        assertEquals("C maj 7", ChordSequences.normalizeName("  C   maj\t7 "))
    }

    @Test
    fun previewWindowFollowsChordBuilder() {
        assertEquals(1, ChordSequences.previewStart(listOf(Open, Open, Open)))
        assertEquals(3, ChordSequences.previewStart(listOf(Fretted(3, 1), Fretted(5, 3))))
        assertEquals(7, ChordSequences.previewStart(listOf(Fretted(3, 1), Fretted(10, 4))))
    }

    @Test
    fun fretRanges() {
        assertEquals(1..24, ChordSequences.fretRange(Instrument.GUITAR))
        assertEquals(9..24, ChordSequences.fretRange(Instrument.BASS))
    }

    @Test
    fun detectsChordSequencesByFrontMatterType() {
        assertTrue(ChordSequences.isChordSequence("---\nid: abcdefgh\ntype: chords\n---\n"))
        assertTrue(ChordSequences.isChordSequence("---  \r\ntype: \"chords\"\r\n---\r\n## C (guitar)"))
        assertFalse(ChordSequences.isChordSequence("---\nid: abcdefgh\n---\ntype: chords\n"))
        assertFalse(ChordSequences.isChordSequence("type: chords\n"))
    }

    @Test
    fun createsChordSequenceNotes() {
        val root = Files.createTempDirectory("chords").toFile()
        try {
            val store = LocalNoteStore(root)
            val path = store.createNote("", "Song", ChordSequences.TYPE)
            val doc = FrontMatter.parse(store.read(path))
            assertTrue(NoteIds.isValid(doc.id!!))
            assertEquals(ChordSequences.TYPE, doc.type)
            assertTrue(ChordSequences.isChordFile(store, path))
            assertFalse(ChordSequences.isChordFile(store, store.createNote("", "Plain")))
        } finally {
            root.deleteRecursively()
        }
    }
}
