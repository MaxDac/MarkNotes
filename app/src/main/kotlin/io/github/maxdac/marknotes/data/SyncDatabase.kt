package io.github.maxdac.marknotes.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import io.github.maxdac.marknotes.core.sync.SyncMetadataStore
import io.github.maxdac.marknotes.core.sync.SyncRecord

@Entity(tableName = "sync_records")
data class SyncRecordEntity(
    @PrimaryKey val path: String,
    val isFolder: Boolean,
    val remoteId: String,
    val remoteVersion: String,
    val localHash: String,
    val syncedAt: Long
)

@Dao
interface SyncRecordDao {
    @Query("SELECT * FROM sync_records")
    suspend fun all(): List<SyncRecordEntity>

    @Upsert
    suspend fun upsert(entity: SyncRecordEntity)

    @Query("DELETE FROM sync_records WHERE path = :path")
    suspend fun delete(path: String)

    @Query("DELETE FROM sync_records")
    suspend fun clear()
}

@Database(entities = [SyncRecordEntity::class], version = 1, exportSchema = false)
abstract class MarkNotesDatabase : RoomDatabase() {
    abstract fun syncRecords(): SyncRecordDao

    companion object {
        fun create(context: Context): MarkNotesDatabase =
            Room.databaseBuilder(context, MarkNotesDatabase::class.java, "marknotes.db").build()
    }
}

class RoomSyncMetadataStore(private val dao: SyncRecordDao) : SyncMetadataStore {
    override suspend fun all(): List<SyncRecord> = dao.all().map {
        SyncRecord(it.path, it.isFolder, it.remoteId, it.remoteVersion, it.localHash, it.syncedAt)
    }

    override suspend fun upsert(record: SyncRecord) = dao.upsert(
        SyncRecordEntity(
            record.path,
            record.isFolder,
            record.remoteId,
            record.remoteVersion,
            record.localHash,
            record.syncedAt
        )
    )

    override suspend fun delete(path: String) = dao.delete(path)

    override suspend fun clear() = dao.clear()
}
