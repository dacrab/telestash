package com.telestash.backup

import android.content.Context
import android.net.Uri
import com.telestash.data.ItemDao
import com.telestash.data.ItemEntity
import com.telestash.data.SettingsRepository
import com.telestash.data.UploadedEntity
import com.telestash.device.DeviceMedia
import com.telestash.device.PendingMedia
import com.telestash.model.StashItem
import com.telestash.model.toEntity
import com.telestash.td.TdException
import com.telestash.td.TgClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single upload engine for every path: the periodic worker, the in-app
 * "back up now" flow and manual picks all funnel through [backupAll]. Stages
 * the device file, dedupes, sends it as a document to Saved Messages and
 * records it in Room — all under one process-wide lock.
 */
object BackupEngine {
    enum class Outcome { UPLOADED, SKIPPED, FAILED }

    data class Summary(
        val uploaded: Int,
        val skipped: Int,
        val failedNames: List<String>,
    ) {
        val failed: Int get() = failedNames.size

        /** No uploads but at least one failure — callers may retry. */
        val allFailed: Boolean get() = uploaded == 0 && failed > 0
    }

    private const val STAGING_DIR = "backup"
    private const val STAGED_TTL_MS = 24L * 60 * 60 * 1000

    /** FNV-1a 64-bit offset basis and prime. */
    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L

    /** A staged cache copy plus its exact content hash. */
    private data class Staged(
        val file: File,
        val contentHash: String,
    )

    private val backupMutex = Mutex()

    /** Runs [block] under the process-wide backup lock. */
    suspend fun <T> exclusive(block: suspend () -> T): T = backupMutex.withLock { block() }

    /** Not-yet-backed-up visible device media for the configured folders. */
    suspend fun pending(
        context: Context,
        dao: ItemDao,
        settings: SettingsRepository,
    ): List<PendingMedia> = pending(context, dao, settings.backupFolders.first())

    suspend fun pending(
        context: Context,
        dao: ItemDao,
        allowedFolders: Set<String>,
    ): List<PendingMedia> {
        val known = dao.allDeviceUris().toSet()
        return DeviceMedia.pending(context, known, allowedFolders)
    }

    /** Deletes staged copies older than a day; they must outlive TDLib's async upload. */
    fun sweepStaleStaged(context: Context) {
        val now = System.currentTimeMillis()
        runCatching {
            File(context.cacheDir, STAGING_DIR)
                .listFiles()
                ?.forEach { f -> if (now - f.lastModified() > STAGED_TTL_MS) f.delete() }
        }
    }

