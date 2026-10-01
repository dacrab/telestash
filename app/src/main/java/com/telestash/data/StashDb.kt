package com.telestash.data

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

@Entity(
    tableName = "remote_media",
    primaryKeys = ["accountId", "messageId"],
    indices = [
        Index(value = ["accountId", "topicId", "sizeBytes", "isVideo"]),
        Index(value = ["accountId", "topicId", "contentHash"]),
        Index(value = ["accountId", "remoteUniqueId"]),
    ],
)
data class RemoteMediaEntity(
    val accountId: Long,
    val messageId: Long,
    val chatId: Long,
    val topicId: Long,
    val fileId: Int,
    val remoteUniqueId: String,
    val fileName: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
    val contentHash: String? = null,
    @ColumnInfo(defaultValue = "''") val remoteFileId: String = "",
    @ColumnInfo(defaultValue = "'document'") val remoteType: String = "document",
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
        /** [filter]: -1 = all, 0 = photos, 1 = videos. */
        private const val PAGED_WHERE =
            "(:filter < 0 OR isVideo = :filter) ORDER BY date DESC, messageId DESC"

        private const val CLEANUP_COLUMNS = "messageId, chatId, contentHash"
    }

    @Query("SELECT * FROM items WHERE topicId = 0 AND $PAGED_WHERE")
    fun pagingMainItems(filter: Int): PagingSource<Int, ItemEntity>

    @Query("SELECT * FROM items WHERE topicId = :topicId AND $PAGED_WHERE")
    fun pagingTopicItems(
        topicId: Long,
        filter: Int,
    ): PagingSource<Int, ItemEntity>

    @Query("SELECT contentHash FROM items WHERE contentHash IS NOT NULL")
    suspend fun allContentHashes(): List<String>

    @Query("SELECT * FROM items WHERE messageId IN (:ids) ORDER BY date DESC, messageId DESC")
    suspend fun itemsByIds(ids: List<Long>): List<ItemEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM items)")
    suspend fun hasGalleryItems(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM remote_media)")
    suspend fun hasRemoteMedia(): Boolean

    @Query("SELECT contentHash FROM uploads")
    suspend fun allUploadedHashes(): List<String>

    @Query(
        "SELECT * FROM remote_media " +
            "WHERE accountId = :accountId AND topicId = :topicId " +
            "AND sizeBytes = :sizeBytes AND isVideo = :isVideo " +
            "GROUP BY remoteUniqueId " +
            "ORDER BY messageId DESC LIMIT :limit",
    )
    suspend fun remoteMediaCandidates(
        accountId: Long,
        topicId: Long,
        sizeBytes: Long,
        isVideo: Boolean,
        limit: Int,
    ): List<RemoteMediaEntity>

    @Query(
        "SELECT * FROM remote_media " +
            "WHERE accountId = :accountId AND topicId = :topicId " +
            "AND contentHash = :contentHash LIMIT 1",
    )
    suspend fun remoteMediaByHash(
        accountId: Long,
        topicId: Long,
        contentHash: String,
    ): RemoteMediaEntity?

    @Query(
        "UPDATE remote_media SET contentHash = :contentHash " +
            "WHERE accountId = :accountId AND remoteUniqueId = :remoteUniqueId",
    )
    suspend fun updateRemoteMediaHash(
        accountId: Long,
        remoteUniqueId: String,
        contentHash: String,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRemoteMedia(item: RemoteMediaEntity)

    @Query(
        """
        UPDATE remote_media SET
            chatId = :chatId,
            topicId = :topicId,
            fileId = :fileId,
            remoteUniqueId = :remoteUniqueId,
            fileName = :fileName,
            sizeBytes = :sizeBytes,
            isVideo = :isVideo,
            contentHash = CASE
                WHEN remoteUniqueId = :remoteUniqueId THEN COALESCE(:contentHash, contentHash)
                ELSE :contentHash
            END,
            remoteFileId = :remoteFileId,
            remoteType = :remoteType
        WHERE accountId = :accountId AND messageId = :messageId
        """,
    )
    suspend fun updateRemoteMedia(
        accountId: Long,
        messageId: Long,
        chatId: Long,
        topicId: Long,
        fileId: Int,
        remoteUniqueId: String,
        fileName: String,
        sizeBytes: Long,
        isVideo: Boolean,
        contentHash: String?,
        remoteFileId: String,
        remoteType: String,
    ): Int

    @Transaction
    suspend fun upsertRemoteMedia(items: List<RemoteMediaEntity>) {
        items.forEach { item ->
            if (
                updateRemoteMedia(
                    accountId = item.accountId,
                    messageId = item.messageId,
                    chatId = item.chatId,
                    topicId = item.topicId,
                    fileId = item.fileId,
                    remoteUniqueId = item.remoteUniqueId,
                    fileName = item.fileName,
                    sizeBytes = item.sizeBytes,
                    isVideo = item.isVideo,
                    contentHash = item.contentHash,
                    remoteFileId = item.remoteFileId,
                    remoteType = item.remoteType,
                ) == 0
            ) {
                insertRemoteMedia(item)
            }
        }
    }

    @Query("DELETE FROM remote_media WHERE accountId = :accountId AND messageId IN (:ids)")
    suspend fun deleteRemoteMediaByMessageIds(
        accountId: Long,
        ids: List<Long>,
    )

    @Query(
        "SELECT remoteUniqueId, contentHash FROM remote_media " +
            "WHERE accountId = :accountId AND topicId = 0 AND contentHash IS NOT NULL",
    )
    suspend fun knownRemoteMediaHashes(accountId: Long): List<RemoteMediaHash>

    @Query("DELETE FROM remote_media WHERE accountId = :accountId AND topicId = 0")
    suspend fun clearMainRemoteMediaForAccount(accountId: Long)

    @Transaction
    suspend fun replaceRemoteMedia(
        accountId: Long,
        items: List<RemoteMediaEntity>,
    ) {
        val knownHashes = knownRemoteMediaHashes(accountId).associate { it.remoteUniqueId to it.contentHash }
        clearMainRemoteMediaForAccount(accountId)
        upsertRemoteMedia(
            items.map { item ->
                item.copy(contentHash = item.contentHash ?: knownHashes[item.remoteUniqueId])
            },
        )
    }

    @Upsert
    suspend fun upsertUploaded(uploaded: List<UploadedEntity>)

    @Query("SELECT name FROM albums WHERE topicId = :topicId")
    suspend fun albumName(topicId: Long): String?

    @Query(
        "SELECT deviceUri FROM items WHERE deviceUri IS NOT NULL " +
            "UNION SELECT deviceUri FROM uploads WHERE deviceUri IS NOT NULL",
    )
    suspend fun allDeviceUris(): List<String>

    /** Rows with exact-content evidence for duplicate grouping. */
    @Query(
        "SELECT $CLEANUP_COLUMNS FROM items " +
            "WHERE contentHash IS NOT NULL AND contentHash != ''",
    )
    suspend fun cleanupCandidates(): List<ExactHashCandidate>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRemoteIfAbsent(item: ItemEntity)

    @Query(
        """
        UPDATE items SET
            chatId = :chatId,
            date = :date,
            fileId = :fileId,
            fileName = :fileName,
            sizeBytes = :sizeBytes,
            isVideo = :isVideo,
            localPath = CASE WHEN localPath IS NULL OR localPath = '' THEN :localPath ELSE localPath END,
            topicId = :topicId,
            thumbFileId = CASE WHEN :thumbFileId != 0 THEN :thumbFileId ELSE thumbFileId END,
            miniJpeg = COALESCE(miniJpeg, :miniJpeg)
        WHERE messageId = :messageId
        """,
    )
    suspend fun updateRemoteFields(
        messageId: Long,
        chatId: Long,
        date: Int,
        fileId: Int,
        fileName: String,
        sizeBytes: Long,
        isVideo: Boolean,
        localPath: String?,
        topicId: Long,
        thumbFileId: Int,
        miniJpeg: ByteArray?,
    ): Int

    @Transaction
    suspend fun upsertRemote(items: List<ItemEntity>) {
        items.forEach { item ->
            val updated =
                updateRemoteFields(
                    messageId = item.messageId,
                    chatId = item.chatId,
                    date = item.date,
                    fileId = item.fileId,
                    fileName = item.fileName,
                    sizeBytes = item.sizeBytes,
                    isVideo = item.isVideo,
                    localPath = item.localPath,
                    topicId = item.topicId,
                    thumbFileId = item.thumbFileId,
                    miniJpeg = item.miniJpeg,
                )
            if (updated == 0) insertRemoteIfAbsent(item)
        }
    }

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

    /** Everything scoped to one account: gallery, albums and the remote index. */
    @Query("DELETE FROM items")
    suspend fun clearItems()

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    @Query("DELETE FROM uploads")
    suspend fun clearUploads()

    @Query("DELETE FROM remote_media")
    suspend fun clearRemoteMedia()

    @Transaction
    suspend fun clearGalleryData() {
        clearItems()
        clearAlbums()
    }

    @Transaction
    suspend fun clearAllAccountData() {
        clearGalleryData()
        clearRemoteMedia()
        clearUploads()
    }
}

