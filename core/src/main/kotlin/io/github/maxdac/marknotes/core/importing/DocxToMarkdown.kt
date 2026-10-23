package io.github.maxdac.marknotes.core.importing

import io.github.maxdac.marknotes.core.notes.Attachments
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException

/**
 * Converts Word documents (`.docx`, Office Open XML) to Markdown: headings, bold/italic/strikethrough, links,
 * bulleted and numbered lists, quotes, code paragraphs, tables and embedded images (handed to an [ImageSink]).
 */
object DocxToMarkdown {
    fun convert(bytes: ByteArray, images: ImageSink = ImageSink.NONE): String =
        MarkdownText.tidy(DocxReader(unzip(bytes), images, plain = false).render())

    /** The text of the document, one paragraph per line, table cells separated by tabs. */
    fun plainText(bytes: ByteArray): String =
        MarkdownText.tidy(DocxReader(unzip(bytes), ImageSink.NONE, plain = true).render())

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = try {
            ZipInputStream(ByteArrayInputStream(bytes)).use(::wordEntries)
        } catch (e: ZipException) {
            throw ImportException(NOT_A_DOCUMENT, e)
        }
        if (DOCUMENT !in entries) throw ImportException(NOT_A_DOCUMENT)
        return entries
    }

    /** Reads the `word/` entries, the only ones the conversion needs, within the size limits. */
    private fun wordEntries(zip: ZipInputStream): Map<String, ByteArray> {
        val entries = HashMap<String, ByteArray>()
        var total = 0L
        generateSequence { zip.nextEntry }
            .filter { !it.isDirectory && it.name.removePrefix("/").startsWith("word/") }
            .forEach { entry ->
                val data = readLimited(zip, MAX_ENTRY_BYTES)
                total += data.size
                if (total > MAX_TOTAL_BYTES) throw ImportException(TOO_LARGE)
                entries[entry.name.removePrefix("/")] = data
            }
        return entries
    }

    private fun readLimited(zip: ZipInputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > max) throw ImportException(TOO_LARGE)
        }
        return out.toByteArray()
    }

    internal const val DOCUMENT = "word/document.xml"
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024
    private const val NOT_A_DOCUMENT = "This file isn't a valid Word document."
    private const val TOO_LARGE = "This document is too large to import."
}

