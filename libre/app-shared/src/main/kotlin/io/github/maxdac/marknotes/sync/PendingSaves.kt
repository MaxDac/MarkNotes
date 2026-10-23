package io.github.maxdac.marknotes.sync

import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * Editors with unsaved edits register here so a sync first writes them to disk: a sync must never plan
 * against a file the editor is about to overwrite.
 */
class PendingSaves {
    private val savers = CopyOnWriteArraySet<suspend () -> Unit>()
    private val writes = CopyOnWriteArraySet<Job>()

    /** Registers [save], which writes the editor's pending edits now; call the returned function to remove it. */
    fun register(save: suspend () -> Unit): () -> Unit {
        savers += save
        return { savers -= save }
    }

    /** Tracks a background write started when an editor closed, so the next sync waits for it. */
    fun track(write: Job) {
        writes += write
        write.invokeOnCompletion { writes -= write }
    }

    suspend fun flushAll() {
        for (save in savers) {
            try {
                save()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The editor reports its own save errors; the sync re-checks every file it touches anyway.
            }
        }
        writes.forEach { it.join() }
    }
}
