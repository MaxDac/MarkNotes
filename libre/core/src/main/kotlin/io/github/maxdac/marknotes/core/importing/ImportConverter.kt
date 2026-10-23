package io.github.maxdac.marknotes.core.importing

import java.io.IOException

/** What was shared with the app, before conversion. */
sealed interface ImportSource {
    /** Plain text; [reflow] joins wrapped lines (text extracted from PDFs). */
    data class Text(val text: String, val reflow: Boolean = false) : ImportSource

    /** Rich text; [text] is the plain-text version some apps share alongside, used when keeping it as-is. */
    data class Html(val html: String, val text: String? = null) : ImportSource

    /** A Markdown file: kept as it is. */
    data class Markdown(val text: String) : ImportSource

    /** A Word document (`.docx`). */
    class Docx(val bytes: ByteArray) : ImportSource
}

/**
 * Stores an image found in an imported document and returns its attachment path (see
 * [io.github.maxdac.marknotes.core.notes.Attachments]), or null to leave the image out.
 */
fun interface ImageSink {
    fun save(bytes: ByteArray, extension: String, fileName: String): String?

    companion object {
        val NONE = ImageSink { _, _, _ -> null }
    }
}

/** A problem with the imported content, with a message meant for the user. */
class ImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Converts shared content to the body of a new note, either formatted as Markdown or kept as plain text. */
object ImportConverter {
    fun convert(source: ImportSource, autoFormat: Boolean, images: ImageSink = ImageSink.NONE): String =
        when (source) {
            is ImportSource.Text -> if (autoFormat) {
                PlainTextToMarkdown.convert(source.text, source.reflow)
            } else {
                MarkdownText.tidy(PlainTextToMarkdown.normalize(source.text))
            }

            is ImportSource.Html -> if (autoFormat) {
                HtmlToMarkdown.convert(source.html)
            } else {
                source.text?.takeIf { it.isNotBlank() }?.let { MarkdownText.tidy(PlainTextToMarkdown.normalize(it)) }
                    ?: HtmlToMarkdown.plainText(source.html)
            }

            is ImportSource.Markdown -> MarkdownText.tidy(PlainTextToMarkdown.normalize(source.text))

            is ImportSource.Docx -> if (autoFormat) {
                DocxToMarkdown.convert(source.bytes, images)
            } else {
                DocxToMarkdown.plainText(source.bytes)
            }
        }

    /**
     * A note name for imported content: the shared [subject], else the first heading of [body], else the
     * [fileName] without extension, else the first line of [body]; cleaned up so it is a valid, portable name.
     */
    fun suggestTitle(body: String, fileName: String? = null, subject: String? = null): String {
        val heading = HEADING.find(body)?.groupValues?.get(1)
        val firstLine = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("![") }
        val candidates = listOf(
            subject,
            heading?.let(::stripMarkdown),
            fileName?.let { name -> name.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: name },
            firstLine?.let(::stripMarkdown)?.let(::shorten)
        )
        return candidates.firstNotNullOfOrNull { it?.let(::sanitize)?.takeIf(String::isNotEmpty) } ?: DEFAULT_TITLE
    }

    /** Makes [name] a valid note name on every platform the notes sync to. */
    fun sanitize(name: String): String {
        val cleaned = name
            .replace(FORBIDDEN, " ")
            .replace(SPACES, " ")
            .trim()
            .trimStart('.')
            .trim()
        val withoutExtension = if (cleaned.endsWith(".md", ignoreCase = true)) cleaned.dropLast(3).trim() else cleaned
        return withoutExtension.take(MAX_TITLE).trimEnd('.', ' ')
    }

    private fun stripMarkdown(text: String): String = text
        .replace(LINK, "$1")
        .replace(AUTOLINK, "$1")
        .replace(Regex("^(#{1,6}|>|[-*+]|\\d+[.)])\\s+"), "")
        .replace(Regex("[*_`~]"), "")
        .replace("\\", "")

    private fun shorten(text: String): String {
        if (text.length <= MAX_FIRST_LINE) return text
        val cut = text.take(MAX_FIRST_LINE)
        val space = cut.lastIndexOf(' ')
        return if (space > MAX_FIRST_LINE / 2) cut.take(space) else cut
    }

    const val DEFAULT_TITLE = "Imported note"
    private const val MAX_TITLE = 80
    private const val MAX_FIRST_LINE = 60
    private val HEADING = Regex("(?m)^#{1,6}\\s+(.+)$")
    private val LINK = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
    private val AUTOLINK = Regex("<([^>]+)>")
    private val FORBIDDEN = Regex("[/\\\\:*?\"<>|\\p{Cntrl}]")
    private val SPACES = Regex("\\s+")
}
