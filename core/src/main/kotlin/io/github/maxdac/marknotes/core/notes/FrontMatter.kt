package io.github.maxdac.marknotes.core.notes

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/** Stable note identifiers stored in the note's front-matter (see spec/note-format.md). */
object NoteIds {
    const val LENGTH = 8

    /** Crockford base32, lowercase, without i, l, o and u. */
    const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

    private val defaultRandom: Random = SecureRandom().asKotlinRandom()

    fun generate(random: Random = defaultRandom): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun isValid(id: String): Boolean = id.length == LENGTH && id.all { it in ALPHABET }
}

/**
 * A note split into its YAML front-matter block and the markdown body the user edits.
 *
 * @param header the whole front-matter block including both `---` lines and the trailing line break,
 *   or "" when the note has none.
 */
data class NoteDocument(val header: String, val body: String) {
    val id: String? get() = FrontMatter.idOf(header)

    val text: String get() = header + body
}

/**
 * Minimal YAML front-matter handling: only the `id` key is interpreted, every other line of the block
 * is preserved verbatim.
 */
object FrontMatter {
    private const val DELIMITER = "---"
    private val idLine = Regex("^id:[ \\t]*(.*?)[ \\t]*$")

    fun parse(text: String): NoteDocument {
        val firstEnd = lineEnd(text, 0) ?: return NoteDocument("", text)
        if (text.substring(0, firstEnd.contentEnd) != DELIMITER) return NoteDocument("", text)
        var start = firstEnd.next
        while (start < text.length) {
            val end = lineEnd(text, start) ?: LineEnd(text.length, text.length)
            if (text.substring(start, end.contentEnd).trimEnd() == DELIMITER) {
                return NoteDocument(text.substring(0, end.next), text.substring(end.next))
            }
            start = end.next
        }
        return NoteDocument("", text)
    }

    /** Value of the `id` key in a front-matter [header], or null when missing or empty. */
    fun idOf(header: String): String? = headerLines(header)
        .firstNotNullOfOrNull { idLine.find(it)?.groupValues?.get(1) }
        ?.removeSurrounding("\"")
        ?.removeSurrounding("'")
        ?.takeIf { it.isNotEmpty() }

    /** Returns [text] with its front-matter `id` set to [id], adding the key or the whole block as needed. */
    fun withId(text: String, id: String): String {
        val doc = parse(text)
        return withId(doc, id).text
    }

    fun withId(doc: NoteDocument, id: String): NoteDocument {
        if (doc.header.isEmpty()) return NoteDocument("$DELIMITER\nid: $id\n$DELIMITER\n", doc.body)
        val newline = if (doc.header.startsWith("$DELIMITER\r\n")) "\r\n" else "\n"
        val lines = headerLines(doc.header)
        val index = lines.indexOfFirst { idLine.matches(it) }
        val updated = if (index >= 0) {
            lines.toMutableList().also { it[index] = "id: $id" }
        } else {
            listOf("id: $id") + lines
        }
        val header = (listOf(DELIMITER) + updated + DELIMITER).joinToString(newline) + newline
        return NoteDocument(header, doc.body)
    }

    /** The key lines between the two delimiters. */
    private fun headerLines(header: String): List<String> {
        if (header.isEmpty()) return emptyList()
        val lines = header.removeSuffix("\n").removeSuffix("\r").split('\n').map { it.removeSuffix("\r") }
        return lines.subList(1, lines.size - 1)
    }

    private class LineEnd(val contentEnd: Int, val next: Int)

    private fun lineEnd(text: String, start: Int): LineEnd? {
        val newline = text.indexOf('\n', start)
        if (newline < 0) return null
        val contentEnd = if (newline > start && text[newline - 1] == '\r') newline - 1 else newline
        return LineEnd(contentEnd, newline + 1)
    }
}
