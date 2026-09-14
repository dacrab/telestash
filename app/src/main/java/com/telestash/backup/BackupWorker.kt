package com.telestash.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.telestash.data.ItemEntity
import com.telestash.data.SettingsRepository
import com.telestash.data.StashDbHolder
import com.telestash.device.DeviceMedia
import com.telestash.device.PendingMedia
import com.telestash.model.messageToEntity
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
 * perceptual hash (near-duplicate images). Trashed and pending media is never
 * picked up (see [DeviceMedia]).
 */
class BackupWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {
    companion object {
        const val PERIODIC = "telestash-backup"
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val client = TgClient.peek() ?: return@withContext Result.failure()
            if (!client.isStarted) return@withContext Result.retry()

            val dao = StashDbHolder.db(applicationContext).itemDao()
            val settings = SettingsRepository(applicationContext)
            val allowedFolders = settings.backupFolders.first()
            val known = dao.allDeviceUris().toSet()
            val candidates = DeviceMedia.pending(applicationContext, known, allowedFolders)
            val dedupe = settings.dedupeEnabled.first()

            var uploaded = 0
            var failures = 0
            for (cand in candidates) {
                if (isStopped) return@withContext Result.retry()
                if (dao.countByDeviceUri(cand.uri.toString()) > 0) continue
                try {
                    when (backupOne(client, dao, cand, dedupe)) {
                        Outcome.UPLOADED -> uploaded++
                        Outcome.SKIPPED -> Unit
                        Outcome.FAILED -> failures++
                    }
                } catch (e: TdException) {
                    if (e.isFloodWait) return@withContext Result.retry()
                    failures++
                }
            }
            if (failures > 0 && uploaded == 0) Result.retry() else Result.success()
        }

    private enum class Outcome { UPLOADED, SKIPPED, FAILED }

    /** Back up one item; returns UPLOADED, SKIPPED (dupe) or FAILED. */
    private suspend fun backupOne(
        client: TgClient,
        dao: com.telestash.data.ItemDao,
        cand: PendingMedia,
        dedupe: Boolean,
    ): Outcome {
        val staged = stage(cand) ?: return Outcome.FAILED
        try {
            var phash = 0L
            if (dedupe && !cand.isVideo) {
                phash = PerceptualHash.compute(staged) ?: 0L
                if (phash != 0L && dao.allPhashes().any { PerceptualHash.isProbablyDuplicate(it, phash) }) {
                    // record without uploading so this uri is never re-scanned
                    dao.upsertAll(listOf(cand.markerEntity(phash)))
                    return Outcome.SKIPPED
                }
            }
            val msg = client.uploadDocument(staged.absolutePath, cand.displayName)
            dao.upsertAll(
                listOfNotNull(
                    messageToEntity(msg)?.copy(deviceUri = cand.uri.toString(), phash = phash),
                ),
            )
            return Outcome.UPLOADED
        } catch (e: TdException) {
            throw e // caller converts FLOOD_WAIT into a retry; others count as failure
        } catch (_: Exception) {
            return Outcome.FAILED
        } finally {
            staged.delete()
        }
    }

    // ---------- helpers ----------

    /** Copy a device media item into the cache dir so TDLib can send it by path. */
    private fun stage(cand: PendingMedia): File? {
        val dir = File(applicationContext.cacheDir, "backup").apply { mkdirs() }
        val staged = File(dir, "${System.currentTimeMillis()}_${cand.displayName}")
        return applicationContext.contentResolver.openInputStream(cand.uri)?.use { input ->
            staged.outputStream().use { input.copyTo(it) }
            staged
        }
    }

    /** Synthetic Room row marking a perceptual duplicate as "handled". */
    private fun PendingMedia.markerEntity(phash: Long) =
        ItemEntity(
            messageId = -uri.hashCode().toLong(),
            chatId = 0L,
            date = 0,
            fileId = 0,
            fileName = displayName,
            sizeBytes = sizeBytes,
            isVideo = isVideo,
            localPath = null,
            deviceUri = uri.toString(),
            phash = phash,
        )
}
