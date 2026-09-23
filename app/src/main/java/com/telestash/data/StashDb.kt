package com.telestash.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A media item mirrored from Saved Messages into local Room cache. */
@Entity(
    tableName = "items",
    indices = [
        // (topicId, isVideo, date, messageId) serves the Photos/Videos filter
        // sorts; (topicId, date, messageId) serves the unfiltered sort.
        Index(value = ["topicId", "date", "messageId"]),
        Index(value = ["topicId", "isVideo", "date", "messageId"]),
        Index(value = ["deviceUri"]),
        Index(value = ["phash"]),
        Index(value = ["contentHash"]),
    ],
)
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
    /** SHA-256 hex of the device original, for exact content dedupe. */
    val contentHash: String? = null,
    /** Saved Messages topic id (album) this message lives in; 0 = main chat. */
    val topicId: Long = 0L,
    /** TDLib thumbnail file id (small preview); 0 = none, fall back to full. */
    val thumbFileId: Int = 0,
    /**
     * TDLib minithumbnail JPEG bytes for an instant grid placeholder; null
     * if Telegram sent none.
     */
    val miniJpeg: ByteArray? = null,
)

/**
 * Ledger of every device file this install has backed up, kept separate from
 * [ItemEntity] so logging out (which clears the gallery) still prevents the
 * same originals from being re-uploaded to the next session.
 */
@Entity(tableName = "uploads")
data class UploadedEntity(
    @PrimaryKey val contentHash: String,
    val fileName: String,
    val sizeBytes: Long,
    /** MediaStore content URI it was uploaded from, if still on this device. */
    val deviceUri: String? = null,
    val uploadedAt: Long,
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val topicId: Long,
    val name: String,
    val order: Long,
)

@Dao
interface ItemDao {
    companion object {
        /**
         * Shared WHERE for the paged gallery. [filter]: -1 = all, 0 = photos,
         * 1 = videos. Marker rows (messageId <= 0) never reach the gallery.
         */
        private const val PAGED_WHERE =
            "messageId > 0 AND (:filter < 0 OR isVideo = :filter) ORDER BY date DESC, messageId DESC"

        /** All columns except the miniJpeg blob, for wide full-table scans. */
        private const val CLEANUP_COLUMNS =
            "messageId, chatId, date, fileId, fileName, sizeBytes, isVideo, " +
                "localPath, deviceUri, phash, topicId, thumbFileId"
    }

    @Query("SELECT * FROM items WHERE topicId = 0 AND $PAGED_WHERE")
    fun pagingMainItems(filter: Int): PagingSource<Int, ItemEntity>

    @Query("SELECT * FROM items WHERE topicId = :topicId AND $PAGED_WHERE")
    fun pagingTopicItems(
        topicId: Long,
        filter: Int,
    ): PagingSource<Int, ItemEntity>

    @Query("SELECT phash FROM items WHERE phash != 0")
    suspend fun allPhashes(): List<Long>

    @Query("SELECT contentHash FROM items WHERE contentHash IS NOT NULL")
    suspend fun allContentHashes(): List<String>

    @Query("SELECT contentHash FROM uploads")
    suspend fun allUploadedHashes(): List<String>

    @Upsert
    suspend fun upsertUploaded(uploaded: List<UploadedEntity>)

    @Query("SELECT name FROM albums WHERE topicId = :topicId")
    suspend fun albumName(topicId: Long): String?

    @Query("SELECT deviceUri FROM items WHERE deviceUri IS NOT NULL")
    suspend fun allDeviceUris(): List<String>

    /** Rows that can never load (size 0), for the gallery health scan. */
    @Query("SELECT * FROM items WHERE messageId > 0 AND sizeBytes <= 0")
    suspend fun brokenItems(): List<ItemEntity>

    /** Non-broken rows for duplicate grouping; the miniJpeg blob is excluded. */
    @Query("SELECT $CLEANUP_COLUMNS FROM items WHERE messageId > 0 AND sizeBytes > 0")
    suspend fun cleanupCandidates(): List<ItemEntity>

    /** Photos without a hash that carry a minithumbnail to hash from. */
    @Query(
        "SELECT $CLEANUP_COLUMNS FROM items WHERE messageId > 0 AND isVideo = 0 AND phash = 0 AND miniJpeg IS NOT NULL",
    )
    suspend fun needsPhash(): List<ItemEntity>

    @Upsert
    suspend fun upsertAll(items: List<ItemEntity>)

    @Upsert
    suspend fun upsertAlbums(albums: List<AlbumEntity>)

    @Query("SELECT * FROM albums ORDER BY `order` DESC")
    fun observeAlbums(): Flow<List<AlbumEntity>>

    /** Item counts per album topic, for the Albums tab "N photos" labels. */
    @Query("SELECT topicId, COUNT(*) AS count FROM items WHERE topicId != 0 GROUP BY topicId")
    fun observeTopicCounts(): Flow<List<TopicStats>>

    @Query("DELETE FROM items WHERE messageId IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    @Query("DELETE FROM items")
    suspend fun clear()

    /** Keeps the [UploadedEntity] ledger: logged-out sessions still dedupe. */
    @Query("DELETE FROM uploads")
    suspend fun clearUploads()
}

@Database(
    entities = [ItemEntity::class, AlbumEntity::class, UploadedEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class StashDb : RoomDatabase() {
    abstract fun itemDao(): ItemDao
}

/** Row projection of [ItemDao.observeTopicCounts]. */
data class TopicStats(
    val topicId: Long,
    val count: Int,
)
