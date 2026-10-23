package io.github.maxdac.marknotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownEditingTest {
    private fun at(text: String, start: Int, end: Int = start) = EditorText(text, start, end)

    /** Simulates typing a newline at the (collapsed) cursor of [old]. */
    private fun enter(old: EditorText): EditorText? {
        val p = old.selectionStart
        return MarkdownEditing.continueList(old, at(old.text.substring(0, p) + "\n" + old.text.substring(p), p + 1))
    }

    @Test
    fun boldWrapsSelection() {
        assertEquals(at("a **bc** d", 4, 6), MarkdownEditing.toggleInline(at("a bc d", 2, 4), "**"))
    }

    @Test
    fun boldWithEmptySelectionInsertsPairAndTogglesBack() {
        val inserted = MarkdownEditing.toggleInline(at("ab", 1), "**")
        assertEquals(at("a****b", 3), inserted)
        assertEquals(at("ab", 1), MarkdownEditing.toggleInline(inserted, "**"))
    }

    @Test
    fun inlineUnwrapsWhenMarkersAreOutsideOrInsideSelection() {
        assertEquals(at("a bc d", 2, 4), MarkdownEditing.toggleInline(at("a _bc_ d", 3, 5), "_"))
        assertEquals(at("a bc d", 2, 4), MarkdownEditing.toggleInline(at("a ~~bc~~ d", 2, 8), "~~"))
    }

    @Test
    fun inlineCodeWrapsSelection() {
        assertEquals(at("`val x`", 1, 6), MarkdownEditing.toggleInline(at("val x", 0, 5), "`"))
    }

    @Test
    fun bulletAddsAndRemovesPrefix() {
        val added = MarkdownEditing.toggleLinePrefix(at("milk", 4), LineKind.BULLET)
        assertEquals(at("- milk", 6), added)
        assertEquals(at("milk", 4), MarkdownEditing.toggleLinePrefix(added, LineKind.BULLET))
    }

    @Test
    fun checkboxConvertsBulletAndKeepsIndent() {
        assertEquals(
            at("  - [ ] milk", 12),
            MarkdownEditing.toggleLinePrefix(at("  - milk", 8), LineKind.CHECKBOX)
        )
    }

    @Test
    fun numberedListNumbersEverySelectedLine() {
        val result = MarkdownEditing.toggleLinePrefix(at("a\nb\n\nc", 0, 6), LineKind.NUMBERED)
        assertEquals(at("1. a\n2. b\n\n3. c", 0, 15), result)
    }

    @Test
    fun quoteOnEmptyLine() {
        assertEquals(at("x\n> ", 4), MarkdownEditing.toggleLinePrefix(at("x\n", 2), LineKind.QUOTE))
    }

    @Test
    fun headingCycles() {
        var value = at("Title", 2)
        value = MarkdownEditing.cycleHeading(value)
        assertEquals(at("# Title", 4), value)
        value = MarkdownEditing.cycleHeading(value)
        assertEquals("## Title", value.text)
        value = MarkdownEditing.cycleHeading(value)
        assertEquals("### Title", value.text)
        value = MarkdownEditing.cycleHeading(value)
        assertEquals(at("Title", 2), value)
    }

    @Test
    fun linkWrapsSelectionAndSelectsUrl() {
        assertEquals(at("see [docs](url)", 11, 14), MarkdownEditing.insertLink(at("see docs", 4, 8)))
        assertEquals(at("[]()", 1), MarkdownEditing.insertLink(at("", 0)))
    }

    @Test
    fun codeBlockInsertedOnOwnLines() {
        assertEquals(at("ab\n```\n\n```\ncd", 6), MarkdownEditing.toggleCodeBlock(at("abcd", 2)))
        assertEquals(at("```\n\n```", 3), MarkdownEditing.toggleCodeBlock(at("", 0)))
    }

    @Test
    fun codeBlockWrapsAndUnwrapsSelectedLines() {
        val wrapped = MarkdownEditing.toggleCodeBlock(at("x\nval a = 1\nval b = 2\ny", 4, 14))
        assertEquals(at("x\n```\nval a = 1\nval b = 2\n```\ny", 5), wrapped)
        val unwrapped = MarkdownEditing.toggleCodeBlock(at(wrapped.text, 2, 28))
        assertEquals(at("x\nval a = 1\nval b = 2\ny", 2, 21), unwrapped)
    }

    @Test
    fun enterContinuesBullet() {
        assertEquals(at("- milk\n- ", 9), enter(at("- milk", 6)))
        assertEquals(at("* milk\n* ", 9), enter(at("* milk", 6)))
    }

    @Test
    fun enterContinuesCheckboxUnchecked() {
        assertEquals(at("  - [x] milk\n  - [ ] ", 21), enter(at("  - [x] milk", 12)))
    }

    @Test
    fun enterIncrementsNumber() {
        assertEquals(at("9) a\n10) ", 9), enter(at("9) a", 4)))
    }

    @Test
    fun enterContinuesQuote() {
        assertEquals(at("> hi\n> ", 7), enter(at("> hi", 4)))
    }

    @Test
    fun enterSplitsItemInTheMiddle() {
        assertEquals(at("- ab\n- cd", 7), enter(at("- abcd", 4)))
    }

    @Test
    fun enterOnEmptyItemEndsList() {
        assertEquals(at("- milk\n", 7), enter(at("- milk\n- [ ] ", 13)))
        assertEquals(at("- milk\n", 7), enter(at("- milk\n- ", 9)))
        assertEquals(at("1. a\n", 5), enter(at("1. a\n2. ", 8)))
    }

    @Test
    fun enterDoesNothingSpecialOutsideLists() {
        assertNull(enter(at("hello", 5)))
        assertNull(enter(at("---", 3)))
        assertNull(enter(at("- milk", 1)))
    }

    @Test
    fun enterDoesNotContinueInsideCodeFence() {
        assertNull(enter(at("```\n- item", 10)))
        assertEquals(at("```\n```\n- a\n- ", 14), enter(at("```\n```\n- a", 11)))
    }

    @Test
    fun nonNewlineEditsAreIgnored() {
        assertNull(MarkdownEditing.continueList(at("- a", 3), at("- ab", 4)))
        assertNull(MarkdownEditing.continueList(at("- a", 3), at("- a\n\n", 5)))
    }

    /** Simulates typing [c] at the (collapsed) cursor of [old]. */
    private fun type(old: EditorText, c: Char): EditorText {
        val p = old.selectionStart
        return at(old.text.substring(0, p) + c + old.text.substring(p), p + 1)
    }

    @Test
    fun mentionTriggersAtLineStartOrAfterWhitespace() {
        assertEquals(0, MarkdownEditing.typedMentionTrigger(at("", 0), type(at("", 0), '@')))
        assertEquals(3, MarkdownEditing.typedMentionTrigger(at("hi x", 3), type(at("hi x", 3), '@')))
        assertEquals(3, MarkdownEditing.typedMentionTrigger(at("ab\n", 3), type(at("ab\n", 3), '@')))
    }

    @Test
    fun mentionDoesNotTriggerInsideWordsOrOnPaste() {
        assertNull(MarkdownEditing.typedMentionTrigger(at("mail", 4), type(at("mail", 4), '@')))
        assertNull(MarkdownEditing.typedMentionTrigger(at("", 0), at("a @", 3)))
        assertNull(MarkdownEditing.typedMentionTrigger(at("x", 1), type(at("x", 1), 'y')))
    }

    @Test
    fun mentionQueryFollowsTheCursor() {
        assertEquals("", MarkdownEditing.mentionQuery(at("a @", 3), 2))
        assertEquals("sho", MarkdownEditing.mentionQuery(at("a @sho", 6), 2))
        assertEquals("sh", MarkdownEditing.mentionQuery(at("a @sho", 5), 2))
        assertNull(MarkdownEditing.mentionQuery(at("a @sho", 2), 2))
        assertNull(MarkdownEditing.mentionQuery(at("a sho", 5), 2))
        assertNull(MarkdownEditing.mentionQuery(at("a @s\nx", 6), 2))
    }

    @Test
    fun replaceMentionInsertsLinkAndMovesCursor() {
        val link = "[Shopping](marknotes:k7f3q2xa)"
        assertEquals(
            at("see $link end", 4 + link.length),
            MarkdownEditing.replaceMention(at("see @sho end", 8), 4, link)
        )
    }

    @Test
    fun insertBlockPutsImageOnItsOwnLine() {
        val image = "![cat](attachments/ab.png)"
        assertEquals(at("one\n$image\ntwo", 4 + image.length), MarkdownEditing.insertBlock(at("onetwo", 3), image))
        assertEquals(at(image, image.length), MarkdownEditing.insertBlock(at("", 0), image))
        assertEquals(at("a\n$image\n", 2 + image.length), MarkdownEditing.insertBlock(at("a\n\n", 2), image))
    }
}
