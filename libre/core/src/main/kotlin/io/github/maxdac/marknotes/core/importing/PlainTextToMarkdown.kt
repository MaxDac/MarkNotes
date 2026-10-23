package io.github.maxdac.marknotes.core.importing

/**
 * Turns plain text (copied text, `.txt` files, text extracted from PDFs) into Markdown with heuristics:
 * bullets and numbered lines become lists, standalone title-like lines become headings, indented blocks become
 * code, bare web addresses become links and hard-wrapped paragraphs are re-joined. Text that already looks like
 * Markdown is kept as it is.
 */
object PlainTextToMarkdown {
    /**
     * Converts [text]. With [reflow] the lines of a paragraph are always joined (PDF text is wrapped at the page
     * width); otherwise they are joined only when the text looks hard-wrapped, and kept as separate paragraphs when
     * it doesn't.
     */
    fun convert(text: String, reflow: Boolean = false): String {
        val normalized = normalize(text)
        if (looksLikeMarkdown(normalized)) return MarkdownText.tidy(normalized)
        val lines = normalized.lines().map { expandTabs(it).trimEnd() }
        val blocks = splitBlocks(lines)
        val wrapped = reflow || isHardWrapped(blocks)
        val levels = listIndentLevels(lines)
        val segments = blocks.flatMap { segments(it, wrapped, levels) }
        return MarkdownText.tidy(render(segments))
    }

    /** Line endings, non-breaking and zero-width spaces and the byte order mark normalised. */
    fun normalize(text: String): String = text
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u00A0', ' ')
        .replace("\u200B", "")
        .replace('\u2028', '\n')
        .replace('\u2029', '\n')

    /** True when [text] already uses Markdown syntax (headings, fences, links, emphasis, tables, task lists). */
    fun looksLikeMarkdown(text: String): Boolean = MARKDOWN_SIGNALS.any { it.containsMatchIn(text) }

    private sealed interface Segment {
        data class Paragraph(val lines: List<String>, val standalone: Boolean) : Segment
        data class Item(val level: Int, val marker: String, val text: String) : Segment
        data class Heading(val level: Int, val text: String) : Segment
        data class Code(val text: String) : Segment
    }

