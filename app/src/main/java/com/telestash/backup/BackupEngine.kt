package com.telestash.backup

import android.content.Context
import com.telestash.data.ItemDao
import com.telestash.data.ItemEntity
import com.telestash.device.DeviceMedia
import com.telestash.device.PendingMedia
import com.telestash.model.StashItem
import com.telestash.model.toEntity
import com.telestash.td.TdException
import com.telestash.td.TgClient
import com.telestash.util.PerceptualHash
import java.io.File

/**
 * Shared backup logic for the in-app pending uploader and the periodic
 * BackupWorker: stage a device item, dedupe, upload as document to Saved
 * Messages, record it in Room. One place so both paths behave identically.
 */
object BackupEngine {
    enum class Outcome { UPLOADED, SKIPPED, FAILED }

    /** Not-yet-backed-up visible device media matching the folder filter. */
    suspend fun pending(
        context: Context,
        dao: ItemDao,
        allowedFolders: Set<String>,
    ): List<PendingMedia> {
        val known = dao.allDeviceUris().toSet()
        return DeviceMedia.pending(context, known, allowedFolders)
    }

    /**
     * Upload every not-yet-backed-up device media item in one batched pass:
     * dedupe hashes load once, not per item. [onProgress] receives (done,total).
     * Returns when done, or early on a FLOOD_WAIT TdException (caller retries).
     */
    suspend fun backupAll(
        context: Context,
        client: TgClient,
        dao: ItemDao,
        items: List<PendingMedia>,
        dedupe: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Summary {
        val knownPhashes = if (dedupe) dao.allPhashes().toHashSet() else hashSetOf<Long>()
        var uploaded = 0
        var skipped = 0
        val failedNames = mutableListOf<String>()

        items.forEachIndexed { i, item ->
            onProgress(i, items.size)
            when (backupOne(context, client, dao, item, dedupe, knownPhashes)) {
                Outcome.UPLOADED -> uploaded++
                Outcome.SKIPPED -> skipped++
                Outcome.FAILED -> failedNames.add(item.displayName)
            }
        }
        return Summary(uploaded, skipped, failedNames)
    }

    /** Per-batch counters for the result toast. */
    data class Summary(
        val uploaded: Int,
        val skipped: Int,
        val failedNames: List<String>,
    ) {
        val failed: Int get() = failedNames.size

        /** True when nothing uploaded at all — callers may retry/schedule. */
        val allFailed: Boolean get() = uploaded == 0 && failed > 0
    }

    /**
     * Upload one device media item; returns UPLOADED, SKIPPED (near-duplicate)
     * or FAILED. Catches everything but rethrows TdException for FLOOD_WAIT so
     * callers can retry the whole batch later. [knownPhashes] is the batch-wide
     * hash set; new hashes join it so later items dedupe against earlier ones.
     */
    suspend fun backupOne(
        context: Context,
        client: TgClient,
        dao: ItemDao,
        item: PendingMedia,
        dedupe: Boolean,
        knownPhashes: MutableSet<Long> = hashSetOf(),
    ): Outcome {
        val staged = stage(context, item) ?: return Outcome.FAILED
        try {
            var phash = 0L
            if (dedupe && !item.isVideo) {
                phash = PerceptualHash.compute(staged) ?: 0L
                if (phash != 0L && knownPhashes.any { PerceptualHash.isProbablyDuplicate(it, phash) }) {
                    // record without uploading so this uri is never re-scanned
                    dao.upsertAll(listOf(item.markerEntity(phash)))
                    return Outcome.SKIPPED
                }
            }
            val msg = client.uploadDocument(staged.absolutePath, item.displayName)
            StashItem.fromMessage(msg)?.let {
                dao.upsertAll(listOf(it.toEntity(deviceUri = item.uri.toString(), phash = phash)))
                if (phash != 0L) knownPhashes.add(phash)
            }
            return Outcome.UPLOADED
        } catch (e: TdException) {
            throw e
        } catch (_: Exception) {
            return Outcome.FAILED
        } finally {
            staged.delete()
        }
    }

    // ---------- helpers ----------

    /**
     * Copy a content uri into the cache dir so TDLib can send it by local path.
     * Keeps the display name (and its extension) so Telegram preserves the
     * original file type.
     */
    private fun stage(
        context: Context,
        item: PendingMedia,
    ): File? {
        val dir = File(context.cacheDir, "backup").apply { mkdirs() }
        val staged = File(dir, "${System.currentTimeMillis()}_${item.displayName}")
        return context.contentResolver.openInputStream(item.uri)?.use { input ->
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