private class DocxReader(
    private val entries: Map<String, ByteArray>,
    private val images: ImageSink,
    private val plain: Boolean
) {
    private class Run(val text: String, val bold: Boolean, val italic: Boolean, val strike: Boolean, val raw: Boolean) {
        /** Whether [other] can be merged into this run: both formatted text with the same emphasis. */
        fun mergesWith(other: Run) =
            !raw && !other.raw && bold == other.bold && italic == other.italic && strike == other.strike
    }

    private val styles = StyleSheet(entries["word/styles.xml"]?.let(::parseXml))
    private val numbering = Numbering(entries["word/numbering.xml"]?.let(::parseXml))
    private val relationships = relationships(entries["word/_rels/document.xml.rels"]?.let(::parseXml))
    private val counters = HashMap<String, IntArray>()
    private val out = BlockBuilder()
    private val code = mutableListOf<String>()

    fun render(): String {
        val document = parseXml(entries.getValue(DocxToMarkdown.DOCUMENT))
        val body = document.documentElement.child("body") ?: return ""
        blocks(body)
        flushCode()
        return out.toString()
    }

    private fun blocks(parent: Element) {
        for (el in parent.childElements()) {
            when (el.localName) {
                "p" -> paragraph(el)
                "tbl" -> table(el)
                "sdt" -> el.child("sdtContent")?.let(::blocks)
                "customXml", "ins" -> blocks(el)
            }
        }
    }

    private fun paragraph(p: Element) {
        val properties = p.child("pPr")
        val styleId = properties?.child("pStyle")?.attr("val")
        val numPr = properties?.child("numPr")
        val numId = numPr?.child("numId")?.attr("val")?.takeIf { it != "0" }
        val level = numPr?.child("ilvl")?.attr("val")?.toIntOrNull() ?: 0
        if (!plain && styles.isCode(styleId)) {
            code += runs(p, emphasis = false, raw = true)
            return
        }
        flushCode()
        val heading = styles.headingLevel(styleId)
            ?: properties?.child("outlineLvl")?.attr("val")?.toIntOrNull()?.takeIf { it < MAX_HEADING }?.plus(1)
        val text = trimBreaks(runs(p, emphasis = heading == null, raw = false))
        if (text.isEmpty()) return
        when {
            plain -> out.block(text)
            heading != null -> out.block("#".repeat(heading) + " " + singleLine(text))
            numId != null -> out.item(LIST_INDENT.repeat(level.coerceIn(0, MAX_LEVEL)) + marker(numId, level) + text)
            styles.isQuote(styleId) -> out.block(text.lines().joinToString("\n") { "> $it" })
            else -> out.block(text.lines().joinToString("\n", transform = MarkdownText::escapeLineStart))
        }
    }

    private fun marker(numId: String, level: Int): String {
        val format = numbering.format(numId, level)
        if (format == null || format == "bullet" || format == "none") return "- "
        val levels = counters.getOrPut(numId) { IntArray(MAX_LEVEL + 1) }
        val index = level.coerceIn(0, MAX_LEVEL)
        levels[index]++
        for (deeper in index + 1..MAX_LEVEL) levels[deeper] = 0
        return "${numbering.start(numId, level) + levels[index] - 1}. "
    }

    private fun flushCode() {
        if (code.isEmpty()) return
        out.block(MarkdownText.fence(code.joinToString("\n")))
        code.clear()
    }

    private fun table(tbl: Element) {
        val rows = tbl.childElements().filter { it.localName == "tr" }
            .map { row -> row.childElements().filter { it.localName == "tc" } }
            .filter { it.isNotEmpty() }
        if (rows.isEmpty()) return
        if (rows.maxOf { it.size } <= 1) {
            rows.flatten().forEach(::blocks)
            return
        }
        flushCode()
        val cells = rows.map { row ->
            row.map { cell ->
                cell.descendants("p").joinToString(" ") { runs(it, emphasis = true, raw = false).trim() }
                    .let(::singleLine)
            }
        }
        out.block(if (plain) cells.joinToString("\n") { it.joinToString("\t") } else MarkdownText.table(cells))
    }

    /** The inline content of a paragraph (or hyperlink) as Markdown, or as text when [raw]. */
    private fun runs(parent: Element, emphasis: Boolean, raw: Boolean): String {
        val collected = mutableListOf<Run>()
        collect(parent, collected, raw)
        return format(collected, emphasis && !plain && !raw, raw)
    }

    private fun collect(parent: Element, into: MutableList<Run>, raw: Boolean) {
        for (el in parent.childElements()) {
            when (el.localName) {
                "r" -> run(el, into, raw)
                "hyperlink" -> hyperlink(el, into, raw)
                "ins", "smartTag", "fldSimple", "customXml", "sdtContent" -> collect(el, into, raw)
                "sdt" -> el.child("sdtContent")?.let { collect(it, into, raw) }
            }
        }
    }

    private fun hyperlink(el: Element, into: MutableList<Run>, raw: Boolean) {
        val target = el.attr("id")?.let { relationships[it] }?.takeIf { it.external }?.target
        if (target == null || plain || raw) {
            collect(el, into, raw)
            return
        }
        val label = runs(el, emphasis = true, raw = false).trim()
        if (label.isNotEmpty()) into += Run(MarkdownText.link(singleLine(label), target), false, false, false, true)
    }

    private fun run(r: Element, into: MutableList<Run>, raw: Boolean) {
        val props = r.child("rPr")
        if (props.flag("vanish")) return
        val style = Run("", props.flag("b"), props.flag("i"), props.flag("strike") || props.flag("dstrike"), false)
        for (el in r.childElements()) runPart(el, style, raw)?.let { into += it }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun runPart(el: Element, style: Run, raw: Boolean): Run? {
        fun text(value: String) = Run(value, style.bold, style.italic, style.strike, raw = false)
        return when (el.localName) {
            "t" -> text(el.textContent)

            "tab" -> text(if (raw) "\t" else " ")

            "noBreakHyphen" -> text("-")

            "br", "cr" -> if (el.attr("type") == null || el.attr("type") == "textWrapping") {
                Run(if (raw || plain) "\n" else MarkdownText.HARD_BREAK, false, false, false, raw = true)
            } else {
                null
            }

            "drawing", "pict", "object" -> if (!plain && !raw) image(el) else null

            else -> null
        }
    }

    private fun image(el: Element): Run? {
        val id = el.descendants("blip").firstNotNullOfOrNull { it.attr("embed") }
            ?: el.descendants("imagedata").firstNotNullOfOrNull { it.attr("id") }
            ?: return null
        val target = relationships[id]?.takeIf { !it.external }?.target ?: return null
        val path = resolve(target)
        val bytes = entries[path] ?: return null
        val fileName = path.substringAfterLast('/')
        val extension = when (val ext = fileName.substringAfterLast('.', "").lowercase()) {
            "jpeg", "jpg" -> "jpg"
            "png", "gif", "webp" -> ext
            else -> return null
        }
        val saved = images.save(bytes, extension, fileName) ?: return null
        return Run(Attachments.markdown(fileName, saved), false, false, false, raw = true)
    }

    private fun resolve(target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = ArrayDeque(listOf("word"))
        for (part in target.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun format(runs: List<Run>, emphasis: Boolean, raw: Boolean): String {
        val merged = mutableListOf<Run>()
        for (run in runs) {
            val last = merged.lastOrNull()
            merged += if (last != null && last.mergesWith(run)) {
                merged.removeAt(merged.lastIndex)
                Run(last.text + run.text, run.bold, run.italic, run.strike, raw = false)
            } else {
                run
            }
        }
        return merged.joinToString("") { run ->
            if (run.raw || raw || plain) run.text else emphasize(run, emphasis)
        }
    }

    private fun emphasize(run: Run, emphasis: Boolean): String {
        var text = MarkdownText.escapeInline(run.text)
        if (!emphasis) return text
        if (run.strike) text = MarkdownText.wrap(text, "~~")
        if (run.italic) text = MarkdownText.wrap(text, "*")
        if (run.bold) text = MarkdownText.wrap(text, "**")
        return text
    }

    /** Trims whitespace and line breaks at both ends (a trailing hard break would show as a backslash). */
    private fun trimBreaks(text: String): String {
        var result = text.trim()
        while (true) {
            result = when {
                result.endsWith(MarkdownText.HARD_BREAK.trim()) && !result.endsWith("\\\\") ->
                    result.dropLast(1).trim()

                result.startsWith(MarkdownText.HARD_BREAK) -> result.drop(MarkdownText.HARD_BREAK.length).trim()

                else -> return result
            }
        }
    }

    private fun singleLine(text: String) = text.replace(MarkdownText.HARD_BREAK, " ").replace('\n', ' ').trim()

    private fun Element?.flag(name: String): Boolean {
        val el = this?.child(name) ?: return false
        return el.attr("val")?.lowercase() !in setOf("0", "false", "off", "none")
    }

    private class Relationship(val target: String, val external: Boolean)

    private fun relationships(doc: Document?): Map<String, Relationship> {
        doc ?: return emptyMap()
        return doc.documentElement.childElements().filter { it.localName == "Relationship" }.mapNotNull { rel ->
            val id = rel.attr("Id") ?: return@mapNotNull null
            val target = rel.attr("Target") ?: return@mapNotNull null
            id to Relationship(target, rel.attr("TargetMode") == "External")
        }.toMap()
    }

    private companion object {
        const val LIST_INDENT = "    "
        const val MAX_LEVEL = 8
        const val MAX_HEADING = 6
    }
}

/** Paragraph styles of the document (`word/styles.xml`), resolved through their `basedOn` chain. */
private class StyleSheet(doc: Document?) {
    private class Style(val name: String, val basedOn: String?)

    private val styles: Map<String, Style> = doc?.documentElement?.childElements()
        ?.filter { it.localName == "style" }
        ?.mapNotNull { el ->
            val id = el.attr("styleId") ?: return@mapNotNull null
            id to Style(el.child("name")?.attr("val").orEmpty().lowercase(), el.child("basedOn")?.attr("val"))
        }?.toMap().orEmpty()

    fun headingLevel(styleId: String?): Int? = resolve(styleId) { id, name ->
        when {
            name == "title" || id.equals("Title", ignoreCase = true) -> 1
            name == "subtitle" || id.equals("Subtitle", ignoreCase = true) -> 2
            else -> (HEADING.find(name) ?: HEADING.find(id))?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 6)
        }
    }

    fun isQuote(styleId: String?): Boolean = resolve(styleId) { id, name ->
        if ("quote" in name || "quote" in id.lowercase()) true else null
    } == true

    fun isCode(styleId: String?): Boolean = resolve(styleId) { id, name ->
        if (CODE.containsMatchIn(name) || CODE.containsMatchIn(id.lowercase())) true else null
    } == true

    private fun <T> resolve(styleId: String?, check: (id: String, name: String) -> T?): T? {
        var id = styleId
        repeat(MAX_DEPTH) {
            val current = id ?: return null
            val style = styles[current]
            check(current, style?.name.orEmpty())?.let { return it }
            id = style?.basedOn
        }
        return null
    }

    private companion object {
        const val MAX_DEPTH = 8
        val HEADING = Regex("(?i)heading\\s*(\\d)")
        val CODE = Regex("code|preformatted|source")
    }
}

/** List definitions of the document (`word/numbering.xml`). */
private class Numbering(doc: Document?) {
    private class Level(val format: String?, val start: Int)

    private val levels: Map<String, Map<Int, Level>>
    private val abstractOf: Map<String, String>

    init {
        val root = doc?.documentElement
        levels = root?.childElements()?.filter { it.localName == "abstractNum" }?.mapNotNull { abstract ->
            val id = abstract.attr("abstractNumId") ?: return@mapNotNull null
            id to abstract.childElements().filter { it.localName == "lvl" }.mapNotNull { lvl ->
                val level = lvl.attr("ilvl")?.toIntOrNull() ?: return@mapNotNull null
                level to Level(
                    lvl.child("numFmt")?.attr("val"),
                    lvl.child("start")?.attr("val")?.toIntOrNull() ?: 1
                )
            }.toMap()
        }?.toMap().orEmpty()
        abstractOf = root?.childElements()?.filter { it.localName == "num" }?.mapNotNull { num ->
            val id = num.attr("numId") ?: return@mapNotNull null
            val abstract = num.child("abstractNumId")?.attr("val") ?: return@mapNotNull null
            id to abstract
        }?.toMap().orEmpty()
    }

    fun format(numId: String, level: Int): String? = level(numId, level)?.format

    fun start(numId: String, level: Int): Int = level(numId, level)?.start ?: 1

    private fun level(numId: String, level: Int): Level? = abstractOf[numId]?.let { levels[it]?.get(level) }
}

private fun parseXml(bytes: ByteArray): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.isExpandEntityReferences = false
    // Not every platform parser supports these (Android's doesn't resolve external entities anyway).
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    runCatching { factory.isXIncludeAware = false }
    return try {
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (e: SAXException) {
        throw ImportException("This Word document is damaged and can't be read.", e)
    }
}

private fun Element.childElements(): List<Element> {
    val result = ArrayList<Element>()
    var node = firstChild
    while (node != null) {
        if (node.nodeType == Node.ELEMENT_NODE) result += node as Element
        node = node.nextSibling
    }
    return result
}

private fun Element.child(localName: String): Element? = childElements().firstOrNull { it.localName == localName }

private fun Element.descendants(localName: String): List<Element> {
    val result = ArrayList<Element>()
    fun walk(el: Element) {
        for (child in el.childElements()) {
            if (child.localName == localName) result += child
            walk(child)
        }
    }
    walk(this)
    return result
}

/** Attribute by local name, whatever its namespace prefix (`w:val`, `r:id`, ...). */
private fun Element.attr(localName: String): String? {
    val attributes = attributes
    for (i in 0 until attributes.length) {
        val attribute = attributes.item(i)
        val name = attribute.localName ?: attribute.nodeName.substringAfter(':')
        if (name == localName) return attribute.nodeValue
    }
    return null
}
