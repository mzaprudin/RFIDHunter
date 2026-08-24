package ru.rfidhunter.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.rfidhunter.app.domain.Item
import ru.rfidhunter.app.domain.normalizeText
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Entity(tableName = "items")
data class ItemEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String,
    val name: String,
    val normalizedName: String,
    val epc: String,
    val photoPath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A durable, idempotent change waiting to be acknowledged by the server. */
@Entity(tableName = "sync_outbox")
data class SyncOutboxEntity(
    @androidx.room.PrimaryKey val operationId: String,
    val syncId: String,
    val operation: String,
    val itemId: Long?,
    val name: String?,
    val epc: String?,
    val photoPath: String?,
    val photoSha256: String?,
    val updatedAt: Long,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY id ASC")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ItemEntity?

    @Query("SELECT * FROM items WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): ItemEntity?

    @Query("SELECT * FROM items WHERE upper(epc) = upper(:epc) LIMIT 1")
    suspend fun getByEpc(epc: String): ItemEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    @Upsert
    suspend fun upsertRestored(item: ItemEntity)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM items WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int
}

@Dao
interface SyncOutboxDao {
    @Query("SELECT * FROM sync_outbox ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE syncId = :syncId")
    suspend fun deletePendingForSyncId(syncId: String)

    @Query("DELETE FROM sync_outbox WHERE operationId = :operationId")
    suspend fun delete(operationId: String)

    @Query("UPDATE sync_outbox SET attempts = attempts + 1, lastError = :error WHERE operationId = :operationId")
    suspend fun recordFailure(operationId: String, error: String)

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE syncId = :syncId)")
    suspend fun hasPending(syncId: String): Boolean
}

@Database(entities = [ItemEntity::class, SyncOutboxEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "rfidhunter.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE items ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE items ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE items ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE items SET syncId = lower(hex(randomblob(16))) WHERE syncId = ''")
                database.execSQL("UPDATE items SET createdAt = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE createdAt = 0")
                database.execSQL("UPDATE items SET updatedAt = createdAt WHERE updatedAt = 0")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS sync_outbox (
                        operationId TEXT NOT NULL PRIMARY KEY,
                        syncId TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        itemId INTEGER,
                        name TEXT,
                        epc TEXT,
                        photoPath TEXT,
                        photoSha256 TEXT,
                        updatedAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        lastError TEXT
                    )""".trimIndent(),
                )
                database.query("SELECT id, syncId, name, epc, photoPath, updatedAt FROM items").use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow("id")
                    val syncIdIndex = cursor.getColumnIndexOrThrow("syncId")
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val epcIndex = cursor.getColumnIndexOrThrow("epc")
                    val photoIndex = cursor.getColumnIndexOrThrow("photoPath")
                    val updatedIndex = cursor.getColumnIndexOrThrow("updatedAt")
                    while (cursor.moveToNext()) {
                        val photo = if (cursor.isNull(photoIndex)) null else cursor.getString(photoIndex)
                        database.execSQL(
                            "INSERT OR IGNORE INTO sync_outbox(operationId, syncId, operation, itemId, name, epc, photoPath, photoSha256, updatedAt, createdAt) VALUES(?, ?, 'UPSERT', ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                "migration-${cursor.getLong(idIndex)}-${UUID.randomUUID()}",
                                cursor.getString(syncIdIndex),
                                cursor.getLong(idIndex),
                                cursor.getString(nameIndex),
                                cursor.getString(epcIndex),
                                photo,
                                photo?.let(::sha256File),
                                cursor.getLong(updatedIndex),
                                cursor.getLong(updatedIndex),
                            ),
                        )
                    }
                }
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.query(
                    """SELECT id, syncId FROM items WHERE
                        (name = ? AND upper(epc) = ?) OR
                        (name = ? AND upper(epc) = ?) OR
                        (name = ? AND upper(epc) = ?)""".trimIndent(),
                    arrayOf(
                        "Очки",
                        "E2000017221101441890AB01",
                        "Ключи",
                        "E2000017221101441890AB02",
                        "Пульт",
                        "E2000017221101441890AB03",
                    ),
                ).use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow("id")
                    val syncIdIndex = cursor.getColumnIndexOrThrow("syncId")
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val syncId = cursor.getString(syncIdIndex)
                        database.execSQL("DELETE FROM sync_outbox WHERE syncId = ?", arrayOf(syncId))
                        database.execSQL("DELETE FROM items WHERE id = ?", arrayOf(id))
                    }
                }
            }
        }
    }
}

class ItemRepository(
    private val database: AppDatabase,
    private val syncScheduler: SyncScheduler,
) {
    private val dao = database.itemDao()
    private val outbox = database.syncOutboxDao()
    val items: Flow<List<Item>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    val pendingSyncCount: Flow<Int> = outbox.observePendingCount()

    suspend fun getByEpc(epc: String): Item? = dao.getByEpc(epc)?.toDomain()

    suspend fun getById(id: Long): Item? = dao.getById(id)?.toDomain()

    suspend fun insert(name: String, epc: String, photoPath: String?): Long {
        val now = System.currentTimeMillis()
        val entity = ItemEntity(
            syncId = UUID.randomUUID().toString(),
            name = name,
            normalizedName = normalizeText(name),
            epc = epc,
            photoPath = photoPath,
            createdAt = now,
            updatedAt = now,
        )
        val id = database.withTransaction {
            val insertedId = dao.insert(entity)
            outbox.deletePendingForSyncId(entity.syncId)
            outbox.insert(entity.toOutbox(insertedId, now))
            insertedId
        }
        syncScheduler.enqueue()
        return id
    }

    suspend fun update(item: Item) {
        val previous = dao.getById(item.id) ?: return
        val now = System.currentTimeMillis()
        val entity = ItemEntity(
            id = item.id,
            syncId = previous.syncId,
            name = item.name,
            normalizedName = normalizeText(item.name),
            epc = item.epc,
            photoPath = item.photoPath,
            createdAt = previous.createdAt,
            updatedAt = now,
        )
        database.withTransaction {
            dao.update(entity)
            outbox.deletePendingForSyncId(entity.syncId)
            outbox.insert(entity.toOutbox(item.id, now))
        }
        syncScheduler.enqueue()
    }

    suspend fun delete(item: Item) {
        val previous = dao.getById(item.id) ?: return
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.deleteById(item.id)
            outbox.deletePendingForSyncId(previous.syncId)
            outbox.insert(
                SyncOutboxEntity(
                    operationId = UUID.randomUUID().toString(),
                    syncId = previous.syncId,
                    operation = "DELETE",
                    itemId = item.id,
                    name = null,
                    epc = null,
                    photoPath = null,
                    photoSha256 = null,
                    updatedAt = now,
                    createdAt = now,
                ),
            )
        }
        syncScheduler.enqueue()
    }

}

private fun ItemEntity.toDomain() = Item(
    id = id,
    name = name,
    epc = epc,
    photoPath = photoPath,
)

private fun ItemEntity.toOutbox(itemId: Long, now: Long) = SyncOutboxEntity(
    operationId = UUID.randomUUID().toString(),
    syncId = syncId,
    operation = "UPSERT",
    itemId = itemId,
    name = name,
    epc = epc,
    photoPath = photoPath,
    photoSha256 = photoPath?.let(::sha256File),
    updatedAt = updatedAt,
    createdAt = now,
)

internal fun sha256File(path: String): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    File(path).inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()
