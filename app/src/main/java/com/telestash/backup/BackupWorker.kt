package com.telestash.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.telestash.data.ItemEntity
import com.telestash.data.SettingsRepository
import com.telestash.data.StashDbHolder
import com.telestash.model.messageToEntity
import com.telestash.model.toEntity
import com.telestash.td.TdException
import com.telestash.td.TgClient
import com.telestash.util.PerceptualHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scans MediaStore for images/videos not yet backed up and uploads them as
 * documents to Saved Messages. Dedupes by MediaStore uri (Room) and optional
 * perceptual hash (near-duplicate images).
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val PERIODIC = "telestash-backup"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val client = TgClient.peek() ?: return@withContext Result.failure()
        if (!client.isStarted) return@withContext Result.retry()

        val dao = StashDbHolder.db(applicationContext).itemDao()
        val settings = SettingsRepository(applicationContext)
        val allowedFolders = settings.backupFolders.first()
        val dedupe = settings.dedupeEnabled.first()
        val resolver = applicationContext.contentResolver

        val toBackup =
            queryMedia(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), resolver, allowedFolders) +
                queryMedia(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), resolver, allowedFolders)

        var uploaded = 0
        var failures = 0
        for (cand in toBackup) {
            if (isStopped) return@withContext Result.retry()
            if (dao.countByDeviceUri(cand.uri.toString()) > 0) continue
            try {
                val staged = stage(cand) ?: continue

                var phash = 0L
                if (dedupe && !cand.isVideo) {
                    phash = PerceptualHash.compute(staged) ?: 0L
                    if (phash != 0L && dao.allPhashes().any { PerceptualHash.isProbablyDuplicate(it, phash) }) {
                        // record without uploading so this uri is never re-scanned
                        dao.upsertAll(listOf(cand.markerEntity(phash)))
                        staged.delete()
                        continue
                    }
                }

                val msg = client.uploadDocument(staged.absolutePath, cand.displayName)
                dao.upsertAll(listOfNotNull(messageToEntity(msg)?.copy(deviceUri = cand.uri.toString(), phash = phash)))
                uploaded++
                staged.delete()
            } catch (e: Exception) {
                failures++
                if (e is TdException && e.isFloodWait) return@withContext Result.retry()
            }
        }

        if (failures > 0 && uploaded == 0) Result.retry() else Result.success()
    }

    // ---------- helpers ----------

    /** Copy a device media item into the cache dir so TDLib can send it by path. */
    private fun stage(cand: MediaItem): File? {
        val dir = File(applicationContext.cacheDir, "backup").apply { mkdirs() }
        val staged = File(dir, "${System.currentTimeMillis()}_${cand.displayName}")
        return applicationContext.contentResolver.openInputStream(cand.uri)?.use { input ->
            staged.outputStream().use { input.copyTo(it) }
            staged
        }
    }

    /** Synthetic Room row marking a perceptual duplicate as "handled". */
    private fun MediaItem.markerEntity(phash: Long) = ItemEntity(
        messageId = -uri.hashCode().toLong(),
        chatId = 0L,
        date = 0,
        fileId = 0,
        fileName = displayName,
        sizeBytes = size,
        isVideo = isVideo,
        localPath = null,
        deviceUri = uri.toString(),
        phash = phash,
    )

    private fun queryMedia(
        collection: Uri,
        resolver: android.content.ContentResolver,
        allowedFolders: Set<String>,
    ): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        resolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val bucket = cursor.getString(bucketCol) ?: ""
                if (allowedFolders.isNotEmpty() && bucket !in allowedFolders) continue
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                out.add(MediaItem(uri, name, cursor.getLong(sizeCol), isVideo = collection.path?.contains("video") == true))
            }
        }
        return out
    }

    /** A device media item pending backup. */
    private data class MediaItem(val uri: Uri, val displayName: String, val size: Long, val isVideo: Boolean)
}
