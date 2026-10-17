package io.github.maxdac.marknotes.ui.editor

/** Editor text plus selection, independent of Compose so the editing logic is plain-JVM testable. */
data class EditorText(val text: String, val selectionStart: Int, val selectionEnd: Int = selectionStart) {
    val min: Int get() = minOf(selectionStart, selectionEnd)
    val max: Int get() = maxOf(selectionStart, selectionEnd)
    val collapsed: Boolean get() = selectionStart == selectionEnd
}

enum class LineKind { BULLET, NUMBERED, CHECKBOX, QUOTE }

/** A recognised block marker at the start of a line, e.g. `  - [ ] ` or `3. `. */
internal data class LineMarker(
    val indent: String,
    val kind: LineKind,
    val marker: String,
    val bullet: Char = '-',
    val number: Int = 0,
    val delimiter: Char = '.'
) {
    /** Length of indentation plus marker, i.e. where the line content starts. */
    val prefixLength: Int get() = indent.length + marker.length

    fun next(): String = when (kind) {
        LineKind.BULLET -> "$bullet "
        LineKind.CHECKBOX -> "$bullet [ ] "
        LineKind.NUMBERED -> "${number + 1}$delimiter "
        LineKind.QUOTE -> "> "
    }
}

/** Markdown formatting helpers used by the editor toolbar and list auto-continuation. */
object MarkdownEditing {
    private const val FENCE = "```"
    private const val MAX_HEADING_LEVEL = 3

    private val indentRegex = Regex("^[ \\t]*")
    private val checkboxRegex = Regex("^([-*+]) \\[([ xX])](?: |$)")
    private val bulletRegex = Regex("^([-*+]) ")
    private val numberedRegex = Regex("^(\\d{1,9})([.)]) ")
    private val quoteRegex = Regex("^> ?")
    private val headingRegex = Regex("^(#{1,6}) ")

    internal fun parseMarker(line: String): LineMarker? {
        val indent = indentRegex.find(line)!!.value
        val rest = line.substring(indent.length)
        checkboxRegex.find(rest)?.let {
            return LineMarker(indent, LineKind.CHECKBOX, it.value, bullet = it.groupValues[1][0])
        }
        bulletRegex.find(rest)?.let {
            return LineMarker(indent, LineKind.BULLET, it.value, bullet = it.groupValues[1][0])
        }
        numberedRegex.find(rest)?.let {
            return LineMarker(
                indent,
                LineKind.NUMBERED,
                it.value,
                number = it.groupValues[1].toInt(),
                delimiter = it.groupValues[2][0]
            )
        }
        quoteRegex.find(rest)?.let { return LineMarker(indent, LineKind.QUOTE, it.value) }
        return null
    }

    private fun lineStart(text: String, index: Int): Int =
        if (index <= 0) 0 else text.lastIndexOf('\n', index - 1) + 1

    private fun lineEnd(text: String, index: Int): Int {
        val newline = text.indexOf('\n', index)
        return if (newline < 0) text.length else newline
    }

    /** Wraps the selection in [marker] (e.g. `**`), or unwraps it when already wrapped. */
    fun toggleInline(value: EditorText, marker: String): EditorText {
        val text = value.text
        val s = value.min
        val e = value.max
        val m = marker.length
        val selected = text.substring(s, e)
        val wrappedOutside = s >= m && text.startsWith(marker, s - m) && text.startsWith(marker, e) &&
            !(selected.isEmpty() && isLongerRun(text, s - m, e + m, marker))
        if (wrappedOutside) {
            val newText = text.removeRange(e, e + m).removeRange(s - m, s)
            return EditorText(newText, s - m, e - m)
        }
        if (selected.length >= 2 * m && selected.startsWith(marker) && selected.endsWith(marker)) {
            val inner = selected.substring(m, selected.length - m)
            return EditorText(text.replaceRange(s, e, inner), s, s + inner.length)
        }
        val newText = text.substring(0, s) + marker + selected + marker + text.substring(e)
        return EditorText(newText, s + m, e + m)
    }

    // `**|**` sitting inside a longer run such as `****` should not be treated as a wrapped empty selection
    // of a shorter marker (e.g. italic `_` inside `__`), so only unwrap exact runs.
    private fun isLongerRun(text: String, from: Int, to: Int, marker: String): Boolean {
        val c = marker.last()
        return (from > 0 && text[from - 1] == c) || (to < text.length && text[to] == c)
    }

    /** Adds [kind]'s marker to every line in the selection, converting other markers; removes it if all have it. */
    fun toggleLinePrefix(value: EditorText, kind: LineKind): EditorText {
        val text = value.text
        val start = lineStart(text, value.min)
        val end = lineEnd(text, value.max)
        val lines = text.substring(start, end).split('\n')
        val relevant = lines.filter { it.isNotBlank() }.ifEmpty { lines }
        val remove = relevant.all { parseMarker(it)?.kind == kind }
        var number = 1
        val firstOldPrefix = parseMarker(lines.first())?.prefixLength
            ?: indentRegex.find(lines.first())!!.value.length
        var firstNewPrefix = 0
        val newLines = lines.mapIndexed { index, line ->
            val parsed = parseMarker(line)
            val indent = parsed?.indent ?: indentRegex.find(line)!!.value
            val content = line.substring(parsed?.prefixLength ?: indent.length)
            val newLine = when {
                remove -> indent + content
                line.isBlank() && lines.size > 1 -> line
                else -> indent + prefixFor(kind, number++) + content
            }
            if (index == 0) firstNewPrefix = newLine.length - content.length
            newLine
        }
        val block = newLines.joinToString("\n")
        val delta = block.length - (end - start)
        val newText = text.substring(0, start) + block + text.substring(end)
        val firstDelta = firstNewPrefix - firstOldPrefix
        val mappedStart = when {
            !value.collapsed && value.min == start -> start
            value.min - start < firstOldPrefix -> start + firstNewPrefix
            else -> value.min + firstDelta
        }
        val mappedEnd = if (value.collapsed) mappedStart else maxOf(mappedStart, value.max + delta)
        return EditorText(newText, mappedStart, mappedEnd)
    }

