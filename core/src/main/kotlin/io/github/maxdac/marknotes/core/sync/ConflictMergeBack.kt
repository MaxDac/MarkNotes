package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.sync.MergeResult
import io.github.maxdac.marknotes.sync.merge3
import io.github.maxdac.marknotes.sync.readId
import io.github.maxdac.marknotes.sync.withId

/**
 * Merges a conflicted copy back into its note. Everything happens in the local tree: the next sync uploads the
 * merged note and deletes the copy on every drive.
 */
class ConflictMergeBack(private val local: LocalNoteStore) {

    /** True while both the note and its conflicted copy still exist. */
    fun isPending(conflict: SyncConflict): Boolean =
        local.exists(conflict.path) && local.exists(conflict.copyPath)

    /**
     * Three-way merge of the base, the note ("local" side) and the copy ("remote" side). The copy's new id is
     * replaced by the note's id first, so that the merge keeps the note's identity.
     */
    fun merge(conflict: SyncConflict): MergeResult {
        val note = local.read(conflict.path)
        val copy = local.read(conflict.copyPath)
        val id = readId(note)
        val aligned = if (id != null && readId(copy) != null) withId(copy, id) else copy
        return merge3(conflict.baseText, note, aligned)
    }

    /** Saves [text] as the note and deletes the copy. */
    fun apply(conflict: SyncConflict, text: String) {
        local.write(conflict.path, text)
        discardCopy(conflict)
    }

    /** Deletes the conflicted copy, keeping the note as it is. */
    fun discardCopy(conflict: SyncConflict) {
        local.delete(conflict.copyPath)
    }
}
