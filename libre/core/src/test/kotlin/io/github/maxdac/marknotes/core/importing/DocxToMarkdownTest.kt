package io.github.maxdac.marknotes.core.importing

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocxToMarkdownTest {
    private val png = byteArrayOf(1, 2, 3)

    private fun docx(body: String, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
        val document = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="$W" xmlns:r="$R" xmlns:a="$A"><w:body>$body</w:body></w:document>"""
        val styles = """<w:styles xmlns:w="$W">
            <w:style w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
            <w:style w:styleId="Titre2"><w:name w:val="heading 2"/></w:style>
            <w:style w:styleId="MyHeading"><w:name w:val="My heading"/><w:basedOn w:val="Heading1"/></w:style>
            <w:style w:styleId="Quote"><w:name w:val="Quote"/></w:style>
            <w:style w:styleId="Code"><w:name w:val="Code"/></w:style>
            </w:styles>"""
        val numbering = """<w:numbering xmlns:w="$W">
            <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/></w:lvl>
              <w:lvl w:ilvl="1"><w:numFmt w:val="bullet"/></w:lvl></w:abstractNum>
            <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/></w:lvl>
            </w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
            <w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
            </w:numbering>"""
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Target="https://example.com" TargetMode="External"/>
            <Relationship Id="rId2" Target="media/image1.png"/>
            </Relationships>"""
        val files = mapOf(
            "[Content_Types].xml" to "<Types/>".toByteArray(),
            "word/document.xml" to document.toByteArray(),
            "word/styles.xml" to styles.toByteArray(),
            "word/numbering.xml" to numbering.toByteArray(),
            "word/_rels/document.xml.rels" to rels.toByteArray(),
            "word/media/image1.png" to png
        ) + extra
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun p(text: String, style: String? = null, num: Pair<Int, Int>? = null): String {
        val props = buildString {
            style?.let { append("<w:pStyle w:val=\"$it\"/>") }
            num?.let { (id, level) -> append("<w:numPr><w:ilvl w:val=\"$level\"/><w:numId w:val=\"$id\"/></w:numPr>") }
        }
        return "<w:p><w:pPr>$props</w:pPr><w:r><w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"
    }

    @Test
    fun headingsListsQuotesAndCode() {
        val body = p("Report", "Heading1") + p("Section", "Titre2") + p("Derived", "MyHeading") +
            p("Apple", num = 1 to 0) + p("Green", num = 1 to 1) + p("Step", num = 2 to 0) + p("Step", num = 2 to 0) +
            p("Wise words", "Quote") + p("val x = 1", "Code") + p("x * 2", "Code") + p("End")
        assertEquals(
            "# Report\n\n## Section\n\n# Derived\n\n- Apple\n    - Green\n1. Step\n2. Step\n\n> Wise words\n\n" +
                "```\nval x = 1\nx * 2\n```\n\nEnd\n",
            DocxToMarkdown.convert(docx(body))
        )
    }

    @Test
    fun runFormattingLinksAndBreaks() {
        val body = "<w:p>" +
            "<w:r><w:rPr><w:b/></w:rPr><w:t>Bold</w:t></w:r>" +
            "<w:r><w:t xml:space=\"preserve\"> and </w:t></w:r>" +
            "<w:r><w:rPr><w:i/><w:b w:val=\"0\"/></w:rPr><w:t>italic</w:t></w:r>" +
            "<w:r><w:rPr><w:strike/></w:rPr><w:t xml:space=\"preserve\"> old</w:t></w:r>" +
            "<w:r><w:rPr><w:vanish/></w:rPr><w:t>hidden</w:t></w:r>" +
            "<w:r><w:br/><w:t>2*3 </w:t></w:r>" +
            "<w:hyperlink r:id=\"rId1\"><w:r><w:t>site</w:t></w:r></w:hyperlink>" +
            "<w:hyperlink w:anchor=\"x\"><w:r><w:t> here</w:t></w:r></w:hyperlink>" +
            "</w:p>"
        assertEquals(
            "**Bold** and *italic* ~~old~~\\\n2\\*3 [site](https://example.com) here\n",
            DocxToMarkdown.convert(docx(body))
        )
    }

    @Test
    fun tablesAndImages() {
        val table = "<w:tbl><w:tr><w:tc>${p("Name")}</w:tc><w:tc>${p("Qty")}</w:tc></w:tr>" +
            "<w:tr><w:tc>${p("Pen")}</w:tc><w:tc>${p("2")}</w:tc></w:tr></w:tbl>"
        val image = "<w:p><w:r><w:drawing><a:graphic><a:graphicData><a:blip r:embed=\"rId2\"/>" +
            "</a:graphicData></a:graphic></w:drawing></w:r></w:p>"
        val saved = mutableListOf<ByteArray>()
        val sink = ImageSink { bytes, extension, _ ->
            saved += bytes
            "attachments/hash.$extension"
        }
        assertEquals(
            "| Name | Qty |\n| --- | --- |\n| Pen | 2 |\n\n![image1](attachments/hash.png)\n",
            DocxToMarkdown.convert(docx(table + image), sink)
        )
        assertArrayEquals(png, saved.single())
        assertEquals("Name\tQty\nPen\t2\n", DocxToMarkdown.plainText(docx(table + image)))
    }

    @Test
    fun plainTextDropsFormatting() {
        val body = p("Report", "Heading1") + p("Apple", num = 1 to 0)
        assertEquals("Report\n\nApple\n", DocxToMarkdown.plainText(docx(body)))
    }

    @Test
    fun rejectsFilesThatAreNotWordDocuments() {
        assertThrows(ImportException::class.java) { DocxToMarkdown.convert("not a zip".toByteArray()) }
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use {
                it.putNextEntry(ZipEntry("other.txt"))
                it.closeEntry()
            }
        }.toByteArray()
        assertThrows(ImportException::class.java) { DocxToMarkdown.convert(zip) }
    }

    @Test
    fun doctypeDeclarationsAreRefused() {
        val evil = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <w:document xmlns:w="$W"><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>"""
        assertThrows(ImportException::class.java) {
            DocxToMarkdown.convert(docx("", mapOf("word/document.xml" to evil.toByteArray())))
        }
    }

    private companion object {
        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    }
}
