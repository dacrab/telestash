package com.telestash.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
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
    /** 64-bit perceptual hash of the image; 0 = not computed. */
    val phash: Long = 0L,
    /** Saved Messages topic id (album) this message lives in; 0 = main chat. */
    val topicId: Long = 0L,
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val topicId: Long,
    val name: String,
    val order: Long,
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE topicId = 0 ORDER BY date DESC, messageId DESC")
    fun observeMainItems(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE topicId = :topicId ORDER BY date DESC, messageId DESC")
    fun observeTopic(topicId: Long): Flow<List<ItemEntity>>

    @Query("SELECT phash FROM items WHERE phash != 0")
    suspend fun allPhashes(): List<Long>

    @Query("SELECT COUNT(*) FROM items WHERE deviceUri = :uri")
    suspend fun countByDeviceUri(uri: String): Int

    @Query("SELECT name FROM albums WHERE topicId = :topicId")
    suspend fun albumName(topicId: Long): String?

    @Query("SELECT deviceUri FROM items WHERE deviceUri IS NOT NULL")
    suspend fun allDeviceUris(): List<String>

    @Upsert
    suspend fun upsertAll(items: List<ItemEntity>)

    @Upsert
    suspend fun upsertAlbums(albums: List<AlbumEntity>)

    @Query("SELECT * FROM albums ORDER BY `order` DESC")
    fun observeAlbums(): Flow<List<AlbumEntity>>

    @Query("DELETE FROM albums WHERE topicId = :topicId")
    suspend fun deleteAlbum(topicId: Long)

    @Query("DELETE FROM items WHERE messageId IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    @Query("DELETE FROM items")
    suspend fun clear()
}

@Database(
    entities = [ItemEntity::class, AlbumEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class StashDb : RoomDatabase() {
    abstract fun itemDao(): ItemDao
}
