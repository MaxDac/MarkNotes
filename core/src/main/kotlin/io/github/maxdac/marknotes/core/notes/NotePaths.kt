package io.github.maxdac.marknotes.core.notes

/**
 * Helpers for the relative, "/"-separated paths used throughout the app.
 * The root folder is the empty string.
 */
object NotePaths {
    const val NOTE_EXTENSION = ".md"

    fun join(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"

    fun parent(path: String): String {
        val index = path.lastIndexOf('/')
        return if (index < 0) "" else path.substring(0, index)
    }

    fun name(path: String): String = path.substringAfterLast('/')

    fun depth(path: String): Int = if (path.isEmpty()) 0 else path.count { it == '/' } + 1

    fun isAncestor(ancestor: String, path: String): Boolean =
        ancestor.isEmpty() || path.startsWith("$ancestor/")

    fun isNote(path: String): Boolean = path.endsWith(NOTE_EXTENSION, ignoreCase = true)

    fun displayName(path: String): String {
        val name = name(path)
        return if (isNote(name)) name.dropLast(NOTE_EXTENSION.length) else name
    }

    /** Validates a single file or folder name (no separators, no dot-only names). */
    fun validateName(name: String) {
        require(name.isNotBlank()) { "Name cannot be empty" }
        require(name.trim() == name) { "Name cannot start or end with spaces" }
        require('/' !in name && '\\' !in name) { "Name cannot contain / or \\" }
        require(name != "." && name != "..") { "Invalid name" }
        require(!name.startsWith(".")) { "Name cannot start with a dot" }
    }

    /** Normalizes and validates a relative path. */
    fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        parts.forEach { validateName(it) }
        return parts.joinToString("/")
    }
}
