package com.telestash.backup

import android.content.Context
import android.net.Uri
import com.telestash.data.ItemDao
import com.telestash.data.ItemEntity
import com.telestash.device.DeviceMedia
import com.telestash.device.PendingMedia
import com.telestash.model.StashItem
import com.telestash.model.toEntity
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
     * Upload one device media item; returns UPLOADED, SKIPPED (near-duplicate)
     * or FAILED. Throws TdException for FLOOD_WAIT so callers can retry the
     * whole batch later.
     */
    suspend fun backupOne(
        context: Context,
        client: TgClient,
        dao: ItemDao,
        item: PendingMedia,
        dedupe: Boolean,
    ): Outcome {
        val staged = stage(context, item.uri) ?: return Outcome.FAILED
        try {
            var phash = 0L
            if (dedupe && !item.isVideo) {
                phash = PerceptualHash.compute(staged) ?: 0L
                if (phash != 0L && dao.allPhashes().any { PerceptualHash.isProbablyDuplicate(it, phash) }) {
                    // record without uploading so this uri is never re-scanned
                    dao.upsertAll(listOf(item.markerEntity(phash)))
                    return Outcome.SKIPPED
                }
            }
            val msg = client.uploadDocument(staged.absolutePath, item.displayName)
            StashItem.fromMessage(msg)?.let {
                dao.upsertAll(listOf(it.toEntity(deviceUri = item.uri.toString(), phash = phash)))
            }
            return Outcome.UPLOADED
        } finally {
            staged.delete()
        }
    }

    // ---------- helpers ----------

    /** Copy a content uri into the cache dir so TDLib can send it by local path. */
    private fun stage(
        context: Context,
        uri: Uri,
    ): File? {
        val dir = File(context.cacheDir, "backup").apply { mkdirs() }
        val staged = File(dir, "${System.currentTimeMillis()}_${uri.hashCode()}")
        return context.contentResolver.openInputStream(uri)?.use { input ->
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