/**
 * Rebuilds `items` in the current shape and adds the tables that arrived with
 * it, carrying rows forward from [fromVersion].
 *
 * v0.3.0 is the only public release and it shipped schema 2. Schemas 1 and 3
 * to 7 only ever existed in development builds, so these are the only two
 * upgrade paths that have to keep working; Room replays one on next app start.
 */
private fun migrateToCurrent(
    db: SupportSQLiteDatabase,
    fromVersion: Int,
) {
    // Columns carried forward, in the order the current table declares them.
    val carriedForward =
        when (fromVersion) {
            // v1 (v0.2.0): no phash, no topicId, no albums table.
            1 -> "`localPath`, `deviceUri`, NULL, 0, 0, NULL"
            // v2 (v0.3.0): has topicId, still carries a now-unused phash.
            2 -> "`localPath`, `deviceUri`, NULL, `topicId`, 0, NULL"
            else -> error("Unsupported StashDb version $fromVersion")
        }
    db.execSQL(
        """
        CREATE TABLE `items_new` (
            `messageId` INTEGER NOT NULL,
            `chatId` INTEGER NOT NULL,
            `date` INTEGER NOT NULL,
            `fileId` INTEGER NOT NULL,
            `fileName` TEXT NOT NULL,
            `sizeBytes` INTEGER NOT NULL,
            `isVideo` INTEGER NOT NULL,
            `localPath` TEXT,
            `deviceUri` TEXT,
            `contentHash` TEXT,
            `topicId` INTEGER NOT NULL,
            `thumbFileId` INTEGER NOT NULL,
            `miniJpeg` BLOB,
            PRIMARY KEY(`messageId`)
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT INTO `items_new` (
            `messageId`, `chatId`, `date`, `fileId`, `fileName`,
            `sizeBytes`, `isVideo`, `localPath`, `deviceUri`,
            `contentHash`, `topicId`, `thumbFileId`, `miniJpeg`
        )
        SELECT
            `messageId`, `chatId`, `date`, `fileId`, `fileName`,
            `sizeBytes`, `isVideo`, $carriedForward
        FROM `items`
        """.trimIndent(),
    )
    db.execSQL("DROP TABLE `items`")
    db.execSQL("ALTER TABLE `items_new` RENAME TO `items`")

    INDEXES.forEach(db::execSQL)
    // v1 predates albums entirely; v2 already has the table.
    if (fromVersion == 1) db.execSQL(CREATE_ALBUMS)
    db.execSQL(CREATE_UPLOADS)
    db.execSQL(CREATE_REMOTE_MEDIA)
    REMOTE_MEDIA_INDEXES.forEach(db::execSQL)
}

