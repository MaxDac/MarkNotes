package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import kotlinx.coroutines.CancellationException

/** Everything needed to sync the local note tree with one drive. */
class DriveSyncTarget(
    val driveId: String,
    val remote: RemoteDriveStore,
    val metadata: SyncMetadataStore,
    val base: BaseContentStore
)

/** Outcome of syncing one drive: the last [SyncResult], or the [error] that stopped it. */
data class DriveSyncOutcome(val driveId: String, val result: SyncResult?, val error: String?)

data class MultiDriveSyncResult(val outcomes: List<DriveSyncOutcome>) {
    val conflicts: List<SyncConflict> get() = outcomes.flatMap { it.result?.conflicts.orEmpty() }

    val localChanged: Boolean get() = outcomes.any { (it.result?.localChanges ?: 0) > 0 }
}

/**
 * Keeps the local note tree and every connected drive identical.
 *
 * Each drive is synced in turn with its own metadata and base copies. Whenever a drive brings changes
 * into the local tree, the other drives are synced again so they receive them, until nothing changes
 * (bounded by [maxPassesPerDrive]).
 */
class MultiDriveSync(
    private val local: LocalNoteStore,
    private val deviceId: String = "",
    private val maxPassesPerDrive: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun engine(target: DriveSyncTarget) =
        SyncEngine(local, target.remote, target.metadata, target.base, target.driveId, deviceId, clock)

    suspend fun sync(targets: List<DriveSyncTarget>): MultiDriveSyncResult {
        val outcomes = linkedMapOf<String, DriveSyncOutcome>()
        val passes = HashMap<String, Int>()
        val pending = ArrayDeque(targets)
        while (pending.isNotEmpty()) {
            val target = pending.removeFirst()
            val count = passes.getOrDefault(target.driveId, 0)
            if (count >= maxPassesPerDrive) continue
            passes[target.driveId] = count + 1
            val outcome = try {
                val result = engine(target).sync()
                DriveSyncOutcome(target.driveId, result, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DriveSyncOutcome(target.driveId, null, e.message ?: e.javaClass.simpleName)
            }
            outcomes[target.driveId] = outcome
            if ((outcome.result?.localChanges ?: 0) > 0) {
                targets.filter { it !== target && it !in pending }.forEach { pending.addLast(it) }
            }
        }
        return MultiDriveSyncResult(targets.mapNotNull { outcomes[it.driveId] })
    }
}
