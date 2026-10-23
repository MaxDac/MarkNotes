package io.github.maxdac.marknotes.ui.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.maxdac.marknotes.core.importing.ImportException
import io.github.maxdac.marknotes.core.importing.ImportSource
import io.github.maxdac.marknotes.ui.editor.ImageImporter
import io.github.maxdac.marknotes.ui.editor.ImportedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Shared content read from its URIs: the document or text to convert, plus any shared images. */
class PreparedImport(
    val source: ImportSource?,
    val fileName: String?,
    val subject: String?,
    val images: List<ImportedImage>,
    val skippedImages: Int
)

/** Reads [SharedContent] (text extras and `content://` streams) into a [PreparedImport]. Call off the main thread. */
class SharedContentReader(private val context: Context, private val imageImporter: ImageImporter) {

    fun read(content: SharedContent): PreparedImport {
        val files = content.streams.map { SharedFile(it, mimeOf(it, content), displayName(it)) }
        val (imageFiles, documents) = files.partition { it.kind == Kind.IMAGE }
        if (documents.size > 1) throw ImportException("Share one document at a time.")
        val images = mutableListOf<ImportedImage>()
        var skipped = 0
        imageFiles.forEachIndexed { i, file ->
            try {
                images += imageImporter.import(file.uri, file.name ?: "image ${i + 1}", file.mime)
            } catch (e: IOException) {
                skipped++
            } catch (e: SecurityException) {
                skipped++
            }
        }
        val document = documents.singleOrNull()
        val source = when {
            document != null -> readDocument(document)
            content.html != null -> ImportSource.Html(content.html, content.text)
            content.text != null -> ImportSource.Text(content.text)
            else -> null
        }
        if (source == null && images.isEmpty()) {
            throw ImportException(if (skipped > 0) "The shared images could not be read." else "Nothing to import.")
        }
        return PreparedImport(source, document?.name, content.subject, images, skipped)
    }

    private fun readDocument(file: SharedFile): ImportSource = when (file.kind) {
        Kind.PDF -> ImportSource.Text(pdfText(readBytes(file.uri, MAX_DOCUMENT_BYTES)), reflow = true)

        Kind.DOCX -> ImportSource.Docx(readBytes(file.uri, MAX_DOCUMENT_BYTES))

        Kind.HTML -> ImportSource.Html(decode(readBytes(file.uri, MAX_TEXT_BYTES)))

        Kind.MARKDOWN -> ImportSource.Markdown(decode(readBytes(file.uri, MAX_TEXT_BYTES)))

        Kind.TEXT -> ImportSource.Text(decode(readBytes(file.uri, MAX_TEXT_BYTES)))

        Kind.IMAGE, Kind.UNSUPPORTED -> throw ImportException(
            "MarkNotes can import text, HTML, Markdown, Word (.docx) and PDF files."
        )
    }

    private fun pdfText(bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val text = stripText(bytes)
        if (text.isBlank()) throw ImportException("This PDF has no text to import (it may be a scanned image).")
        return text
    }

    private fun stripText(bytes: ByteArray): String = try {
        PDDocument.load(bytes).use { document ->
            PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
                paragraphEnd = "\n"
            }.getText(document)
        }
    } catch (e: InvalidPasswordException) {
        throw ImportException("This PDF is password protected.", e)
    } catch (e: IOException) {
        throw ImportException("Could not read the PDF: ${e.message}", e)
    }

    private fun readBytes(uri: Uri, max: Long): ByteArray {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw ImportException("MarkNotes isn't allowed to read the shared file.", e)
        } ?: throw ImportException("Could not open the shared file.")
        return input.use { readLimited(it, max) }
    }

    private fun readLimited(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > max) {
                throw ImportException("This file is too large to import (the limit is ${max / BYTES_PER_MB} MB).")
            }
        }
        return out.toByteArray()
    }

    private fun mimeOf(uri: Uri, content: SharedContent): String? {
        val resolved = try {
            context.contentResolver.getType(uri)
        } catch (e: SecurityException) {
            null
        }
        return resolved ?: content.mimeType?.takeUnless { it.endsWith("/*") }
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    private class SharedFile(val uri: Uri, val mime: String?, val name: String?) {
        val kind: Kind = kindOf(mime?.lowercase()?.substringBefore(';')?.trim(), name)
    }

    internal enum class Kind { IMAGE, PDF, DOCX, HTML, MARKDOWN, TEXT, UNSUPPORTED }

    internal companion object {
        const val MAX_TEXT_BYTES = 5L * 1024 * 1024
        const val MAX_DOCUMENT_BYTES = 50L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private const val BYTES_PER_MB = 1024 * 1024
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

        /** Classifies a shared file by its MIME type, falling back to the file name's extension. */
        @Suppress("CyclomaticComplexMethod")
        fun kindOf(mime: String?, name: String?): Kind {
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return when {
                mime == "application/pdf" || ext == "pdf" -> Kind.PDF

                mime == DOCX_MIME || ext == "docx" -> Kind.DOCX

                mime?.startsWith("image/") == true || ext in IMAGE_EXTENSIONS -> Kind.IMAGE

                mime == "text/html" || mime == "application/xhtml+xml" || ext == "html" || ext == "htm" -> Kind.HTML

                mime == "text/markdown" || mime == "text/x-markdown" || ext == "md" || ext == "markdown" ->
                    Kind.MARKDOWN

                mime?.startsWith("text/") == true || ext == "txt" -> Kind.TEXT

                else -> Kind.UNSUPPORTED
            }
        }

        /** Decodes text as UTF-8 (or UTF-16 with a byte order mark), falling back to Windows-1252. */
        fun decode(bytes: ByteArray): String {
            val utf16 = bytes.size >= 2 &&
                (
                    (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) ||
                        (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
                    )
            if (utf16) return String(bytes, Charsets.UTF_16)
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1252"))
            }
            return text.removePrefix("\uFEFF")
        }
    }
}
