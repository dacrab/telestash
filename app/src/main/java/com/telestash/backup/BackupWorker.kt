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
import com.telestash.model.StashItem
import com.telestash.td.TdException
import com.telestash.td.TgClient
import com.telestash.util.PerceptualHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
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
        if (!TgClient.started) return@withContext Result.retry()

        val dao = StashDbHolder.db(applicationContext).itemDao()
        val settings = SettingsRepository(applicationContext)
        val allowedFolders = parseFolders(settings.backupFolders.first())
        val dedupe = settings.dedupeEnabled.first()
        val resolver = applicationContext.contentResolver

        val toBackup = mutableListOf<BackupCandidate>()
        toBackup.addAll(query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), resolver, allowedFolders))
        toBackup.addAll(query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), resolver, allowedFolders))

        var uploaded = 0
        var failures = 0
        for (cand in toBackup) {
            if (isStopped) return@withContext Result.retry()
            try {
                if (dao.countByDeviceUri(cand.uri.toString()) > 0) continue

                val staged = File(applicationContext.cacheDir, "backup/${cand.displayName}")
                staged.parentFile?.mkdirs()
                if (staged.exists()) staged.delete()
                resolver.openInputStream(cand.uri)?.use { input ->
                    staged.outputStream().use { input.copyTo(it) }
                } ?: continue

                var phash = 0L
                if (dedupe && !cand.isVideo) {
                    phash = PerceptualHash.compute(staged) ?: 0L
                    if (phash != 0L) {
                        val dup = dao.allPhashes().any { PerceptualHash.isProbablyDuplicate(it.phash, phash) }
                        if (dup) {
                            // mark as backed up without uploading: skip & record
                            dao.upsertAll(
                                listOf(
                                    entityFor(cand, staged, msgIdPlaceholder(cand.uri), phash)
                                )
                            )
                            staged.delete()
                            continue
                        }
                    }
                }

                val msg = client.uploadDocument(staged.absolutePath, cand.displayName)
                val item = StashItem.fromMessage(msg)
                dao.upsertAll(
                    listOf(
                        ItemEntity(
                            messageId = msg.id,
                            chatId = msg.chatId,
                            date = msg.date,
                            fileId = item?.fileId ?: 0,
                            fileName = cand.displayName,
                            sizeBytes = cand.size,
                            isVideo = cand.isVideo,
                            localPath = null,
                            deviceUri = cand.uri.toString(),
                            phash = phash,
                        )
                    )
                )
                uploaded++
                staged.delete()
            } catch (e: Exception) {
                failures++
                if (e is TdException && e.isFloodWait) return@withContext Result.retry()
            }
        }

        if (failures > 0 && uploaded == 0) Result.retry() else Result.success()
    }

    /** Store a synthetic entity so a perceptual duplicate isn't re-scanned forever. */
    private fun entityFor(cand: BackupCandidate, staged: File, msgId: Long, phash: Long) =
        ItemEntity(
            messageId = msgId,
            chatId = 0,
            date = 0,
            fileId = 0,
            fileName = cand.displayName,
            sizeBytes = cand.size,
            isVideo = cand.isVideo,
            localPath = null,
            deviceUri = cand.uri.toString(),
            phash = phash,
        )

    private fun msgIdPlaceholder(uri: Uri): Long = -uri.hashCode().toLong()

    private fun parseFolders(csv: String): Set<String> =
        csv.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()

    private fun query(
        collection: Uri,
        resolver: android.content.ContentResolver,
        allowedFolders: Set<String>,
    ): List<BackupCandidate> {
        val out = mutableListOf<BackupCandidate>()
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
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val bucket = cursor.getString(bucketCol) ?: ""
                if (allowedFolders.isNotEmpty() && bucket !in allowedFolders) continue
                val uri = ContentUris.withAppendedId(collection, id)
                out.add(
                    BackupCandidate(
                        uri, name, cursor.getLong(sizeCol),
                        isVideo = collection.path?.contains("video") == true
                    )
                )
            }
        }
        return out
    }

    data class BackupCandidate(val uri: Uri, val displayName: String, val size: Long, val isVideo: Boolean)
}
