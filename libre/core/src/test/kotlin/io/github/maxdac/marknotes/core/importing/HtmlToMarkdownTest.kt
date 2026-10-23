package io.github.maxdac.marknotes.core.importing

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlToMarkdownTest {
    private fun md(html: String) = HtmlToMarkdown.convert(html)

    @Test
    fun headingsParagraphsAndInlineFormatting() {
        val html = "<h1>Title</h1><p>Some <b>bold</b>, <em>italic</em>, <del>gone</del> and <code>a_b</code>.</p>" +
            "<h3>Sub <i>part</i></h3>"
        assertEquals(
            "# Title\n\nSome **bold**, *italic*, ~~gone~~ and `a_b`.\n\n### Sub *part*\n",
            md(html)
        )
    }

    @Test
    fun spacesStayOutsideEmphasisMarkers() {
        assertEquals("a **bold** b\n", md("<p>a<b> bold </b>b</p>"))
    }

    @Test
    fun linksAndImages() {
        val html = "<p><a href=\"https://example.com/a b\">Example</a> <a href=\"https://x.io\">https://x.io</a> " +
            "<img src=\"https://img.io/p.png\" alt=\"Pic\"><img src=\"data:image/png;base64,AAAA\" alt=\"inline\"></p>"
        assertEquals(
            "[Example](https://example.com/a%20b) <https://x.io> [Pic](https://img.io/p.png)\n",
            md(html)
        )
    }

    @Test
    fun relativeLinksAreResolvedOrDropped() {
        assertEquals("[a](https://site.org/x)\n", HtmlToMarkdown.convert("<a href=\"/x\">a</a>", "https://site.org/"))
        assertEquals("a\n", md("<a href=\"javascript:alert(1)\">a</a>"))
    }

    @Test
    fun nestedListsAndTasks() {
        val html = "<ul><li>One<ul><li>Nested</li></ul></li><li><p>Two</p></li></ul>" +
            "<ol start=\"3\"><li>Three</li><li><input type=\"checkbox\" checked> Done</li></ol>"
        assertEquals(
            "- One\n  - Nested\n- Two\n\n3. Three\n4. [x] Done\n",
            md(html)
        )
    }

    @Test
    fun quotesCodeAndRules() {
        val html = "<blockquote><p>Quoted</p><p>Again</p></blockquote><hr>" +
            "<pre><code class=\"language-kotlin\">val x = 1\n  println(x)</code></pre>"
        assertEquals(
            "> Quoted\n>\n> Again\n\n---\n\n```kotlin\nval x = 1\n  println(x)\n```\n",
            md(html)
        )
    }

    @Test
    fun tablesBecomeGfmAndLayoutTablesAreFlattened() {
        val data = "<table><tr><th>Name</th><th>Qty</th></tr><tr><td>A|B</td><td><p>2</p></td></tr></table>"
        assertEquals("| Name | Qty |\n| --- | --- |\n| A\\|B | 2 |\n", md(data))
        val layout = "<table><tr><td><h2>Newsletter</h2><p>Hello</p></td></tr></table>"
        assertEquals("## Newsletter\n\nHello\n", md(layout))
    }

    @Test
    fun lineBreaksAndEscaping() {
        assertEquals("Line one\\\nLine two\n\nNext\n", md("<p>Line one<br>Line two<br><br>Next</p>"))
        assertEquals("\\# not a heading \\*x\\* \\[y\\]\n", md("<p># not a heading *x* [y]</p>"))
        assertEquals("1\\. Not a list\n", md("<p>1. Not a list</p>"))
    }

    @Test
    fun googleDocsWrapperAndCssFormatting() {
        val html = "<meta charset=\"utf-8\"><b style=\"font-weight:normal;\" id=\"docs-internal-guid-1\">" +
            "<h2><span style=\"font-weight:400\">Plan</span></h2>" +
            "<p><span style=\"font-weight:700\">Bold</span><span> and </span>" +
            "<span style=\"font-style:italic\">italic</span></p></b>"
        assertEquals("## Plan\n\n**Bold** and *italic*\n", md(html))
    }

    @Test
    fun scriptsAndStylesAreDropped() {
        assertEquals("Text\n", md("<style>p{}</style><script>alert(1)</script><p>Text</p>"))
    }

    @Test
    fun plainTextKeepsStructureWithoutMarkup() {
        val html = "<h1>Title</h1><p>Some <b>bold</b> <a href=\"https://x.io\">link</a></p><ul><li>a</li></ul>"
        assertEquals("Title\n\nSome bold link\n\n- a\n", HtmlToMarkdown.plainText(html))
    }
}
