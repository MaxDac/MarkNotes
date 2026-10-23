package io.github.maxdac.marknotes.core.importing

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Converts HTML (rich text shared by browsers, mail and office apps, or `.html` files) to Markdown.
 * Images are not downloaded: `<img>` with a web address becomes a link to it, inline data images are dropped.
 */
object HtmlToMarkdown {
    fun convert(html: String, baseUri: String = ""): String = render(html, baseUri, plain = false)

    /** The readable text of [html]: block structure and list markers are kept, formatting and links aren't. */
    fun plainText(html: String): String = render(html, "", plain = true)

    private fun render(html: String, baseUri: String, plain: Boolean): String {
        val doc = Jsoup.parse(html, baseUri)
        doc.select("script, style, noscript, template, head, title, meta, link, svg, iframe, object").remove()
        val text = HtmlRenderer(plain).blocks(doc.body())
        return MarkdownText.tidy(if (plain) text.replace(MarkdownText.HARD_BREAK, "\n") else text)
    }
}

private class HtmlRenderer(private val plain: Boolean) {
    private class Part(val text: String, val isList: Boolean)

    private class Style(val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false)

    fun blocks(parent: Element): String = parts(parent).joinToString("\n\n") { it.text }

    private fun parts(parent: Element): List<Part> {
        val result = mutableListOf<Part>()
        val inline = StringBuilder()
        fun flush() {
            paragraphs(inline.toString())?.let { result += Part(it, isList = false) }
            inline.clear()
        }
        for (node in parent.childNodes()) {
            if (node is Element && (node.normalName() in BLOCK_TAGS || containsBlock(node))) {
                flush()
                result += block(node)
            } else {
                inline.append(inline(node, Style()))
            }
        }
        flush()
        return result
    }

    private fun containsBlock(element: Element): Boolean = element.select(BLOCK_SELECTOR).any { it !== element }

    @Suppress("CyclomaticComplexMethod")
    private fun block(el: Element): List<Part> = when (val tag = el.normalName()) {
        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            val text = singleLine(inlineChildren(el, Style()))
            when {
                text.isEmpty() -> emptyList()
                plain -> listOf(Part(text, false))
                else -> listOf(Part("#".repeat(tag[1].digitToInt()) + " " + text, false))
            }
        }

        "p" -> listOfNotNull(paragraphs(inlineChildren(el, Style()))?.let { Part(it, false) })

        "ul", "ol" -> listOfNotNull(list(el).takeIf { it.isNotBlank() }?.let { Part(it, true) })

        "li" -> listOf(Part(listItem(el, "- "), true))

        "dt" -> listOfNotNull(
            paragraphs(inlineChildren(el, Style(bold = true)))?.let { Part(it, false) }
        )

