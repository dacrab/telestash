package com.telestash.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** A media item mirrored from Saved Messages into local Room cache. */
@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val messageId: Long,
    val chatId: Long,
    val date: Int,
    val fileId: Int,
    val fileName: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
    val localPath: String?,
    /** MediaStore content URI of the device original, if this item backs it up. */
    val deviceUri: String?,
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY date DESC, messageId DESC")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE chatId = :chatId ORDER BY date DESC LIMIT 1")
    suspend fun newest(chatId: Long): ItemEntity?

    @Query("SELECT COUNT(*) FROM items WHERE deviceUri = :uri")
    suspend fun countByDeviceUri(uri: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE messageId IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    @Query("DELETE FROM items")
    suspend fun clear()
}

@Database(entities = [ItemEntity::class], version = 1, exportSchema = false)
abstract class StashDb : RoomDatabase() {
    abstract fun itemDao(): ItemDao
}
