package io.github.maxdac.marknotes.core.importing

/** Small helpers to emit Markdown from imported content. */
internal object MarkdownText {
    /** Hard line break inside a paragraph (CommonMark backslash form, visible and not trimmed by editors). */
    const val HARD_BREAK = "\\\n"

    private val headingStart = Regex("^(#{1,6})(\\s|$)")
    private val orderedStart = Regex("^(\\d{1,9})([.)])(\\s|$)")
    private val bulletStart = Regex("^([-+])(\\s|$)")
    private val thematicOrSetext = Regex("^([-=_])\\1*\\s*$")
    private val blankLines = Regex("\n{3,}")

    /** Escapes characters that would otherwise start inline Markdown constructs. */
    fun escapeInline(text: String): String {
        val out = StringBuilder(text.length + 8)
        for (i in text.indices) {
            val c = text[i]
            when (c) {
                '\\', '`', '*', '[', ']', '<' -> out.append('\\').append(c)

                '_' -> {
                    // Intraword underscores (snake_case) can't start emphasis, so they are left alone.
                    val prev = text.getOrNull(i - 1)
                    val next = text.getOrNull(i + 1)
                    if (prev?.isLetterOrDigit() == true && next?.isLetterOrDigit() == true) {
                        out.append(c)
                    } else {
                        out.append("\\_")
                    }
                }

                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Escapes a line start that would otherwise be read as a heading, quote, list item or rule. */
    fun escapeLineStart(line: String): String = when {
        line.startsWith(">") -> "\\$line"

        headingStart.containsMatchIn(line) -> "\\$line"

        bulletStart.containsMatchIn(line) -> "\\$line"

        thematicOrSetext.matches(line) -> "\\$line"

        else -> orderedStart.find(line)?.let { m ->
            val number = m.groupValues[1]
            number + "\\" + line.substring(number.length)
        } ?: line
    }

    /** Wraps [text] in an emphasis [marker], keeping surrounding whitespace outside the markers. */
    fun wrap(text: String, marker: String): String {
        if (text.isBlank()) return text
        val lead = text.takeWhile { it.isWhitespace() }
        val trail = text.takeLastWhile { it.isWhitespace() }
        val core = text.substring(lead.length, text.length - trail.length)
        return lead + marker + core + marker + trail
    }

    /** Inline code span for [code], using a backtick run longer than any inside it. */
    fun codeSpan(code: String): String {
        val text = code.replace('\n', ' ')
        if (text.isBlank()) return text
        val ticks = "`".repeat(longestRun(text, '`') + 1)
        val pad = if (text.startsWith('`') || text.endsWith('`')) " " else ""
        return "$ticks$pad$text$pad$ticks"
    }

    /** Fenced code block for [code], with an optional [language] info string. */
    fun fence(code: String, language: String = ""): String {
        val fence = "`".repeat(maxOf(3, longestRun(code, '`') + 1))
        return "$fence$language\n${code.trimEnd('\n')}\n$fence"
    }

    /** Link destination usable in `[text](url)`: spaces and parentheses are percent-encoded. */
    fun destination(url: String): String = url.trim()
        .replace(" ", "%20")
        .replace("(", "%28")
        .replace(")", "%29")

    fun link(text: String, url: String): String {
        val label = text.trim().ifEmpty { escapeInline(url) }
        return "[$label](${destination(url)})"
    }

    /** A GitHub-flavoured table; the first row is the header. Cells must already be inline Markdown. */
    fun table(rows: List<List<String>>): String {
        val columns = rows.maxOf { it.size }
        fun cell(text: String) = text.replace(HARD_BREAK, " ").replace("\n", " ").replace("|", "\\|").trim()
        fun line(row: List<String>) =
            (0 until columns).joinToString(" | ", "| ", " |") { cell(row.getOrElse(it) { "" }) }
        val separator = (0 until columns).joinToString(" | ", "| ", " |") { "---" }
        return (listOf(line(rows.first()), separator) + rows.drop(1).map(::line)).joinToString("\n")
    }

    /** Prefixes every non-empty line after the first with [indent] (list item continuation). */
    fun indentContinuation(text: String, indent: String): String = text.lines().mapIndexed { i, line ->
        if (i == 0 || line.isEmpty()) line else indent + line
    }.joinToString("\n")

    /** Final clean-up: no trailing spaces, at most one blank line in a row, single trailing newline. */
    fun tidy(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().map { it.trimEnd() }
        val joined = lines.joinToString("\n").replace(blankLines, "\n\n").trim('\n')
        return if (joined.isEmpty()) "" else joined + "\n"
    }

    private fun longestRun(text: String, c: Char): Int {
        var best = 0
        var current = 0
        for (ch in text) {
            current = if (ch == c) current + 1 else 0
            best = maxOf(best, current)
        }
        return best
    }
}

/** Markdown blocks being assembled: list items are kept tight, other blocks are separated by a blank line. */
internal class BlockBuilder {
    private val out = StringBuilder()
    private var lastWasItem = false

    fun block(text: String) {
        if (text.isBlank()) return
        separate(blank = true)
        out.append(text.trimEnd())
        lastWasItem = false
    }

    fun item(text: String) {
        if (text.isBlank()) return
        separate(blank = !lastWasItem)
        out.append(text.trimEnd())
        lastWasItem = true
    }

    private fun separate(blank: Boolean) {
        if (out.isEmpty()) return
        out.append(if (blank) "\n\n" else "\n")
    }

    override fun toString(): String = out.toString()
}