        "blockquote" -> {
            val inner = blocks(el)
            when {
                inner.isBlank() -> emptyList()
                plain -> listOf(Part(inner, false))
                else -> listOf(Part(inner.lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" }, false))
            }
        }

        "pre" -> {
            val code = el.wholeText().trimEnd().trimStart('\n')
            when {
                code.isBlank() -> emptyList()
                plain -> listOf(Part(code, false))
                else -> listOf(Part(MarkdownText.fence(code, language(el)), false))
            }
        }

        "table" -> table(el)

        "hr" -> if (plain) emptyList() else listOf(Part("---", false))

        else -> parts(el)
    }

    private fun list(el: Element): String {
        val ordered = el.normalName() == "ol"
        var number = el.attr("start").trim().toIntOrNull() ?: 1
        val items = mutableListOf<String>()
        var lastMarkerWidth = 2
        for (child in el.children()) {
            when (child.normalName()) {
                "li" -> {
                    val marker = if (ordered) "$number. " else "- "
                    items += listItem(child, marker)
                    lastMarkerWidth = marker.length
                    number++
                }

                // Invalid but common: a nested list directly inside the parent list.
                "ul", "ol" -> {
                    val nested = list(child)
                    if (nested.isBlank()) continue
                    if (items.isEmpty()) {
                        items += nested
                    } else {
                        items[items.lastIndex] += "\n" + nested.prependIndent(" ".repeat(lastMarkerWidth))
                    }
                }
            }
        }
        return items.joinToString("\n")
    }

    private fun listItem(li: Element, marker: String): String {
        val checkbox = li.children().firstOrNull(::isCheckbox)
            ?: li.children().firstOrNull()?.takeIf { it.normalName() in setOf("p", "label", "span") }
                ?.children()?.firstOrNull(::isCheckbox)
        val task = when {
            checkbox == null || plain -> ""
            checkbox.hasAttr("checked") -> "[x] "
            else -> "[ ] "
        }
        val parts = parts(li)
        val content = StringBuilder()
        parts.forEachIndexed { i, part ->
            if (i > 0) content.append(if (part.isList) "\n" else "\n\n")
            content.append(part.text)
        }
        return marker + task + MarkdownText.indentContinuation(content.toString(), " ".repeat(marker.length))
    }

    private fun isCheckbox(el: Element) = el.normalName() == "input" && el.attr("type").equals("checkbox", true)

    private fun table(el: Element): List<Part> {
        val rows = rowsOf(el)
        val columns = rows.maxOfOrNull { it.size } ?: 0
        val layout = columns <= 1 || rows.any { row -> row.any { it.selectFirst(LAYOUT_SELECTOR) != null } }
        if (layout) return rows.flatten().flatMap { parts(it) }
        if (plain) {
            val text = rows.joinToString("\n") { row -> row.joinToString("\t") { singleLine(blocks(it)) } }
            return listOf(Part(text, false))
        }
        val cells = rows.map { row -> row.map { singleLine(blocks(it)) } }
        return listOf(Part(MarkdownText.table(cells), false))
    }

    private fun rowsOf(table: Element): List<List<Element>> {
        val rows = mutableListOf<Element>()
        for (child in table.children()) {
            when (child.normalName()) {
                "tr" -> rows.add(child)
                "thead", "tbody", "tfoot" -> rows.addAll(child.children().filter { it.normalName() == "tr" })
            }
        }
        return rows.map { row -> row.children().filter { it.normalName() == "td" || it.normalName() == "th" } }
            .filter { it.isNotEmpty() }
    }

    private fun language(pre: Element): String {
        val classes = pre.classNames() + (pre.selectFirst("code")?.classNames() ?: emptySet())
        return classes.firstNotNullOfOrNull { name ->
            name.removePrefix("language-").removePrefix("lang-").takeIf { it != name && it.isNotBlank() }
        }.orEmpty()
    }

    private fun inlineChildren(el: Element, style: Style): String =
        el.childNodes().joinToString("") { inline(it, style) }

    private fun inline(node: Node, style: Style): String = when (node) {
        is TextNode -> {
            val text = collapse(node.wholeText)
            if (plain) text else MarkdownText.escapeInline(text)
        }

        is Element -> inlineElement(node, style)

        else -> ""
    }

    @Suppress("CyclomaticComplexMethod")
    private fun inlineElement(el: Element, style: Style): String {
        val css = el.attr("style").lowercase()
        return when (el.normalName()) {
            "br" -> MarkdownText.HARD_BREAK

            "img" -> image(el)

            "a" -> anchor(el, style)

            "code", "kbd", "samp", "tt" -> code(el)

            "strong", "b" -> if (NOT_BOLD.containsMatchIn(
                    css
                )
            ) {
                styled(el, style, css)
            } else {
                emphasis(el, style, bold = true)
            }

            "em", "i", "cite", "dfn", "var" -> emphasis(el, style, italic = true)

            "s", "strike", "del" -> emphasis(el, style, strike = true)

            "input" -> ""

            else -> {
                val text = styled(el, style, css)
                if (el.normalName() in BLOCK_TAGS) " $text " else text
            }
        }
    }

    /** Applies the formatting Google Docs and others express with CSS instead of tags. */
    private fun styled(el: Element, style: Style, css: String): String {
        if (css.isEmpty()) return inlineChildren(el, style)
        if (MONOSPACE.containsMatchIn(css)) return code(el)
        return emphasis(
            el,
            style,
            bold = BOLD.containsMatchIn(css),
            italic = ITALIC.containsMatchIn(css),
            strike = STRIKE.containsMatchIn(css)
        )
    }

    private fun emphasis(
        el: Element,
        style: Style,
        bold: Boolean = false,
        italic: Boolean = false,
        strike: Boolean = false
    ): String {
        val addBold = bold && !style.bold
        val addItalic = italic && !style.italic
        val addStrike = strike && !style.strike
        var text = inlineChildren(el, Style(style.bold || bold, style.italic || italic, style.strike || strike))
        if (plain || text.contains(MarkdownText.HARD_BREAK)) return text
        if (addStrike) text = MarkdownText.wrap(text, "~~")
        if (addItalic) text = MarkdownText.wrap(text, "*")
        if (addBold) text = MarkdownText.wrap(text, "**")
        return text
    }

    private fun code(el: Element): String {
        val text = collapse(el.wholeText())
        return if (plain) text else MarkdownText.codeSpan(text)
    }

    private fun anchor(el: Element, style: Style): String {
        val label = inlineChildren(el, style)
        val href = el.absUrl("href").ifEmpty { el.attr("href") }.trim()
        if (plain || label.isBlank() || !LINK_SCHEMES.any { href.startsWith(it, ignoreCase = true) }) return label
        val lead = label.takeWhile { it.isWhitespace() }
        val trail = label.takeLastWhile { it.isWhitespace() }
        val text = label.trim()
        val link = if (text == MarkdownText.escapeInline(href) && !href.contains(' ')) {
            "<$href>"
        } else {
            MarkdownText.link(text.replace(MarkdownText.HARD_BREAK, " "), href)
        }
        return lead + link + trail
    }

    private fun image(el: Element): String {
        val src = el.absUrl("src").ifEmpty { el.attr("src") }.trim()
        val alt = collapse(el.attr("alt")).trim()
        if (plain) return alt
        if (!src.startsWith("http://", true) && !src.startsWith("https://", true)) return ""
        return MarkdownText.link(MarkdownText.escapeInline(alt.ifEmpty { "Image" }), src)
    }

    /** Splits inline content on empty hard-broken lines into paragraphs and escapes line starts. */
    private fun paragraphs(inline: String): String? {
        val groups = mutableListOf<MutableList<String>>(mutableListOf())
        for (raw in inline.split('\n')) {
            val line = raw.trim()
            val content = line.removeSuffix("\\").trim()
            if (content.isEmpty()) {
                if (groups.last().isNotEmpty()) groups += mutableListOf<String>()
            } else {
                groups.last() += if (line.endsWith("\\")) "$content\\" else content
            }
        }
        val text = groups.filter { it.isNotEmpty() }.joinToString("\n\n") { lines ->
            val last = lines.lastIndex
            lines.mapIndexed { i, line ->
                val clean = if (i == last) line.removeSuffix("\\").trimEnd() else line
                if (plain) clean else MarkdownText.escapeLineStart(clean)
            }.joinToString("\n")
        }
        return text.ifBlank { null }
    }

    private fun singleLine(text: String): String = text
        .replace(MarkdownText.HARD_BREAK, " ")
        .replace('\n', ' ')
        .replace(SPACES, " ")
        .trim()

    private fun collapse(text: String): String = text.replace('\u00A0', ' ').replace(WHITESPACE, " ")

    private companion object {
        val BLOCK_TAGS = setOf(
            "address", "article", "aside", "blockquote", "body", "center", "dd", "details", "dialog", "div", "dl",
            "dt", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
            "header", "hgroup", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "summary", "table", "ul"
        )
        val BLOCK_SELECTOR = BLOCK_TAGS.joinToString(", ")
        const val LAYOUT_SELECTOR = "table, ul, ol, pre, blockquote, h1, h2, h3, h4, h5, h6"
        val LINK_SCHEMES = listOf("http://", "https://", "mailto:", "ftp://", "tel:")
        val WHITESPACE = Regex("[\\s\\u200B]+")
        val SPACES = Regex(" {2,}")
        val BOLD = Regex("font-weight\\s*:\\s*(bold|bolder|[6-9]00)")
        val NOT_BOLD = Regex("font-weight\\s*:\\s*(normal|lighter|[1-5]00)")
        val ITALIC = Regex("font-style\\s*:\\s*(italic|oblique)")
        val STRIKE = Regex("text-decoration[a-z-]*\\s*:[^;]*line-through")
        val MONOSPACE = Regex("font-family\\s*:[^;]*(monospace|courier|consolas|menlo|monaco)")
    }
}
