package com.telestash.backup

import android.content.Context
import android.net.Uri
import com.telestash.data.ItemDao
import com.telestash.data.RemoteMediaEntity
import com.telestash.data.SettingsRepository
import com.telestash.data.UploadedEntity
import com.telestash.device.DeviceMedia
import com.telestash.device.PendingMedia
import com.telestash.model.StashItem
import com.telestash.model.messageToRemoteMedia
import com.telestash.model.toEntity
import com.telestash.td.TdException
import com.telestash.td.TgClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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

    private data class UploadResult(
        val outcome: Outcome,
        val possibleRemoteMatch: Boolean = false,
    )

    data class Summary(
        val uploaded: Int,
        val skipped: Int,
        val failedNames: List<String>,
        val possibleRemoteMatches: List<String> = emptyList(),
    ) {
        val failed: Int get() = failedNames.size

        /** No uploads but at least one failure — callers may retry. */
        val allFailed: Boolean get() = uploaded == 0 && failed > 0
    }

    private const val STAGING_DIR = "backup"
    private const val STAGED_TTL_MS = 24L * 60 * 60 * 1000
    private const val MAX_STAGED_NAME_LENGTH = 120
    private const val REMOTE_INDEX_PAGE_SIZE = 100
    private const val REMOTE_INDEX_CACHE_MS = 5L * 60 * 1000
    private const val REMOTE_INDEX_UPSERT_CHUNK = 500
    private const val MAX_REMOTE_CANDIDATES = 64
    private const val REMOTE_VERIFY_BUDGET_BYTES = 512L * 1024 * 1024
    private const val MAX_POSSIBLE_REMOTE_NAMES = 3

    /** A staged cache copy plus its exact content hash. */
    private data class Staged(
        val file: File,
        val contentHash: String,
    )

    private val backupMutex = Mutex()

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

    /** Deletes staged copies left behind by interrupted uploads. */
    fun sweepStaleStaged(context: Context) {
        val now = System.currentTimeMillis()
        runCatching {
            File(context.cacheDir, STAGING_DIR)
                .listFiles()
                ?.forEach { f -> if (now - f.lastModified() > STAGED_TTL_MS) f.delete() }
        }
    }

    suspend fun invalidateRemoteIndexCache() {
        SavedMessagesIndex.invalidate()
    }

    /**
     * Uploads [items] in one pass, loading dedupe hashes and known URIs once.
     * [topicId] routes uploads into an album. Returns early on FLOOD_WAIT.
     */
    @Suppress("CyclomaticComplexMethod")
    suspend fun backupAll(
        context: Context,
        client: TgClient,
        dao: ItemDao,
        items: List<PendingMedia>,
        dedupe: Boolean,
        remoteDedupe: Boolean = false,
        topicId: Long = 0L,
        accountId: Long? = null,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): Summary {
        return backupMutex.withLock {
            if (items.isEmpty()) {
                onProgress(0, 0)
                return@withLock Summary(0, 0, emptyList())
            }
            val remoteAccountId =
                if (remoteDedupe && dedupe && topicId == 0L) {
                    try {
                        accountId ?: client.currentUserId()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TdException) {
                        if (e.isFloodWait) throw e
                        0L
                    } catch (_: Exception) {
                        0L
                    }
                } else {
                    0L
                }
            if (remoteAccountId != 0L) {
                SavedMessagesIndex.ensure(dao, client, remoteAccountId)
            }
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
            val knownUris = dao.allDeviceUris().toMutableSet()
            val processedUris = mutableSetOf<String>()
            val seq = AtomicInteger(0)
            var uploaded = 0
            var skipped = 0
            val failedNames = mutableListOf<String>()
            val possibleRemoteMatches = mutableListOf<String>()
            val remoteBudget = RemoteVerificationBudget()

            items.forEachIndexed { index, item ->
                val result =
                    uploadOne(
                        context,
                        client,
                        dao,
                        item,
                        topicId,
                        dedupe,
                        remoteAccountId,
                        remoteBudget,
                        knownHashes,
                        knownUris,
                        processedUris,
                        seq,
                    )
                when (result.outcome) {
                    Outcome.UPLOADED -> uploaded++
                    Outcome.SKIPPED -> skipped++
                    Outcome.FAILED -> failedNames.add(item.displayName)
                }
                if (result.possibleRemoteMatch) possibleRemoteMatches.add(item.displayName)
                if (result.outcome != Outcome.FAILED) {
                    processedUris += item.uri.toString()
                    knownUris += item.uri.toString()
                }
                onProgress(index + 1, items.size)
            }
            Summary(
                uploaded = uploaded,
                skipped = skipped,
                failedNames = failedNames,
                possibleRemoteMatches = possibleRemoteMatches.take(MAX_POSSIBLE_REMOTE_NAMES),
            )
        }
    }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount")
    private suspend fun uploadOne(
        context: Context,
        client: TgClient,
        dao: ItemDao,
        item: PendingMedia,
        topicId: Long,
        dedupe: Boolean,
        remoteAccountId: Long,
        remoteBudget: RemoteVerificationBudget,
        knownHashes: HashSet<String>,
        knownUris: MutableSet<String>,
        processedUris: Set<String>,
        seq: AtomicInteger,
    ): UploadResult {
        val uri = item.uri.toString()
        val shouldDedupe = dedupe && topicId == 0L
        if (uri in processedUris || (shouldDedupe && uri in knownUris)) {
            return UploadResult(Outcome.SKIPPED)
        }
        var staged: Staged? = null
        return try {
            val copy = stage(context, item, seq) ?: return UploadResult(Outcome.FAILED)
            staged = copy
            if (shouldDedupe && copy.contentHash in knownHashes) {
                dao.upsertUploaded(listOf(uploadedEntity(item, copy.contentHash)))
                return UploadResult(Outcome.SKIPPED)
            }
            val remoteCheck =
                if (shouldDedupe) {
                    isRemoteDuplicate(
                        client,
                        dao,
                        remoteAccountId,
                        item,
                        copy.file.length(),
                        copy.contentHash,
                        remoteBudget,
                    )
                } else {
                    RemoteDuplicateCheck()
                }
            if (remoteCheck.exact) {
                dao.upsertUploaded(listOf(uploadedEntity(item, copy.contentHash)))
                return UploadResult(Outcome.SKIPPED)
            }
            val msg = client.uploadDocument(copy.file.absolutePath, item.displayName, topicId)
            val mapped = StashItem.fromMessage(msg)
            dao.upsertUploaded(listOf(uploadedEntity(item, copy.contentHash)))
            if (remoteAccountId != 0L) {
                try {
                    messageToRemoteMedia(msg, remoteAccountId, topicId, copy.contentHash)?.let {
                        dao.upsertRemoteMedia(listOf(it))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }

            // Ledger first: it's the dedupe record, so if the gallery-row write
            // below fails a later pass sees this file as done instead of
            // re-uploading it (the message itself arrives on the next sync).
            writeMappedItem(dao, mapped, item, topicId, copy.contentHash)
            knownHashes.add(copy.contentHash)
            UploadResult(Outcome.UPLOADED, remoteCheck.possible)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TdException) {
            // Only a rate limit should abort the batch; other errors are per-item.
            if (e.isFloodWait) throw e
            UploadResult(Outcome.FAILED)
        } catch (_: Exception) {
            UploadResult(Outcome.FAILED)
        } finally {
            // uploadDocument returns only once the server holds the bytes (or a
            // definitive failure), so the copy can be freed right away.
            staged?.file?.delete()
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun isRemoteDuplicate(
        client: TgClient,
        dao: ItemDao,
        accountId: Long,
        item: PendingMedia,
        sizeBytes: Long,
        contentHash: String,
        budget: RemoteVerificationBudget,
    ): RemoteDuplicateCheck {
        if (accountId == 0L || sizeBytes <= 0L) return RemoteDuplicateCheck()
        val known = dao.remoteMediaByHash(accountId, 0L, contentHash)
        if (
            known != null &&
            client.messageExists(known.chatId, known.messageId) &&
            remoteFileExists(client, known)
        ) {
            return RemoteDuplicateCheck(exact = true)
        }
        var possible = false
        val allCandidates =
            dao
                .remoteMediaCandidates(accountId, 0L, sizeBytes, item.isVideo, MAX_REMOTE_CANDIDATES + 1)
                .distinctBy { it.remoteUniqueId }
        possible = allCandidates.size > MAX_REMOTE_CANDIDATES
        for (candidate in allCandidates.take(MAX_REMOTE_CANDIDATES)) {
            if (candidate.fileId > 0) {
                val remoteHash =
                    if (candidate.contentHash != null) {
                        if (
                            client.messageExists(candidate.chatId, candidate.messageId) &&
                            remoteFileExists(client, candidate)
                        ) {
                            candidate.contentHash
                        } else {
                            possible = true
                            null
                        }
                    } else if (candidate.sizeBytes <= budget.remainingBytes) {
                        budget.remainingBytes -= candidate.sizeBytes
                        hashRemoteFile(client, candidate)
                    } else {
                        possible = true
                        null
                    }
                if (remoteHash != null) {
                    if (candidate.contentHash == null) {
                        dao.updateRemoteMediaHash(accountId, candidate.remoteUniqueId, remoteHash)
                    }
                    if (
                        remoteHash == contentHash &&
                        client.messageExists(candidate.chatId, candidate.messageId)
                    ) {
                        return RemoteDuplicateCheck(exact = true)
                    }
                    possible = true
                }
            }
        }
        return RemoteDuplicateCheck(possible = possible)
    }

    private data class RemoteDuplicateCheck(
        val exact: Boolean = false,
        val possible: Boolean = false,
    )

    private class RemoteVerificationBudget(
        var remainingBytes: Long = REMOTE_VERIFY_BUDGET_BYTES,
    )

    private suspend fun remoteFileExists(
        client: TgClient,
        candidate: RemoteMediaEntity,
    ): Boolean =
        try {
            client.resolveRemoteFile(candidate.remoteFileId, remoteFileType(candidate.remoteType))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: TdException) {
            if (e.isFloodWait) throw e
            false
        } catch (_: Exception) {
            false
        }

    private suspend fun hashRemoteFile(
        client: TgClient,
        candidate: RemoteMediaEntity,
    ): String? =
        try {
            val file = client.resolveRemoteFile(candidate.remoteFileId, remoteFileType(candidate.remoteType))
            hashFile(File(client.download(file.id)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: TdException) {
            if (e.isFloodWait) throw e
            null
        } catch (_: Exception) {
            null
        }

    private fun remoteFileType(type: String): TdApi.FileType =
        when (type) {
            "photo" -> TdApi.FileTypePhoto()
            "video" -> TdApi.FileTypeVideo()
            else -> TdApi.FileTypeDocument()
        }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private object SavedMessagesIndex {
        private val mutex = Mutex()
        private var indexedAccountId = 0L
        private var indexedAt = 0L

        suspend fun ensure(
            dao: ItemDao,
            client: TgClient,
            accountId: Long,
        ) {
            if (isFresh(accountId)) return
            mutex.withLock {
                if (isFresh(accountId)) return
                try {
                    val result = scan(client, accountId)
                    if (result.complete) {
                        dao.replaceRemoteMedia(accountId, result.items)
                    } else {
                        result.items.chunked(REMOTE_INDEX_UPSERT_CHUNK).forEach { dao.upsertRemoteMedia(it) }
                    }
                    indexedAccountId = accountId
                    indexedAt = System.currentTimeMillis()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TdException) {
                    if (e.isFloodWait) throw e
                } catch (_: Exception) {
                }
            }
        }

        suspend fun invalidate() {
            mutex.withLock {
                indexedAccountId = 0L
                indexedAt = 0L
            }
        }

        private fun isFresh(accountId: Long): Boolean =
            indexedAccountId == accountId &&
                System.currentTimeMillis() - indexedAt < REMOTE_INDEX_CACHE_MS

        @Suppress("LoopWithTooManyJumpStatements")
        private suspend fun scan(
            client: TgClient,
            accountId: Long,
        ): RemoteIndexScan {
            val items = mutableListOf<RemoteMediaEntity>()
            var fromId = 0L
            var complete = false
            while (true) {
                val page = client.history(fromId, REMOTE_INDEX_PAGE_SIZE)
                val fresh =
                    if (fromId == 0L) {
                        page.messages.filter { it.id > 0L }
                    } else {
                        page.messages.filter { it.id in 1 until fromId }
                    }
                if (fresh.isEmpty()) {
                    complete = true
                    break
                }
                items += fresh.mapNotNull { messageToRemoteMedia(it, accountId) }
                val nextId = fresh.minOf { it.id }
                if (nextId <= 0L || (fromId != 0L && nextId >= fromId)) {
                    complete = true
                    break
                }
                fromId = nextId
            }
            return RemoteIndexScan(items, complete)
        }
    }

    private data class RemoteIndexScan(
        val items: List<RemoteMediaEntity>,
        val complete: Boolean,
    )

    private suspend fun writeMappedItem(
        dao: ItemDao,
        mapped: StashItem?,
        item: PendingMedia,
        topicId: Long,
        contentHash: String,
    ) {
        try {
            if (mapped != null) {
                dao.upsertAll(
                    listOf(
                        mapped.toEntity(
                            topicId,
                            deviceUri = item.uri.toString(),
                            contentHash = contentHash,
                        ),
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun copyAndHash(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
    ) {
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            digest.update(buf, 0, n)
        }
    }

    /**
     * Copies a content URI into the cache so TDLib can send it by local path,
     * hashing the stream on the fly for exact dedupe. The suffix is preserved
     * so Telegram retains the original file type.
     */
    private fun stage(
        context: Context,
        item: PendingMedia,
        seq: AtomicInteger,
    ): Staged? {
        val dir = File(context.cacheDir, STAGING_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        if (!dir.isDirectory) return null
        val extension = item.displayName.substringAfterLast('.', "")
        val suffix = if (extension.isNotEmpty() && extension.length <= 20) ".$extension" else ""
        val baseLength = (MAX_STAGED_NAME_LENGTH - suffix.length).coerceAtLeast(1)
        val safeName =
            (
                item.displayName
                    .removeSuffix(suffix)
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(baseLength) + suffix
            ).ifEmpty { "media" }
        val staged = File(dir, "${System.currentTimeMillis()}_${seq.incrementAndGet()}_$safeName")
        val digest = MessageDigest.getInstance("SHA-256")
        var complete = false
        return try {
            val input = context.contentResolver.openInputStream(item.uri) ?: return null
            input.use { source ->
                staged.outputStream().use { output ->
                    copyAndHash(source, output, digest)
                }
            }
            val contentHash = digest.digest().joinToString("") { "%02x".format(it) }
            complete = true
            Staged(staged, contentHash)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            if (!complete) staged.delete()
        }
    }

    /** Builds a [PendingMedia] for a manually picked URI. */
    fun pendingFromUri(
        uri: Uri,
        displayName: String,
        isVideo: Boolean,
        sizeBytes: Long = 0L,
    ): PendingMedia =
        PendingMedia(
            uri = uri,
            displayName = displayName,
            bucket = "",
            sizeBytes = sizeBytes,
            isVideo = isVideo,
        )

    private fun uploadedEntity(
        item: PendingMedia,
        contentHash: String,
    ) = UploadedEntity(
        contentHash = contentHash,
        fileName = item.displayName,
        sizeBytes = item.sizeBytes,
        deviceUri = item.uri.toString(),
        uploadedAt = System.currentTimeMillis(),
    )
}
