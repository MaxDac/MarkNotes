package io.github.maxdac.marknotes.core.notes

import io.github.maxdac.marknotes.core.sync.Hashing

/**
 * Images embedded in notes. They live in the root [FOLDER], named after the SHA-256 of their bytes,
 * and are referenced from notes with paths relative to the notes root (see spec/note-format.md).
 */
object Attachments {
    const val FOLDER = "attachments"
    const val MAX_BYTES = 10L * 1024 * 1024
    const val MAX_DIMENSION = 2048

    private val extensionsByMime = linkedMapOf(
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/gif" to "gif",
        "image/webp" to "webp"
    )
    private val mimeByExtension = extensionsByMime.entries.associate { (mime, ext) -> ext to mime }
    private val referenceRegex = Regex("$FOLDER/[A-Za-z0-9._-]+")

    /** File extension for an accepted image MIME type, or null when the type isn't supported. */
    fun extensionFor(mime: String?): String? = mime?.lowercase()?.substringBefore(';')?.trim()?.let {
        extensionsByMime[it]
    }

    /** MIME type of an attachment path, or null for anything that isn't an attachment. */
    fun mimeFor(path: String): String? = mimeByExtension[path.substringAfterLast('.', "").lowercase()]

    /** True for paths inside [FOLDER] (at any depth). */
    fun isInFolder(path: String): Boolean = path == FOLDER || path.startsWith("$FOLDER/")

    /** True for an image file directly inside [FOLDER]: these are synced as immutable binaries. */
    fun isAttachment(path: String): Boolean = NotePaths.parent(path) == FOLDER && mimeFor(path) != null

    fun pathFor(bytes: ByteArray, extension: String): String = "$FOLDER/${Hashing.sha256Hex(bytes)}.$extension"

    /** True when [bytes] hash to the SHA-256 in the name of the attachment at [path]. */
    fun matchesName(path: String, bytes: ByteArray): Boolean =
        NotePaths.name(path).substringBeforeLast('.').equals(Hashing.sha256Hex(bytes), ignoreCase = true)

    /** Saves [bytes] (unless an identical attachment exists) and returns its path. */
    fun save(store: LocalNoteStore, bytes: ByteArray, extension: String): String {
        require(bytes.size <= MAX_BYTES) { "Images must be smaller than ${MAX_BYTES / (1024 * 1024)} MB" }
        require(mimeFor("x.$extension") != null) { "Unsupported image type .$extension" }
        val path = pathFor(bytes, extension)
        if (!store.exists(path)) store.writeBytes(path, bytes)
        return path
    }

    /** Markdown image for [path], using the original file name (without extension) as alt text. */
    fun markdown(fileName: String, path: String): String {
        val dot = fileName.lastIndexOf('.')
        val alt = if (dot > 0) fileName.substring(0, dot) else fileName
        return "![${NoteLinks.escapeLabel(alt)}]($path)"
    }

    /**
     * Size to downscale a [width] x [height] image to so its longest side is at most [MAX_DIMENSION],
     * or null when it already fits.
     */
    fun scaledSize(width: Int, height: Int): Pair<Int, Int>? {
        val longest = maxOf(width, height)
        if (longest <= MAX_DIMENSION) return null
        val scale = MAX_DIMENSION.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** Every `attachments/...` path mentioned in [text]. */
    fun references(text: String): Set<String> = referenceRegex.findAll(text).map { it.value }.toSet()

    /** Attachment files that no note references. */
    fun unused(store: LocalNoteStore): List<String> {
        val all = store.listAll()
        val referenced = all
            .filter { !it.isFolder && NotePaths.isNote(it.path) && !isInFolder(it.path) }
            .flatMap { references(store.read(it.path)) }
            .toSet()
        return all.filter { !it.isFolder && isAttachment(it.path) && it.path !in referenced }.map { it.path }
    }

    /** Deletes [unused] attachments and returns their paths. */
    fun deleteUnused(store: LocalNoteStore): List<String> = unused(store).onEach { store.delete(it) }
}