    private fun prefixFor(kind: LineKind, number: Int): String = when (kind) {
        LineKind.BULLET -> "- "
        LineKind.NUMBERED -> "$number. "
        LineKind.CHECKBOX -> "- [ ] "
        LineKind.QUOTE -> "> "
    }

    /** Cycles the current line through no heading → `#` → `##` → `###` → no heading. */
    fun cycleHeading(value: EditorText): EditorText {
        val text = value.text
        val start = lineStart(text, value.min)
        val end = lineEnd(text, start)
        val line = text.substring(start, end)
        val current = headingRegex.find(line)
        val level = current?.groupValues?.get(1)?.length ?: 0
        val oldPrefix = current?.value?.length ?: 0
        val nextLevel = if (level >= MAX_HEADING_LEVEL) 0 else level + 1
        val newPrefix = if (nextLevel == 0) "" else "#".repeat(nextLevel) + " "
        val newText = text.substring(0, start) + newPrefix + line.substring(oldPrefix) + text.substring(end)
        fun map(pos: Int): Int =
            if (pos - start < oldPrefix) start + newPrefix.length else pos + newPrefix.length - oldPrefix
        return EditorText(newText, map(value.selectionStart), map(value.selectionEnd))
    }

    /** Inserts `[selection](url)` with `url` selected, or `[]()` with the cursor in the brackets. */
    fun insertLink(value: EditorText): EditorText {
        val text = value.text
        val s = value.min
        val e = value.max
        val label = text.substring(s, e)
        if (label.isEmpty()) {
            return EditorText(text.substring(0, s) + "[]()" + text.substring(e), s + 1)
        }
        val placeholder = "url"
        val newText = text.substring(0, s) + "[$label]($placeholder)" + text.substring(e)
        val urlStart = s + label.length + 3
        return EditorText(newText, urlStart, urlStart + placeholder.length)
    }

    /**
     * Wraps the selected lines in a fenced code block (cursor after the opening fence to type a language),
     * inserts an empty block when nothing is selected, or removes the fences when the selection is a block.
     */
    fun toggleCodeBlock(value: EditorText): EditorText {
        val text = value.text
        if (value.collapsed) {
            val s = value.min
            val before = if (s > 0 && text[s - 1] != '\n') "\n" else ""
            val after = if (s < text.length && text[s] != '\n') "\n" else ""
            val insert = "$before$FENCE\n\n$FENCE$after"
            return EditorText(text.substring(0, s) + insert + text.substring(s), s + before.length + FENCE.length)
        }
        val start = lineStart(text, value.min)
        val end = lineEnd(text, value.max)
        val lines = text.substring(start, end).split('\n')
        if (lines.size >= 2 && lines.first().trimStart().startsWith(FENCE) && lines.last().trim() == FENCE) {
            val inner = lines.subList(1, lines.size - 1).joinToString("\n")
            return EditorText(text.substring(0, start) + inner + text.substring(end), start, start + inner.length)
        }
        val block = text.substring(start, end)
        val newText = text.substring(0, start) + "$FENCE\n$block\n$FENCE" + text.substring(end)
        return EditorText(newText, start + FENCE.length)
    }

    private fun insideCodeFence(text: String, lineStart: Int): Boolean =
        text.substring(0, lineStart).split('\n').count { it.trimStart().startsWith(FENCE) } % 2 == 1

    /**
     * When [new] is [old] with a single newline typed at the cursor on a list/checkbox/quote line, returns the
     * edit that continues the list (or ends it when the item was empty). Returns null when nothing applies.
     */
    fun continueList(old: EditorText, new: EditorText): EditorText? {
        if (!old.collapsed || !new.collapsed) return null
        val p = old.selectionStart
        val text = old.text
        if (new.text.length != text.length + 1 || new.selectionStart != p + 1) return null
        val typedNewline = new.text[p] == '\n' &&
            new.text.startsWith(text.substring(0, p)) &&
            new.text.endsWith(text.substring(p))
        if (!typedNewline) return null
        val start = lineStart(text, p)
        val end = lineEnd(text, p)
        val marker = parseMarker(text.substring(start, end)) ?: return null
        if (p < start + marker.prefixLength) return null
        if (insideCodeFence(text, start)) return null
        val content = text.substring(start + marker.prefixLength, end)
        if (content.isBlank()) {
            val newText = text.substring(0, start) + text.substring(end)
            return EditorText(newText, start)
        }
        val insert = "\n" + marker.indent + marker.next()
        return EditorText(text.substring(0, p) + insert + text.substring(p), p + insert.length)
    }
}
