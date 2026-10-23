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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.SyncMetadataStore
import io.github.maxdac.marknotes.core.sync.SyncRecord
import kotlinx.coroutines.flow.Flow

/** Sync state of one path on one drive. */
@Entity(tableName = "sync_records", primaryKeys = ["driveId", "path"])
data class SyncRecordEntity(
    val driveId: String,
    val path: String,
    val isFolder: Boolean,
    val remoteId: String,
    val remoteVersion: String,
    val localHash: String,
    val syncedAt: Long
)

@Entity(tableName = "drive_accounts")
data class DriveAccountEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val label: String,
    val remoteFolder: String,
    val createRootFolder: Boolean,
    val rootFolderName: String,
    val lastSyncAt: Long,
    val lastMessage: String?
) {
    fun toAccount(): DriveAccount? {
        val provider = DriveProvider.entries.firstOrNull { it.name == provider } ?: return null
        return DriveAccount(id, provider, label, remoteFolder, createRootFolder, rootFolderName)
    }

    companion object {
        fun from(account: DriveAccount, lastSyncAt: Long = 0L, lastMessage: String? = null) = DriveAccountEntity(
            account.id,
            account.provider.name,
            account.label,
            account.remoteFolder,
            account.createRootFolder,
            account.rootFolderName,
            lastSyncAt,
            lastMessage
        )
    }
}

@Dao
interface SyncRecordDao {
    @Query("SELECT * FROM sync_records WHERE driveId = :driveId")
    suspend fun all(driveId: String): List<SyncRecordEntity>

    @Upsert
    suspend fun upsert(entity: SyncRecordEntity)

    @Query("DELETE FROM sync_records WHERE driveId = :driveId AND path = :path")
    suspend fun delete(driveId: String, path: String)

    @Query("DELETE FROM sync_records WHERE driveId = :driveId")
    suspend fun clear(driveId: String)
}

@Dao
interface DriveAccountDao {
    @Query("SELECT * FROM drive_accounts ORDER BY rowid")
    fun observe(): Flow<List<DriveAccountEntity>>

    @Query("SELECT * FROM drive_accounts ORDER BY rowid")
    suspend fun all(): List<DriveAccountEntity>

    @Query("SELECT * FROM drive_accounts WHERE id = :id")
    suspend fun get(id: String): DriveAccountEntity?

    @Upsert
    suspend fun upsert(entity: DriveAccountEntity)

    @Query("UPDATE drive_accounts SET lastSyncAt = :at, lastMessage = :message WHERE id = :id")
    suspend fun updateStatus(id: String, at: Long, message: String?)

    @Query("DELETE FROM drive_accounts WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [SyncRecordEntity::class, DriveAccountEntity::class], version = 2, exportSchema = false)
abstract class MarkNotesDatabase : RoomDatabase() {
    abstract fun syncRecords(): SyncRecordDao

    abstract fun driveAccounts(): DriveAccountDao

    companion object {
        /** Id of the drive account created for the v1 single Proton Drive setup. */
        const val LEGACY_PROTON_ID = "proton"

        /** v1 had a single Proton Drive: its records become the records of the "proton" drive. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drive_accounts` (`id` TEXT NOT NULL, `provider` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, `remoteFolder` TEXT NOT NULL, `createRootFolder` INTEGER NOT NULL, " +
                        "`rootFolderName` TEXT NOT NULL, `lastSyncAt` INTEGER NOT NULL, `lastMessage` TEXT, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_records_new` (`driveId` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                        "`isFolder` INTEGER NOT NULL, `remoteId` TEXT NOT NULL, `remoteVersion` TEXT NOT NULL, " +
                        "`localHash` TEXT NOT NULL, `syncedAt` INTEGER NOT NULL, PRIMARY KEY(`driveId`, `path`))"
                )
                db.execSQL(
                    "INSERT INTO `sync_records_new` (`driveId`, `path`, `isFolder`, `remoteId`, `remoteVersion`, " +
                        "`localHash`, `syncedAt`) SELECT '$LEGACY_PROTON_ID', `path`, `isFolder`, `remoteId`, " +
                        "`remoteVersion`, `localHash`, `syncedAt` FROM `sync_records`"
                )
                db.execSQL("DROP TABLE `sync_records`")
                db.execSQL("ALTER TABLE `sync_records_new` RENAME TO `sync_records`")
            }
        }

        fun create(context: Context): MarkNotesDatabase =
            Room.databaseBuilder(context, MarkNotesDatabase::class.java, "marknotes.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

/** Sync metadata of a single drive. */
class RoomSyncMetadataStore(private val dao: SyncRecordDao, private val driveId: String) : SyncMetadataStore {
    override suspend fun all(): List<SyncRecord> = dao.all(driveId).map {
        SyncRecord(it.path, it.isFolder, it.remoteId, it.remoteVersion, it.localHash, it.syncedAt)
    }

    override suspend fun upsert(record: SyncRecord) = dao.upsert(
        SyncRecordEntity(
            driveId,
            record.path,
            record.isFolder,
            record.remoteId,
            record.remoteVersion,
            record.localHash,
            record.syncedAt
        )
    )

    override suspend fun delete(path: String) = dao.delete(driveId, path)

    override suspend fun clear() = dao.clear(driveId)
}
