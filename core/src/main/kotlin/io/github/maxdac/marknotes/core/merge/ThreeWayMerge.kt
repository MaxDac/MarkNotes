package io.github.maxdac.marknotes.core.merge

/** A region of a three-way merge. */
sealed interface MergeChunk {
    /** Lines everyone agrees on, or that only one side changed (taken automatically). */
    data class Resolved(val lines: List<String>, val changed: Boolean = false) : MergeChunk {
        val text: String get() = lines.joinToString("")
    }

    /** Both sides changed the same region differently. */
    data class Conflict(val base: List<String>, val local: List<String>, val remote: List<String>) : MergeChunk {
        val baseText: String get() = base.joinToString("")
        val localText: String get() = local.joinToString("")
        val remoteText: String get() = remote.joinToString("")
    }
}

/** How a single conflicting region is resolved. */
enum class ConflictChoice { LOCAL, REMOTE, BOTH, NEITHER }

data class MergeResult(val chunks: List<MergeChunk>) {
    val conflicts: List<MergeChunk.Conflict> get() = chunks.filterIsInstance<MergeChunk.Conflict>()
    val hasConflicts: Boolean get() = chunks.any { it is MergeChunk.Conflict }

    /**
     * Builds the merged text. [choices] is indexed by conflict (0 = first conflict); unresolved
     * conflicts are written with git-style markers.
     */
    fun text(choices: Map<Int, ConflictChoice> = emptyMap()): String = buildString {
        var conflictIndex = 0
        for (chunk in chunks) {
            when (chunk) {
                is MergeChunk.Resolved -> chunk.lines.forEach { append(it) }

                is MergeChunk.Conflict -> {
                    when (choices[conflictIndex]) {
                        ConflictChoice.LOCAL -> chunk.local.forEach { append(it) }

                        ConflictChoice.REMOTE -> chunk.remote.forEach { append(it) }

                        ConflictChoice.BOTH -> {
                            appendLines(chunk.local)
                            chunk.remote.forEach { append(it) }
                        }

                        ConflictChoice.NEITHER -> Unit

                        null -> {
                            append(MARKER_LOCAL).append('\n')
                            appendLines(chunk.local)
                            append(MARKER_SEPARATOR).append('\n')
                            appendLines(chunk.remote)
                            append(MARKER_REMOTE).append('\n')
                        }
                    }
                    conflictIndex++
                }
            }
        }
    }

    /** Appends lines making sure the last one ends with a newline, so that following text starts on a new line. */
    private fun StringBuilder.appendLines(lines: List<String>) {
        lines.forEach { append(it) }
        if (lines.isNotEmpty() && !lines.last().endsWith("\n")) append('\n')
    }

    companion object {
        const val MARKER_LOCAL = "<<<<<<< this device"
        const val MARKER_SEPARATOR = "======="
        const val MARKER_REMOTE = ">>>>>>> drive"
    }
}

/**
 * Line-based three-way merge (diff3). Regions changed on only one side are merged automatically;
 * regions changed differently on both sides become [MergeChunk.Conflict]s.
 */
object ThreeWayMerge {

    /**
     * Merges [local] and [remote] against their common ancestor [base]. Without a base (the two
     * versions were never in sync) nothing can be merged safely: lines common to both are kept and
     * every difference is a conflict.
     */
    fun merge(base: String?, local: String, remote: String): MergeResult {
        val l = LineDiff.lines(local)
        val r = LineDiff.lines(remote)
        if (local == remote) return MergeResult(listOf(MergeChunk.Resolved(l)))
        val autoResolve = base != null
        val o = if (base != null) {
            LineDiff.lines(base)
        } else {
            LineDiff.matches(l, r).map { (i, _) -> l[i] }
        }
        return MergeResult(diff3(o, l, r, autoResolve))
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun diff3(o: List<String>, a: List<String>, b: List<String>, autoResolve: Boolean): List<MergeChunk> {
        val matchA = IntArray(o.size) { -1 }
        val matchB = IntArray(o.size) { -1 }
        LineDiff.matches(o, a).forEach { (i, j) -> matchA[i] = j }
        LineDiff.matches(o, b).forEach { (i, j) -> matchB[i] = j }

        val chunks = mutableListOf<MergeChunk>()
        fun addResolved(lines: List<String>, changed: Boolean) {
            if (lines.isEmpty()) return
            val last = chunks.lastOrNull()
            if (last is MergeChunk.Resolved && last.changed == changed) {
                chunks[chunks.size - 1] = MergeChunk.Resolved(last.lines + lines, changed)
            } else {
                chunks += MergeChunk.Resolved(lines, changed)
            }
        }

        fun addUnstable(oPart: List<String>, aPart: List<String>, bPart: List<String>) {
            when {
                aPart == bPart -> addResolved(aPart, changed = aPart != oPart)
                autoResolve && aPart == oPart -> addResolved(bPart, changed = true)
                autoResolve && bPart == oPart -> addResolved(aPart, changed = true)
                else -> chunks += MergeChunk.Conflict(oPart, aPart, bPart)
            }
        }

        var lo = 0
        var la = 0
        var lb = 0
        var done = false
        while (!done) {
            var i = 0
            while (lo + i < o.size && matchA[lo + i] == la + i && matchB[lo + i] == lb + i) i++
            if (i > 0) {
                addResolved(o.subList(lo, lo + i), changed = false)
                lo += i
                la += i
                lb += i
                continue
            }
            var next = lo
            while (next < o.size && (matchA[next] < 0 || matchB[next] < 0)) next++
            if (next >= o.size) {
                if (lo < o.size || la < a.size || lb < b.size) {
                    addUnstable(o.subList(lo, o.size), a.subList(la, a.size), b.subList(lb, b.size))
                }
                done = true
            } else {
                addUnstable(o.subList(lo, next), a.subList(la, matchA[next]), b.subList(lb, matchB[next]))
                lo = next
                la = matchA[next]
                lb = matchB[next]
            }
        }
        return chunks
    }
}
