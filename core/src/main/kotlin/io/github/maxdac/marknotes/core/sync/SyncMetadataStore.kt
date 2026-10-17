package io.github.maxdac.marknotes.core.sync

/** What we knew about a path the last time it was in sync on both sides. */
data class SyncRecord(
    val path: String,
    val isFolder: Boolean,
    val remoteId: String,
    val remoteVersion: String,
    val localHash: String,
    val syncedAt: Long,
)

interface SyncMetadataStore {
    suspend fun all(): List<SyncRecord>
    suspend fun upsert(record: SyncRecord)
    suspend fun delete(path: String)
    suspend fun clear()
}

class InMemorySyncMetadataStore : SyncMetadataStore {
    private val records = linkedMapOf<String, SyncRecord>()

    override suspend fun all(): List<SyncRecord> = synchronized(records) { records.values.toList() }

    override suspend fun upsert(record: SyncRecord) {
        synchronized(records) { records[record.path] = record }
    }

    override suspend fun delete(path: String) {
        synchronized(records) { records.remove(path) }
    }

    override suspend fun clear() {
        synchronized(records) { records.clear() }
    }
}
