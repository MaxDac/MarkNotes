package io.github.maxdac.marknotes.core.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainTextToMarkdownTest {
    private fun md(text: String, reflow: Boolean = false) = PlainTextToMarkdown.convert(text, reflow)

    @Test
    fun linesBecomeParagraphsAndBulletsBecomeLists() {
        val text = "Shopping for the weekend trip\r\n• Milk\r\n• Bread\r\n   ◦ Rye\r\nThat's all."
        assertEquals(
            "Shopping for the weekend trip\n\n- Milk\n- Bread\n    - Rye\n\nThat's all.\n",
            md(text)
        )
    }

    @Test
    fun numberedAndTaskLines() {
        assertEquals("1. First\n2. Second\n- [ ] Todo\n- [x] Done\n", md("1) First\n2) Second\n☐ Todo\n☑ Done"))
    }

    @Test
    fun standaloneTitleLinesBecomeHeadings() {
        val text = "Meeting Notes\n\nWe discussed the roadmap for the next quarter.\n\n" +
            "ACTION ITEMS\n\n- Send the summary\n\n2.1 Budget Review\n\nNumbers were fine overall."
        assertEquals(
            "# Meeting Notes\n\nWe discussed the roadmap for the next quarter.\n\n## ACTION ITEMS\n\n" +
                "- Send the summary\n\n### 2.1 Budget Review\n\nNumbers were fine overall.\n",
            md(text)
        )
    }

    @Test
    fun sentencesAndGreetingsAreNotHeadings() {
        assertEquals("Hi there\n\nThanks\n\nJohn\n", md("Hi there\n\nThanks\n\nJohn"))
        assertEquals(
            "This is a sentence.\n\nAnother one follows here.\n",
            md("This is a sentence.\n\nAnother one follows here.")
        )
    }

    @Test
    fun setextHeadings() {
        assertEquals("# Title\n\n## Part\n\nBody\n", md("Title\n=====\n\nPart\n----\n\nBody"))
    }

    @Test
    fun hardWrappedParagraphsAreJoined() {
        val text = "This paragraph was wrapped at a fixed width by the\n" +
            "program that produced it, so every line is about the\n" +
            "same length and the sentences continue on the next\n" +
            "line until the end.\n\n" +
            "A second paragraph that is also wrapped by the same\n" +
            "tool and hyphen-\n" +
            "ates a word at the end of a line."
        assertEquals(
            "This paragraph was wrapped at a fixed width by the program that produced it, so every line is " +
                "about the same length and the sentences continue on the next line until the end.\n\n" +
                "A second paragraph that is also wrapped by the same tool and hyphenates a word at the end " +
                "of a line.\n",
            md(text)
        )
    }

    @Test
    fun reflowJoinsPdfLines() {
        assertEquals("Short line joined with the next.\n", md("Short line\njoined with the next.", reflow = true))
    }

    @Test
    fun indentedBlocksBecomeCode() {
        assertEquals(
            "Run this:\n\n```\nnpm install\n  npm test\n```\n",
            md("Run this:\n\n    npm install\n      npm test")
        )
    }

    @Test
    fun urlsBecomeLinksAndMarkdownCharactersAreEscaped() {
        assertEquals(
            "See <https://example.com/a_(b)>, [www.x.io](https://www.x.io) and \\*stars\\* or file_name.\n",
            md("See https://example.com/a_(b), www.x.io and *stars* or file_name.")
        )
    }

    @Test
    fun markdownIsKeptAsIs() {
        val text = "# Title\n\nSome **bold** and a [link](https://x.io).\n"
        assertTrue(PlainTextToMarkdown.looksLikeMarkdown(text))
        assertEquals(text, md(text))
        assertFalse(PlainTextToMarkdown.looksLikeMarkdown("Just text with #hashtag and 3 * 4"))
    }
}