    private fun splitBlocks(lines: List<String>): List<List<String>> {
        val blocks = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) blocks += current
                current = mutableListOf()
            } else {
                current += line
            }
        }
        if (current.isNotEmpty()) blocks += current
        return blocks
    }

    /** Wrapped text has many non-final paragraph lines of similar length that don't end a sentence. */
    private fun isHardWrapped(blocks: List<List<String>>): Boolean {
        val inner = blocks.filter { it.size >= 2 }.flatMap { it.dropLast(1) }.filter { listItem(it) == null }
        if (inner.size < MIN_WRAPPED_LINES) return false
        val longest = inner.maxOf { it.length }
        if (longest !in MIN_WRAP_WIDTH..MAX_WRAP_WIDTH) return false
        val similar = inner.count { it.length >= longest * WRAP_LENGTH_RATIO }
        val open = inner.count { !it.trimEnd().endsWith('.') && !it.trimEnd().endsWith(':') }
        return similar >= inner.size * WRAP_SHARE && open >= inner.size * WRAP_SHARE
    }

    private fun listIndentLevels(lines: List<String>): Map<Int, Int> = lines.mapNotNull { listItem(it)?.indent }
        .distinct()
        .sorted()
        .withIndex()
        .associate { (index, indent) -> indent to minOf(index, MAX_LIST_LEVEL) }

    private class ListLine(val indent: Int, val marker: String, val text: String)

    private fun listItem(line: String): ListLine? {
        TASK.matchEntire(line)?.let { m ->
            val done = m.groupValues[2].let { it == "☑" || it == "☒" || it == "✅" || it.contains('x', true) }
            return ListLine(m.groupValues[1].length, if (done) "- [x] " else "- [ ] ", m.groupValues[3])
        }
        BULLET.matchEntire(line)?.let { m -> return ListLine(m.groupValues[1].length, "- ", m.groupValues[3]) }
        ORDERED.matchEntire(line)?.let { m ->
            return ListLine(m.groupValues[1].length, "${m.groupValues[2].toInt()}. ", m.groupValues[3])
        }
        return null
    }

    @Suppress("CyclomaticComplexMethod")
    private fun segments(block: List<String>, wrapped: Boolean, levels: Map<Int, Int>): List<Segment> {
        whole(block)?.let { return listOf(it) }
        val result = mutableListOf<Segment>()
        var paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) result += Segment.Paragraph(paragraph, standalone = false)
            paragraph = mutableListOf()
        }
        for (line in block) {
            val item = listItem(line)
            val last = result.lastOrNull()
            when {
                item != null -> {
                    flush()
                    result += Segment.Item(levels[item.indent] ?: 0, item.marker, item.text.trim())
                }

                last is Segment.Item && paragraph.isEmpty() && (wrapped || line.startsWith(" ")) ->
                    result[result.lastIndex] = last.copy(text = joinWrapped(listOf(last.text, line.trim())))

                wrapped -> paragraph += line.trim()

                else -> {
                    flush()
                    paragraph += line.trim()
                    flush()
                }
            }
        }
        flush()
        val only = result.singleOrNull()
        if (only is Segment.Paragraph && only.lines.size == 1) return listOf(only.copy(standalone = true))
        return result
    }

    /** A block that is a single segment as a whole: an indented code block or a setext heading. */
    private fun whole(block: List<String>): Segment? {
        if (block.size >= 2 && block.all { it.startsWith("    ") } && block.none { listItem(it) != null }) {
            val indent = block.minOf { line -> line.takeWhile { it == ' ' }.length }
            return Segment.Code(block.joinToString("\n") { it.drop(indent) })
        }
        if (block.size != 2 || listItem(block[0]) != null) return null
        val underline = block[1].trim()
        if (underline.length < MIN_SETEXT) return null
        return when {
            underline.all { it == '=' } -> Segment.Heading(1, block[0].trim())
            underline.all { it == '-' } -> Segment.Heading(2, block[0].trim())
            else -> null
        }
    }

    private fun render(segments: List<Segment>): String {
        val out = BlockBuilder()
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is Segment.Heading -> out.block("#".repeat(segment.level) + " " + inline(segment.text))

                is Segment.Code -> out.block(MarkdownText.fence(segment.text))

                is Segment.Item -> out.item(
                    LIST_INDENT.repeat(segment.level) + segment.marker + inline(segment.text)
                )

                is Segment.Paragraph -> {
                    val text = joinWrapped(segment.lines)
                    val level = headingLevel(segment, index, segments)
                    if (level != null) {
                        out.block("#".repeat(level) + " " + inline(text.trimEnd(':')))
                    } else {
                        out.block(MarkdownText.escapeLineStart(inline(text)))
                    }
                }
            }
        }
        return out.toString()
    }

    /** A standalone short line that reads like a title and introduces more content becomes a heading. */
    private fun headingLevel(segment: Segment.Paragraph, index: Int, segments: List<Segment>): Int? {
        if (!segment.standalone) return null
        val line = segment.lines.single()
        val next = segments.getOrNull(index + 1) ?: return null
        val introduces = when (next) {
            is Segment.Paragraph -> joinWrapped(next.lines).length > line.length
            is Segment.Item, is Segment.Code -> true
            is Segment.Heading -> false
        }
        if (!introduces || !isTitleLike(line)) return null
        SECTION_NUMBER.find(line)?.let { m ->
            return minOf(m.groupValues[1].count { it == '.' } + 2, MAX_HEADING)
        }
        return if (index == 0) 1 else 2
    }

    private fun isTitleLike(line: String): Boolean {
        val words = line.split(' ').filter { it.isNotEmpty() }
        val letters = line.filter { it.isLetter() }
        val plausible = line.length <= MAX_HEADING_LENGTH && words.size <= MAX_HEADING_WORDS && letters.isNotEmpty() &&
            line.last() !in ".,;!?" && !URL.containsMatchIn(line)
        if (!plausible) return false
        if (SECTION_NUMBER.containsMatchIn(line)) return true
        if (letters.length >= MIN_CAPS_LETTERS && letters.none { it.isLowerCase() }) return true
        val significant = words.filter { word -> word.count { it.isLetter() } >= SIGNIFICANT_WORD }
        return line.first().isUpperCase() && significant.isNotEmpty() &&
            significant.all { word -> word.first { it.isLetter() }.isUpperCase() }
    }

    private fun joinWrapped(lines: List<String>): String = lines.reduce { acc, line ->
        val hyphenated = acc.length >= 2 && acc.last() == '-' && acc[acc.length - 2].isLetter()
        if (hyphenated && line.first().isLowerCase()) acc.dropLast(1) + line else "$acc $line"
    }

    /** Escapes Markdown characters and turns bare web addresses into links. */
    private fun inline(text: String): String {
        val out = StringBuilder()
        var last = 0
        for (match in URL.findAll(text)) {
            val url = trimUrl(match.value)
            out.append(MarkdownText.escapeInline(text.substring(last, match.range.first)))
            out.append(
                if (url.startsWith("www.", ignoreCase = true)) {
                    MarkdownText.link(MarkdownText.escapeInline(url), "https://$url")
                } else {
                    "<$url>"
                }
            )
            last = match.range.first + url.length
        }
        out.append(MarkdownText.escapeInline(text.substring(last)))
        return out.toString()
    }

    /** Drops trailing punctuation from a matched address, keeping closing brackets that are balanced. */
    private fun trimUrl(url: String): String {
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            val unbalanced = c == ')' && url.take(end).count { it == '(' } < url.take(end).count { it == ')' }
            if (c in ".,;:!?'\"" || unbalanced) end-- else break
        }
        return url.take(end)
    }

    private fun expandTabs(line: String): String {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        return indent.replace("\t", "    ") + line.substring(indent.length)
    }

    private const val LIST_INDENT = "    "
    private const val MAX_LIST_LEVEL = 5
    private const val MAX_HEADING = 6
    private const val MAX_HEADING_LENGTH = 70
    private const val MAX_HEADING_WORDS = 10
    private const val MIN_CAPS_LETTERS = 3
    private const val SIGNIFICANT_WORD = 4
    private const val MIN_SETEXT = 3
    private const val MIN_WRAPPED_LINES = 3
    private const val MIN_WRAP_WIDTH = 30
    private const val MAX_WRAP_WIDTH = 140
    private const val WRAP_LENGTH_RATIO = 0.6
    private const val WRAP_SHARE = 0.75

    private val BULLET = Regex("^( *)([-*+•·◦▪▫‣⁃●○■□►▶✓✔–—])\\s+(\\S.*)$")
    private val ORDERED = Regex("^( *)(\\d{1,3})[.)]\\s+(\\S.*)$")
    private val TASK = Regex("^( *)(?:[-*•]\\s+)?([☐☑☒✅]|\\[[ xX]])\\s+(\\S.*)$")
    private val SECTION_NUMBER = Regex("^(\\d+(?:\\.\\d+)+)\\.?\\s+\\S")
    private val URL = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\"]+")
    private val MARKDOWN_SIGNALS = listOf(
        Regex("(?m)^#{1,6} \\S"),
        Regex("(?m)^(```|~~~)"),
        Regex("\\[[^\\]\\n]+]\\([^)\\s]+\\)"),
        Regex("\\*\\*[^*\\n]+\\*\\*"),
        Regex("(?m)^\\s*[-*] \\[[ xX]] "),
        Regex("(?m)^\\|?\\s*:?-{3,}:?\\s*\\|")
    )
}