    /**
     * Uploads [items] in one pass, loading dedupe hashes and known URIs once.
     * [topicId] routes uploads into an album. Returns early on FLOOD_WAIT.
     * Callers must hold the lock via [exclusive]; it is not taken here so the
     * worker and the UI can compose passes without nesting.
     */
    suspend fun backupAll(
        context: Context,
        client: TgClient,
        dao: ItemDao,
        items: List<PendingMedia>,
        dedupe: Boolean,
        topicId: Long = 0L,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Summary {
        // Both the item rows and the upload ledger feed dedupe, so files backed
        // up before a logout are still recognized as done afterwards.
        val knownHashes =
            if (dedupe) {
                (dao.allContentHashes() + dao.allUploadedHashes()).toHashSet()
            } else {
                hashSetOf()
            }
        // The list may predate a concurrent pass that already recorded these
        // URIs: skip them up front instead of a COUNT(*) per item.
        val knownUris = dao.allDeviceUris().toSet()
        val seq = AtomicInteger(0)
        var uploaded = 0
        var skipped = 0
        val failedNames = mutableListOf<String>()

        items.forEachIndexed { i, item ->
            onProgress(i, items.size)
            when (
                uploadOne(
                    context,
                    client,
                    dao,
                    item,
                    topicId,
                    dedupe,
                    knownHashes,
                    knownUris,
                    seq,
                )
            ) {
                Outcome.UPLOADED -> uploaded++
                Outcome.SKIPPED -> skipped++
                Outcome.FAILED -> failedNames.add(item.displayName)
            }
        }
        return Summary(uploaded, skipped, failedNames)
    }

    private suspend fun uploadOne(
        context: Context,
        client: TgClient,
        dao: ItemDao,
        item: PendingMedia,
        topicId: Long,
        dedupe: Boolean,
        knownHashes: HashSet<String>,
        knownUris: Set<String>,
        seq: AtomicInteger,
    ): Outcome {
        if (item.uri.toString() in knownUris) return Outcome.SKIPPED
        val staged = stage(context, item, seq) ?: return Outcome.FAILED
        return try {
            // Exact content hash only: perceptual "similarity" is never enough
            // to silently skip a distinct photo.
            if (dedupe && staged.contentHash in knownHashes) {
                dao.upsertAll(listOf(markerEntity(item, staged.contentHash)))
                return Outcome.SKIPPED
            }
            val msg = client.uploadDocument(staged.file.absolutePath, item.displayName, topicId)
            val mapped = StashItem.fromMessage(msg)
            // Ledger first: it's the dedupe record, so if the gallery-row write
            // below fails a later pass sees this file as done instead of
            // re-uploading it (the message itself arrives on the next sync).
            dao.upsertUploaded(
                listOf(
                    UploadedEntity(
                        contentHash = staged.contentHash,
                        fileName = item.displayName,
                        sizeBytes = item.sizeBytes,
                        deviceUri = item.uri.toString(),
                        uploadedAt = System.currentTimeMillis(),
                    ),
                ),
            )
            runCatching {
                if (mapped != null) {
                    dao.upsertAll(
                        listOf(
                            mapped.toEntity(
                                topicId,
                                deviceUri = item.uri.toString(),
                                contentHash = staged.contentHash,
                            ),
                        ),
                    )
                } else {
                    // Uploaded but unmappable: still record the URI so it isn't sent again.
                    dao.upsertAll(listOf(markerEntity(item, staged.contentHash)))
                }
            }
            knownHashes.add(staged.contentHash)
            Outcome.UPLOADED
        } catch (e: CancellationException) {
            throw e
        } catch (e: TdException) {
            // Only a rate limit should abort the batch; other errors are per-item.
            if (e.isFloodWait) throw e
            Outcome.FAILED
        } catch (_: Exception) {
            Outcome.FAILED
        } finally {
            // uploadDocument returns only once the server holds the bytes (or a
            // definitive failure), so the copy can be freed right away.
            staged.file.delete()
        }
    }

    /**
     * Copies a content URI into the cache so TDLib can send it by local path,
     * hashing the stream on the fly for exact dedupe. The name is kept so
     * Telegram preserves the file type, and is made unique per item because
     * TDLib can upload staged files concurrently.
     */
    private fun stage(
        context: Context,
        item: PendingMedia,
        seq: AtomicInteger,
    ): Staged? {
        val dir = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
        val safeName = item.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val staged = File(dir, "${System.currentTimeMillis()}_${seq.incrementAndGet()}_$safeName")
        val digest = MessageDigest.getInstance("SHA-256")
        return context.contentResolver.openInputStream(item.uri)?.use { input ->
            staged.outputStream().use { out ->
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                }
            }
            Staged(staged, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    /** Builds a [PendingMedia] for a manually picked URI. */
    fun pendingFromUri(
        uri: Uri,
        displayName: String,
        isVideo: Boolean,
    ): PendingMedia =
        PendingMedia(
            uri = uri,
            displayName = displayName,
            bucket = "",
            sizeBytes = 0L,
            isVideo = isVideo,
        )

    /** Synthetic row marking a duplicate or unmappable upload as handled. */
    private fun markerEntity(
        item: PendingMedia,
        contentHash: String,
    ) = ItemEntity(
        // Negative: real message ids are positive and gallery queries require
        // messageId > 0, so markers stay invisible. 64-bit FNV keeps ids unique.
        messageId = -markerId(item.uri.toString()) - 1,
        chatId = 0L,
        date = 0,
        fileId = 0,
        fileName = item.displayName,
        sizeBytes = item.sizeBytes,
        isVideo = item.isVideo,
        localPath = null,
        deviceUri = item.uri.toString(),
        contentHash = contentHash,
    )

    private fun markerId(uri: String): Long {
        var h = FNV_OFFSET_BASIS
        for (byte in uri.toByteArray()) {
            h = (h xor byte.toLong()) * FNV_PRIME
        }
        return h and Long.MAX_VALUE
    }
}