private val INDEXES =
    listOf(
        "CREATE INDEX `index_items_topicId_date_messageId` ON `items` (`topicId`, `date`, `messageId`)",
        "CREATE INDEX `index_items_topicId_isVideo_date_messageId` " +
            "ON `items` (`topicId`, `isVideo`, `date`, `messageId`)",
        "CREATE INDEX `index_items_deviceUri` ON `items` (`deviceUri`)",
        "CREATE INDEX `index_items_contentHash` ON `items` (`contentHash`)",
    )

private val REMOTE_MEDIA_INDEXES =
    listOf(
        "CREATE INDEX IF NOT EXISTS `index_remote_media_accountId_topicId_sizeBytes_isVideo` " +
            "ON `remote_media` (`accountId`, `topicId`, `sizeBytes`, `isVideo`)",
        "CREATE INDEX IF NOT EXISTS `index_remote_media_accountId_topicId_contentHash` " +
            "ON `remote_media` (`accountId`, `topicId`, `contentHash`)",
        "CREATE INDEX IF NOT EXISTS `index_remote_media_accountId_remoteUniqueId` " +
            "ON `remote_media` (`accountId`, `remoteUniqueId`)",
    )

private val CREATE_ALBUMS =
    """
    CREATE TABLE `albums` (
        `topicId` INTEGER NOT NULL,
        `name` TEXT NOT NULL,
        `order` INTEGER NOT NULL,
        PRIMARY KEY(`topicId`)
    )
    """.trimIndent()

