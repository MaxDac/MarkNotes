package io.github.maxdac.marknotes.core.notes

import kotlin.random.Random

/** A note with its stable id, as listed in the `@` link popup. */
data class IndexedNote(val id: String, val path: String) {
    val title: String get() = NotePaths.displayName(path)

    /** Full path without the ".md" extension, e.g. "Work/Projects/Roadmap". */
    val displayPath: String get() = NotePaths.join(NotePaths.parent(path), title)
}

/** Every note of the tree with its id; ids resolve to the first note by sorted path. */
class NoteIndex(notes: List<IndexedNote>) {
    val notes: List<IndexedNote> = notes.sortedBy { it.path }
    private val byId: Map<String, IndexedNote> = this.notes.reversed().associateBy { it.id.lowercase() }

    fun resolve(id: String): IndexedNote? = byId[id.trim().lowercase()]

    /**
     * Notes whose id starts with [query] or whose title/path contains it (case-insensitive).
     * Id matches come first, then the rest ordered by path.
     */
    fun search(query: String): List<IndexedNote> {
        val q = query.lowercase()
        val sorted = notes.sortedBy { it.displayPath.lowercase() }
        if (q.isEmpty()) return sorted
        val idMatches = sorted.filter { it.id.lowercase().startsWith(q) }
        val textMatches = sorted.filter {
            it !in idMatches && (it.title.lowercase().contains(q) || it.displayPath.lowercase().contains(q))
        }
        return idMatches + textMatches
    }

    /** Result of [build]: the index plus the notes whose front-matter was rewritten. */
    class Build(val index: NoteIndex, val rewritten: Set<String>)

    companion object {
        /**
         * Reads every note of [store], giving an id to notes without one and a new id to the later
         * (by sorted path) of two notes sharing the same id. Changed notes are written back to disk.
         * With [assignIds] false nothing is written and notes without an id are left out.
         */
        fun build(store: LocalNoteStore, random: Random? = null, assignIds: Boolean = true): Build {
            val seen = HashSet<String>()
            val rewritten = LinkedHashSet<String>()
            val entries = store.listAll()
                .filter { !it.isFolder && NotePaths.isNote(it.path) && !Attachments.isInFolder(it.path) }
                .map { it.path }
                .sorted()
            val indexed = entries.mapNotNull { path ->
                val doc = runCatching { FrontMatter.parse(store.read(path)) }.getOrNull() ?: return@mapNotNull null
                var id = doc.id
                if (id == null && !assignIds) return@mapNotNull null
                if (id == null || (assignIds && id.lowercase() in seen)) {
                    id = generateUnique(seen, random)
                    store.write(path, FrontMatter.withId(doc, id).text)
                    rewritten += path
                }
                seen += id.lowercase()
                IndexedNote(id, path)
            }
            return Build(NoteIndex(indexed), rewritten)
        }

        private fun generateUnique(taken: Set<String>, random: Random?): String {
            while (true) {
                val id = if (random != null) NoteIds.generate(random) else NoteIds.generate()
                if (id !in taken) return id
            }
        }
    }
}

/** Links between notes: `[Title](marknotes:<id>)`. */
object NoteLinks {
    const val SCHEME = "marknotes:"

    fun markdown(title: String, id: String): String = "[${escapeLabel(title)}]($SCHEME$id)"

    /** The note id referenced by a link destination, or null when it isn't a note link. */
    fun idOf(destination: String): String? = destination.trim()
        .takeIf { it.startsWith(SCHEME, ignoreCase = true) }
        ?.substring(SCHEME.length)
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

    internal fun escapeLabel(label: String): String =
        label.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
}
