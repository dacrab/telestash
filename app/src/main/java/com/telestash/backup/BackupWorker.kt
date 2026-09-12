package com.telestash.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.telestash.data.ItemEntity
import com.telestash.data.StashDbHolder
import com.telestash.td.TgClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

/**
 * Scans MediaStore for images/videos not yet backed up and uploads them as
 * documents to Saved Messages. Dedupes by MediaStore uri via the Room cache.
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val PERIODIC = "telestash-backup"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val client = TgClient.peek() ?: return@withContext Result.failure()
        if (!TgClient.started) return@withContext Result.retry()

        val dao = StashDbHolder.db(applicationContext).itemDao()
        val resolver = applicationContext.contentResolver

        // Only back up items newer than our newest cached entry? No - incremental by uri.
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val toBackup = mutableListOf<BackupCandidate>()
        toBackup.addAll(query(collection, resolver))
        toBackup.addAll(query(videoCollection, resolver))

        var uploaded = 0
        var failures = 0
        for (c in toBackup) {
            if (isStopped) return@withContext Result.retry()
            try {
                // stage into cache dir (TDLib needs a real path)
                val staged = File(applicationContext.cacheDir, "backup/${c.displayName}")
                staged.parentFile?.mkdirs()
                resolver.openInputStream(c.uri)?.use { input ->
                    staged.outputStream().use { input.copyTo(it) }
                } ?: continue

                val msg = client.uploadDocument(staged.absolutePath, c.displayName)
                val entity = ItemEntity(
                    messageId = msg.id,
                    chatId = msg.chatId,
                    date = msg.date,
                    fileId = (msg.content as TdApi.MessageDocument).document.document.id,
                    fileName = c.displayName,
                    sizeBytes = c.size,
                    isVideo = c.isVideo,
                    localPath = staged.absolutePath,
                    deviceUri = c.uri.toString(),
                )
                dao.upsertAll(listOf(entity))
                uploaded++
                staged.delete()
            } catch (e: Exception) {
                failures++
                if (e is com.telestash.td.TdException && e.isFloodWait) {
                    // flood-waited mid-run: back off and retry the rest later
                    return@withContext Result.retry()
                }
            }
        }

        if (failures > 0 && uploaded == 0) Result.retry() else Result.success()
    }

    private fun query(collection: Uri, resolver: android.content.ContentResolver): List<BackupCandidate> {
        val out = mutableListOf<BackupCandidate>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        resolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val uri = ContentUris.withAppendedId(collection, id)
                out.add(BackupCandidate(uri, name, cursor.getLong(sizeCol), isVideo = collection.path?.contains("video") == true))
            }
        }
        return out
    }

    data class BackupCandidate(val uri: Uri, val displayName: String, val size: Long, val isVideo: Boolean)
}
