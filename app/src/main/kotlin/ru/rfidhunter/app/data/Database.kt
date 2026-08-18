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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.rfidhunter.app.domain.Item
import ru.rfidhunter.app.domain.normalizeText

@Entity(tableName = "items")
data class ItemEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val epc: String,
    val photoPath: String? = null,
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY id ASC")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ItemEntity?

    @Query("SELECT * FROM items WHERE upper(epc) = upper(:epc) LIMIT 1")
    suspend fun getByEpc(epc: String): ItemEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int
}

@Database(entities = [ItemEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "rfidhunter.db",
        ).build()
    }
}

class ItemRepository(private val dao: ItemDao) {
    val items: Flow<List<Item>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getByEpc(epc: String): Item? = dao.getByEpc(epc)?.toDomain()

    suspend fun getById(id: Long): Item? = dao.getById(id)?.toDomain()

    suspend fun insert(name: String, epc: String, photoPath: String?): Long = dao.insert(
        ItemEntity(
            name = name,
            normalizedName = normalizeText(name),
            epc = epc,
            photoPath = photoPath,
        ),
    )

    suspend fun update(item: Item) = dao.update(
        ItemEntity(
            id = item.id,
            name = item.name,
            normalizedName = normalizeText(item.name),
            epc = item.epc,
            photoPath = item.photoPath,
        ),
    )

    suspend fun delete(item: Item) = dao.deleteById(item.id)

    suspend fun seedIfEmpty() {
        if (dao.count() == 0) {
            insert("Очки", "E2000017221101441890AB01", null)
            insert("Ключи", "E2000017221101441890AB02", null)
            insert("Пульт", "E2000017221101441890AB03", null)
        }
    }
}

private fun ItemEntity.toDomain() = Item(
    id = id,
    name = name,
    epc = epc,
    photoPath = photoPath,
)
