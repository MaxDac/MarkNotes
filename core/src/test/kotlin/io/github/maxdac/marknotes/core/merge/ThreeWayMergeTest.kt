package io.github.maxdac.marknotes.core.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDiffTest {

    @Test
    fun linesKeepTerminators() {
        assertEquals(listOf("a\n", "b\n", "c"), LineDiff.lines("a\nb\nc"))
        assertEquals(listOf("a\n", "\n"), LineDiff.lines("a\n\n"))
        assertEquals(emptyList<String>(), LineDiff.lines(""))
    }

    @Test
    fun diffMarksAddedAndRemovedLines() {
        val diff = LineDiff.diff("a\nb\nc\n", "a\nx\nc\nd\n")
        assertEquals(
            listOf(
                DiffLine(DiffLine.Type.SAME, "a\n"),
                DiffLine(DiffLine.Type.REMOVED, "b\n"),
                DiffLine(DiffLine.Type.ADDED, "x\n"),
                DiffLine(DiffLine.Type.SAME, "c\n"),
                DiffLine(DiffLine.Type.ADDED, "d\n")
            ),
            diff
        )
    }

    @Test
    fun matchesAreALongestCommonSubsequence() {
        val a = "abcabba".map { "$it\n" }
        val b = "cbabac".map { "$it\n" }
        val matches = LineDiff.matches(a, b)
        assertEquals(4, matches.size)
        matches.forEach { (i, j) -> assertEquals(a[i], b[j]) }
        assertEquals(matches.sortedBy { it.first }, matches)
        assertEquals(matches.sortedBy { it.second }, matches)
    }

    @Test
    fun diffRoundTripsBothSides() {
        val old = (1..200).joinToString("\n") { "line $it" }
        val new = (1..200).filter { it % 7 != 0 }.joinToString("\n") { if (it % 5 == 0) "changed $it" else "line $it" }
        val diff = LineDiff.diff(old, new)
        assertEquals(old, diff.filter { it.type != DiffLine.Type.ADDED }.joinToString("") { it.text })
        assertEquals(new, diff.filter { it.type != DiffLine.Type.REMOVED }.joinToString("") { it.text })
    }
}

class ThreeWayMergeTest {

    @Test
    fun nonOverlappingEditsMergeAutomatically() {
        val base = "title\n\none\ntwo\nthree\n"
        val local = "title\n\none (local)\ntwo\nthree\n"
        val remote = "title\n\none\ntwo\nthree\nfour (remote)\n"

        val result = ThreeWayMerge.merge(base, local, remote)

        assertFalse(result.hasConflicts)
        assertEquals("title\n\none (local)\ntwo\nthree\nfour (remote)\n", result.text())
    }

    @Test
    fun overlappingEditsConflict() {
        val result = ThreeWayMerge.merge("a\nb\nc\n", "a\nlocal\nc\n", "a\nremote\nc\n")

        assertTrue(result.hasConflicts)
        val conflict = result.conflicts.single()
        assertEquals("b\n", conflict.baseText)
        assertEquals("local\n", conflict.localText)
        assertEquals("remote\n", conflict.remoteText)
        assertEquals("a\nlocal\nc\n", result.text(mapOf(0 to ConflictChoice.LOCAL)))
        assertEquals("a\nremote\nc\n", result.text(mapOf(0 to ConflictChoice.REMOTE)))
        assertEquals("a\nlocal\nremote\nc\n", result.text(mapOf(0 to ConflictChoice.BOTH)))
        assertEquals("a\nc\n", result.text(mapOf(0 to ConflictChoice.NEITHER)))
    }

    @Test
    fun unresolvedConflictsUseGitMarkers() {
        val result = ThreeWayMerge.merge("x", "local", "remote")
        assertEquals(
            "${MergeResult.MARKER_LOCAL}\nlocal\n${MergeResult.MARKER_SEPARATOR}\n" +
                "remote\n${MergeResult.MARKER_REMOTE}\n",
            result.text()
        )
    }

    @Test
    fun identicalChangesOnBothSidesAreNotAConflict() {
        val result = ThreeWayMerge.merge("a\nb\n", "a\nB\n", "a\nB\n")
        assertFalse(result.hasConflicts)
        assertEquals("a\nB\n", result.text())
    }

    @Test
    fun deletionOnOneSideAndEditElsewhereMerge() {
        val result = ThreeWayMerge.merge("1\n2\n3\n4\n", "1\n3\n4\n", "1\n2\n3\n4!\n")
        assertFalse(result.hasConflicts)
        assertEquals("1\n3\n4!\n", result.text())
    }

    @Test
    fun withoutBaseEveryDifferenceIsAConflict() {
        val result = ThreeWayMerge.merge(null, "same\nlocal only\n", "same\n")
        assertTrue(result.hasConflicts)
        assertEquals("same\n", (result.chunks.first() as MergeChunk.Resolved).text)
        assertEquals("same\nlocal only\n", result.text(mapOf(0 to ConflictChoice.LOCAL)))
        assertEquals("same\n", result.text(mapOf(0 to ConflictChoice.REMOTE)))
    }

    @Test
    fun conflictsAreIndexedInOrder() {
        val base = "a\nb\nc\nd\ne\n"
        val local = "A1\nb\nc\nd\nE1\n"
        val remote = "A2\nb\nc\nd\nE2\n"
        val result = ThreeWayMerge.merge(base, local, remote)
        assertEquals(2, result.conflicts.size)
        assertEquals(
            "A1\nb\nc\nd\nE2\n",
            result.text(mapOf(0 to ConflictChoice.LOCAL, 1 to ConflictChoice.REMOTE))
        )
    }
}
