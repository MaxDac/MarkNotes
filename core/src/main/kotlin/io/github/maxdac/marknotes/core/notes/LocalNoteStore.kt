package io.github.maxdac.marknotes.core.notes

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class NoteEntry(
    val path: String,
    val isFolder: Boolean,
    val modifiedMillis: Long,
    val size: Long,
) {
    val name: String get() = NotePaths.name(path)
    val displayName: String get() = NotePaths.displayName(path)
}

/**
 * Stores notes as plain markdown files in a folder tree under [root].
 * All paths are relative to [root] and use "/" as separator; "" is the root folder.
 * Hidden entries (starting with ".") are ignored; they are used for temporary files.
 */
class LocalNoteStore(val root: File) {

    init {
        root.mkdirs()
    }

    fun file(path: String): File {
        val normalized = NotePaths.normalize(path)
        return if (normalized.isEmpty()) root else File(root, normalized)
    }

    fun exists(path: String): Boolean = file(path).exists()

    fun isFolder(path: String): Boolean = file(path).isDirectory

    /** Lists the direct children of [folder]: folders first, then notes, alphabetically. */
    fun list(folder: String = ""): List<NoteEntry> {
        val dir = file(folder)
        val children = dir.listFiles() ?: return emptyList()
        val base = NotePaths.normalize(folder)
        return children
            .filter { !it.name.startsWith(".") }
            .map { it.toEntry(NotePaths.join(base, it.name)) }
            .sortedWith(compareBy<NoteEntry>({ !it.isFolder }, { it.name.lowercase() }))
    }

    /** Lists every folder and file below the root, depth first. */
    fun listAll(): List<NoteEntry> {
        val result = mutableListOf<NoteEntry>()
        fun walk(dir: File, base: String) {
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name }) {
                if (child.name.startsWith(".")) continue
                val path = NotePaths.join(base, child.name)
                result += child.toEntry(path)
                if (child.isDirectory) walk(child, path)
            }
        }
        walk(root, "")
        return result
    }

    fun read(path: String): String = readBytes(path).toString(Charsets.UTF_8)

    fun readBytes(path: String): ByteArray = file(path).readBytes()

    fun write(path: String, content: String) = writeBytes(path, content.toByteArray(Charsets.UTF_8))

    /** Writes atomically (temp file + move), creating parent folders as needed. */
    fun writeBytes(path: String, bytes: ByteArray) {
        val target = file(path)
        require(target != root) { "Cannot write to the root folder" }
        val parent = target.parentFile ?: throw IOException("Invalid path: $path")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create folder for $path")
        val temp = File(parent, ".${target.name}.tmp")
        temp.writeBytes(bytes)
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun createFolder(path: String): String {
        val normalized = NotePaths.normalize(path)
        val dir = file(normalized)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create folder $path")
        return normalized
    }

    /** Creates an empty note named [name] (".md" is appended if missing) and returns its path. */
    fun createNote(folder: String, name: String): String {
        val trimmed = name.trim()
        NotePaths.validateName(trimmed)
        val fileName = if (NotePaths.isNote(trimmed)) trimmed else trimmed + NotePaths.NOTE_EXTENSION
        val path = NotePaths.join(NotePaths.normalize(folder), fileName)
        if (exists(path)) throw IOException("\"$fileName\" already exists")
        write(path, "")
        return path
    }

    /** Creates a folder named [name] inside [folder] and returns its path. */
    fun createFolder(folder: String, name: String): String {
        val trimmed = name.trim()
        NotePaths.validateName(trimmed)
        val path = NotePaths.join(NotePaths.normalize(folder), trimmed)
        if (exists(path)) throw IOException("\"$trimmed\" already exists")
        return createFolder(path)
    }

    /** Renames an entry within its folder. Notes keep the ".md" extension. Returns the new path. */
    fun rename(path: String, newName: String): String {
        val source = file(path)
        if (!source.exists()) throw IOException("$path does not exist")
        val trimmed = newName.trim()
        NotePaths.validateName(trimmed)
        val finalName = if (source.isFile && !NotePaths.isNote(trimmed)) {
            trimmed + NotePaths.NOTE_EXTENSION
        } else {
            trimmed
        }
        val newPath = NotePaths.join(NotePaths.parent(NotePaths.normalize(path)), finalName)
        if (newPath == NotePaths.normalize(path)) return newPath
        val target = file(newPath)
        if (target.exists()) throw IOException("\"$finalName\" already exists")
        Files.move(source.toPath(), target.toPath())
        return newPath
    }

    /** Deletes a note or a folder (recursively). */
    fun delete(path: String) {
        val target = file(path)
        require(target != root) { "Cannot delete the root folder" }
        if (target.exists() && !target.deleteRecursively()) throw IOException("Cannot delete $path")
    }

    /**
     * Returns a free sibling path for [path] by appending [suffix] (and a counter if needed)
     * before the extension, e.g. "a/b.md" -> "a/b (conflicted copy).md".
     */
    fun uniqueSibling(path: String, suffix: String): String {
        val parent = NotePaths.parent(path)
        val name = NotePaths.name(path)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = NotePaths.join(parent, "$base$suffix$ext")
        var counter = 2
        while (exists(candidate)) {
            candidate = NotePaths.join(parent, "$base$suffix $counter$ext")
            counter++
        }
        return candidate
    }

    private fun File.toEntry(path: String) = NoteEntry(
        path = path,
        isFolder = isDirectory,
        modifiedMillis = lastModified(),
        size = if (isFile) length() else 0L,
    )
}
