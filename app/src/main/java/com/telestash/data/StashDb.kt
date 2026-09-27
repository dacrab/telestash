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

@Suppress("TooManyFunctions")
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

    @Query("DELETE FROM remote_media")
    suspend fun clearRemoteMedia()

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

    @Query("DELETE FROM items")
    suspend fun clear()

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    @Query("DELETE FROM uploads")
    suspend fun clearUploads()

    @Transaction
    suspend fun clearGalleryData() {
        clear()
        clearAlbums()
    }

    @Transaction
    suspend fun clearAccountData() {
        clearGalleryData()
        clearRemoteMedia()
    }

    @Transaction
    suspend fun clearAllAccountData() {
        clearAccountData()
        clearUploads()
    }
}

private fun migrateItemsToV5(
    db: SupportSQLiteDatabase,
    fromVersion: Int,
) {
    val sourceTail =
        when (fromVersion) {
            1 -> "0, NULL, 0, 0, NULL"
            2 -> "`phash`, NULL, `topicId`, 0, NULL"
            3 -> "`phash`, NULL, `topicId`, `thumbFileId`, `miniJpeg`"
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
            `phash` INTEGER NOT NULL,
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
            `phash`, `contentHash`, `topicId`, `thumbFileId`, `miniJpeg`
        )
        SELECT
            `messageId`, `chatId`, `date`, `fileId`, `fileName`,
            `sizeBytes`, `isVideo`, `localPath`, `deviceUri`,
            $sourceTail
        FROM `items`
        """.trimIndent(),
    )
    db.execSQL("DROP TABLE `items`")
    db.execSQL("ALTER TABLE `items_new` RENAME TO `items`")
    if (fromVersion == 1) {
        db.execSQL(
            """
            CREATE TABLE `albums` (
                `topicId` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `order` INTEGER NOT NULL,
                PRIMARY KEY(`topicId`)
            )
            """.trimIndent(),
        )
    }
    db.execSQL(
        """
        CREATE TABLE `uploads` (
            `contentHash` TEXT NOT NULL,
            `fileName` TEXT NOT NULL,
            `sizeBytes` INTEGER NOT NULL,
            `deviceUri` TEXT,
            `uploadedAt` INTEGER NOT NULL,
            PRIMARY KEY(`contentHash`)
        )
        """.trimIndent(),
    )
    db.execSQL(
        "CREATE INDEX `index_items_topicId_date_messageId` ON `items` (`topicId`, `date`, `messageId`)",
    )
    db.execSQL(
        "CREATE INDEX `index_items_topicId_isVideo_date_messageId` " +
            "ON `items` (`topicId`, `isVideo`, `date`, `messageId`)",
    )
    db.execSQL("CREATE INDEX `index_items_deviceUri` ON `items` (`deviceUri`)")
    db.execSQL("CREATE INDEX `index_items_phash` ON `items` (`phash`)")
    db.execSQL("CREATE INDEX `index_items_contentHash` ON `items` (`contentHash`)")
}

val MIGRATION_1_5 =
    object : Migration(1, 5) {
        override fun migrate(db: SupportSQLiteDatabase) = migrateItemsToV5(db, 1)
    }

val MIGRATION_2_5 =
    object : Migration(2, 5) {
        override fun migrate(db: SupportSQLiteDatabase) = migrateItemsToV5(db, 2)
    }

val MIGRATION_3_5 =
    object : Migration(3, 5) {
        override fun migrate(db: SupportSQLiteDatabase) = migrateItemsToV5(db, 3)
    }

private fun migrateItemsToV6(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        INSERT OR IGNORE INTO `uploads` (`contentHash`, `fileName`, `sizeBytes`, `deviceUri`, `uploadedAt`)
        SELECT `contentHash`, `fileName`, `sizeBytes`, `deviceUri`, 0
        FROM `items`
        WHERE `messageId` <= 0 AND `contentHash` IS NOT NULL AND `contentHash` != ''
        """.trimIndent(),
    )
    db.execSQL("DELETE FROM `items` WHERE `messageId` <= 0")
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
            `sizeBytes`, `isVideo`, `localPath`, `deviceUri`,
            `contentHash`, `topicId`, `thumbFileId`, `miniJpeg`
        FROM `items`
        """.trimIndent(),
    )
    db.execSQL("DROP TABLE `items`")
    db.execSQL("ALTER TABLE `items_new` RENAME TO `items`")
    db.execSQL(
        "CREATE INDEX `index_items_topicId_date_messageId` ON `items` (`topicId`, `date`, `messageId`)",
    )
    db.execSQL(
        "CREATE INDEX `index_items_topicId_isVideo_date_messageId` " +
            "ON `items` (`topicId`, `isVideo`, `date`, `messageId`)",
    )
    db.execSQL("CREATE INDEX `index_items_deviceUri` ON `items` (`deviceUri`)")
    db.execSQL("CREATE INDEX `index_items_contentHash` ON `items` (`contentHash`)")
}

val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) = migrateItemsToV6(db)
    }

val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `remote_media` (
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
                    PRIMARY KEY(`accountId`, `messageId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_remote_media_accountId_topicId_sizeBytes_isVideo` " +
                    "ON `remote_media` (`accountId`, `topicId`, `sizeBytes`, `isVideo`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_remote_media_accountId_topicId_contentHash` " +
                    "ON `remote_media` (`accountId`, `topicId`, `contentHash`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_remote_media_accountId_remoteUniqueId` " +
                    "ON `remote_media` (`accountId`, `remoteUniqueId`)",
            )
        }
    }

val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `remote_media` ADD COLUMN `remoteFileId` TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "ALTER TABLE `remote_media` ADD COLUMN `remoteType` TEXT NOT NULL DEFAULT 'document'",
            )
        }
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
