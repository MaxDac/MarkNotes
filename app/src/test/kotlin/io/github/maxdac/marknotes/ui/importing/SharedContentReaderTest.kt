package io.github.maxdac.marknotes.ui.importing

import io.github.maxdac.marknotes.ui.importing.SharedContentReader.Companion.decode
import io.github.maxdac.marknotes.ui.importing.SharedContentReader.Companion.kindOf
import io.github.maxdac.marknotes.ui.importing.SharedContentReader.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedContentReaderTest {
    @Test
    fun filesAreClassifiedByMimeTypeThenExtension() {
        assertEquals(Kind.PDF, kindOf("application/pdf", null))
        assertEquals(Kind.DOCX, kindOf("application/octet-stream", "Report.DOCX"))
        assertEquals(Kind.IMAGE, kindOf("image/png", "x.bin"))
        assertEquals(Kind.HTML, kindOf("text/html", null))
        assertEquals(Kind.MARKDOWN, kindOf("text/plain", "notes.md"))
        assertEquals(Kind.TEXT, kindOf("text/csv", "data.csv"))
        assertEquals(Kind.TEXT, kindOf(null, "readme.txt"))
        assertEquals(Kind.UNSUPPORTED, kindOf("application/zip", "a.zip"))
    }

    @Test
    fun textIsDecodedFromUtf8Utf16OrWindows1252() {
        assertEquals("Café", decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Café".toByteArray()))
        assertEquals("Café", decode("Café".toByteArray(Charsets.UTF_16)))
        assertEquals("Café “x”", decode("Café “x”".toByteArray(charset("windows-1252"))))
    }
}
