package io.github.maxdac.marknotes.core.importing

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportConverterTest {
    @Test
    fun keepAsIsReturnsTheTextUnchanged() {
        val text = "• Milk\r\nURGENT\n\n*not emphasis*"
        assertEquals("• Milk\nURGENT\n\n*not emphasis*\n", ImportConverter.convert(ImportSource.Text(text), false))
    }

    @Test
    fun htmlKeptAsIsPrefersTheSharedPlainText() {
        val source = ImportSource.Html("<p><b>Hi</b></p>", text = "Hi there")
        assertEquals("Hi there\n", ImportConverter.convert(source, autoFormat = false))
        assertEquals("**Hi**\n", ImportConverter.convert(source, autoFormat = true))
        assertEquals("Hi\n", ImportConverter.convert(ImportSource.Html("<p><b>Hi</b></p>"), autoFormat = false))
    }

    @Test
    fun markdownFilesAreNotReformatted() {
        val text = "Line one\nLine two\n"
        assertEquals(text, ImportConverter.convert(ImportSource.Markdown(text), autoFormat = true))
    }

    @Test
    fun titleComesFromSubjectHeadingFileNameOrFirstLine() {
        assertEquals("Shared page", ImportConverter.suggestTitle("# Heading", "file.docx", "Shared page"))
        assertEquals("My Report", ImportConverter.suggestTitle("Intro\n\n## My **Report**", "file.docx"))
        assertEquals("quarterly-plan", ImportConverter.suggestTitle("Body", "quarterly-plan.pdf"))
        assertEquals(
            "This is the first line of a rather long text that keeps",
            ImportConverter.suggestTitle("This is the first line of a rather long text that keeps going on and on")
        )
        assertEquals("Link text more", ImportConverter.suggestTitle("[Link text](https://x.io) more"))
        assertEquals(ImportConverter.DEFAULT_TITLE, ImportConverter.suggestTitle("  \n"))
    }

    @Test
    fun titlesAreSanitized() {
        assertEquals("a b c d", ImportConverter.sanitize("a/b\\c: d"))
        assertEquals("notes", ImportConverter.sanitize("..notes.md"))
        assertEquals("x".repeat(80), ImportConverter.sanitize("x".repeat(200)))
    }
}