private val CREATE_UPLOADS =
    """
    CREATE TABLE `uploads` (
        `contentHash` TEXT NOT NULL,
        `fileName` TEXT NOT NULL,
        `sizeBytes` INTEGER NOT NULL,
        `deviceUri` TEXT,
        `uploadedAt` INTEGER NOT NULL,
        PRIMARY KEY(`contentHash`)
    )
    """.trimIndent()

private val CREATE_REMOTE_MEDIA =
    """
    CREATE TABLE `remote_media` (
        `accountId` INTEGER NOT NULL,
        `messageId` INTEGER NOT NULL,
        `chatId` INTEGER NOT NULL,
        `topicId` INTEGER NOT NULL,
        `fileId` INTEGER NOT NULL,
        `remoteUniqueId` TEXT NOT NULL,
        `fileName` TEXT NOT NULL,
        `sizeBytes` INTEGER NOT NULL,
        `isVideo` INTEGER NOT NULL,
        `contentHash` TEXT,
        `remoteFileId` TEXT NOT NULL DEFAULT '',
        `remoteType` TEXT NOT NULL DEFAULT 'document',
        PRIMARY KEY(`accountId`, `messageId`)
    )
    """.trimIndent()

val MIGRATION_1_8 =
    object : Migration(1, 8) {
        override fun migrate(db: SupportSQLiteDatabase) = migrateToCurrent(db, 1)
    }

val MIGRATION_2_8 =
    object : Migration(2, 8) {
        override fun migrate(db: SupportSQLiteDatabase) = migrateToCurrent(db, 2)
    }

@Database(
    entities = [
        ItemEntity::class,
        AlbumEntity::class,
        UploadedEntity::class,
        RemoteMediaEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class StashDb : RoomDatabase() {
    abstract fun itemDao(): ItemDao
}

data class RemoteMediaHash(
    val remoteUniqueId: String,
    val contentHash: String,
)

data class ExactHashCandidate(
    val messageId: Long,
    val chatId: Long,
    val contentHash: String?,
)

/** Row projection of [ItemDao.observeTopicCounts]. */
data class TopicStats(
    val topicId: Long,
    val count: Int,
)
