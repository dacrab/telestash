package com.telestash.backup

import android.content.Context
import android.net.Uri
import com.telestash.data.ItemDao
import com.telestash.data.RemoteMediaEntity
import com.telestash.data.UploadedEntity
import com.telestash.device.DeviceMedia
import com.telestash.device.PendingMedia
import com.telestash.model.StashItem
import com.telestash.model.messageToRemoteMedia
import com.telestash.model.toEntity
import com.telestash.td.TdException
import com.telestash.td.TgClient
import com.telestash.util.FileTypes
import com.telestash.util.Hashing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Upload engine for every path — the worker, "back up now" and manual picks all
 * funnel through [backupAll]. Stages the file, dedupes, sends it to Saved
 * Messages and records it in Room, all under one lock.
 */
object BackupEngine {
    enum class Outcome { UPLOADED, SKIPPED, FAILED }

    private data class UploadResult(
        val outcome: Outcome,
        val possibleRemoteMatch: Boolean = false,
    )

    /** Tally of one backup pass; the lists name the files behind the counts. */
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
    private const val MAX_STAGED_SUFFIX_LENGTH = 20
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

    /** Not-yet-backed-up visible device media in [allowedFolders] (all when empty). */
    suspend fun pending(
        context: Context,
        dao: ItemDao,
        allowedFolders: Set<String>,
    ): List<PendingMedia> = DeviceMedia.pending(context, dao.allDeviceUris().toSet(), allowedFolders)

    /** Deletes staged copies left behind by interrupted uploads. */
    fun sweepStaleStaged(context: Context) {
        val now = System.currentTimeMillis()
        runCatching {
            File(context.cacheDir, STAGING_DIR)
                .listFiles()
                ?.forEach { f -> if (now - f.lastModified() > STAGED_TTL_MS) f.delete() }
        }
    }

    /** Drops the cached Saved Messages index so the next pass re-scans it. */
    suspend fun invalidateRemoteIndexCache() {
        SavedMessagesIndex.invalidate()
    }

    /**
     * Uploads [items] in one pass, loading dedupe hashes and known URIs once.
     * [topicId] routes uploads into an album. Returns early on FLOOD_WAIT.
     */
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
                    remoteAccountId(client, accountId)
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
            var uploaded = 0
            var skipped = 0
            val failedNames = mutableListOf<String>()
            val possibleRemoteMatches = mutableListOf<String>()

            val pass =
                UploadPass(
                    context = context,
                    client = client,
                    dao = dao,
                    topicId = topicId,
                    remoteAccountId = remoteAccountId,
                    remoteBudget = RemoteVerificationBudget(),
                    knownHashes = knownHashes,
                    knownUris = dao.allDeviceUris().toMutableSet(),
                    processedUris = mutableSetOf(),
                    seq = AtomicInteger(0),
                    dedupe = dedupe,
                )
            items.forEachIndexed { index, item ->
                val result = pass.upload(item)
                when (result.outcome) {
                    Outcome.UPLOADED -> uploaded++
                    Outcome.SKIPPED -> skipped++
                    Outcome.FAILED -> failedNames.add(item.displayName)
                }
                if (result.possibleRemoteMatch) possibleRemoteMatches.add(item.displayName)
                if (result.outcome != Outcome.FAILED) {
                    pass.processedUris += item.uri.toString()
                    pass.knownUris += item.uri.toString()
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

    /**
     * The account whose Saved Messages to dedupe against, or 0 when it can't be
     * resolved — in which case this pass simply uploads without the cloud check.
     */
    private suspend fun remoteAccountId(
        client: TgClient,
        known: Long?,
    ): Long =
        try {
            known ?: client.currentUserId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TdException) {
            if (e.isFloodWait) throw e
            0L
        } catch (_: Exception) {
            0L
        }

    /**
     * State shared by every item in one [backupAll] pass: the dedupe lookups
     * are loaded once and grown as uploads succeed, instead of querying per item.
     */
    private class UploadPass(
        val context: Context,
        val client: TgClient,
        val dao: ItemDao,
        val topicId: Long,
        val remoteAccountId: Long,
        private val remoteBudget: RemoteVerificationBudget,
        private val knownHashes: MutableSet<String>,
        val knownUris: MutableSet<String>,
        val processedUris: MutableSet<String>,
        private val seq: AtomicInteger,
        dedupe: Boolean,
    ) {
        /** Dedupe only applies to the main library; album copies stay independent. */
        private val shouldDedupe = dedupe && topicId == 0L

        suspend fun upload(item: PendingMedia): UploadResult {
            val uri = item.uri.toString()
            if (uri in processedUris || (shouldDedupe && uri in knownUris)) {
                return UploadResult(Outcome.SKIPPED)
            }
            var staged: Staged? = null
            return try {
                val copy = stage(context, item, seq) ?: return UploadResult(Outcome.FAILED)
                staged = copy

                if (shouldDedupe && copy.contentHash in knownHashes) return recordSkip(item, copy)

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
                if (remoteCheck.exact) return recordSkip(item, copy)

                val message = client.uploadDocument(copy.file.absolutePath, item.displayName, topicId)
                // Ledger first: it is the dedupe record, so if the gallery-row
                // write below fails a later pass sees this file as done rather
                // than re-uploading it (the message itself arrives on the next sync).
                dao.upsertUploaded(listOf(uploadedEntity(item, copy.contentHash)))
                indexUploadedMessage(message, copy.contentHash)
                writeMappedItem(dao, StashItem.fromMessage(message), item, topicId, copy.contentHash)
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
                // definitive failure), so the cache copy can be freed right away.
                staged?.file?.delete()
            }
        }

        /** Already in the cloud: record it in the ledger so it is never retried. */
        private suspend fun recordSkip(
            item: PendingMedia,
            staged: Staged,
        ): UploadResult {
            dao.upsertUploaded(listOf(uploadedEntity(item, staged.contentHash)))
            // Lets a later copy of the same bytes skip staging and re-hashing.
            knownHashes += staged.contentHash
            return UploadResult(Outcome.SKIPPED)
        }

        private suspend fun indexUploadedMessage(
            message: TdApi.Message,
            contentHash: String,
        ) {
            if (remoteAccountId == 0L) return
            try {
                messageToRemoteMedia(message, remoteAccountId, topicId, contentHash)?.let {
                    dao.upsertRemoteMedia(listOf(it))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The index is an optimization; a miss only costs a re-check later.
            }
        }
    }

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
        val allCandidates =
            dao
                .remoteMediaCandidates(accountId, 0L, sizeBytes, item.isVideo, MAX_REMOTE_CANDIDATES + 1)
                .distinctBy { it.remoteUniqueId }
        // More candidates than we are willing to check means we cannot rule out
        // a match, so the caller is told a possible duplicate was left alone.
        var possible = allCandidates.size > MAX_REMOTE_CANDIDATES
        for (candidate in allCandidates.take(MAX_REMOTE_CANDIDATES).filter { it.fileId > 0 }) {
            when (verifyCandidate(client, dao, accountId, candidate, budget, contentHash)) {
                CandidateMatch.EXACT -> return RemoteDuplicateCheck(exact = true)
                CandidateMatch.UNVERIFIED -> possible = true
            }
        }
        return RemoteDuplicateCheck(possible = possible)
    }

    private enum class CandidateMatch {
        /** Byte-identical to the file being uploaded: do not upload it again. */
        EXACT,

        /** Could not be resolved; may or may not be a duplicate. */
        UNVERIFIED,
    }

    /**
     * Compares one Saved Messages candidate against the file being uploaded,
     * reusing an indexed hash or spending download budget. Only an exact match
     * counts as resolved; anything else is UNVERIFIED, so the caller warns
     * instead of silently duplicating.
     */
    private suspend fun verifyCandidate(
        client: TgClient,
        dao: ItemDao,
        accountId: Long,
        candidate: RemoteMediaEntity,
        budget: RemoteVerificationBudget,
        contentHash: String,
    ): CandidateMatch {
        val known = candidate.contentHash
        if (known != null) {
            val live =
                client.messageExists(candidate.chatId, candidate.messageId) &&
                    remoteFileExists(client, candidate)
            return when {
                !live -> CandidateMatch.UNVERIFIED
                known == contentHash -> CandidateMatch.EXACT
                else -> CandidateMatch.UNVERIFIED
            }
        }
        if (candidate.sizeBytes > budget.remainingBytes) return CandidateMatch.UNVERIFIED
        budget.remainingBytes -= candidate.sizeBytes
        val hash = hashRemoteFile(client, candidate) ?: return CandidateMatch.UNVERIFIED
        dao.updateRemoteMediaHash(accountId, candidate.remoteUniqueId, hash)
        return if (hash == contentHash) CandidateMatch.EXACT else CandidateMatch.UNVERIFIED
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
            Hashing.sha256(File(client.download(file.id)))
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

        /**
         * Walks Saved Messages newest-first, stopping at a page with nothing new;
         * reports whether the end was genuine (an incomplete walk isn't authoritative).
         */
        private suspend fun scan(
            client: TgClient,
            accountId: Long,
        ): RemoteIndexScan {
            val items = mutableListOf<RemoteMediaEntity>()
            var fromId = 0L
            while (true) {
                val fresh = client.history(fromId, REMOTE_INDEX_PAGE_SIZE).newerThan(fromId)
                if (fresh.isEmpty()) return RemoteIndexScan(items, complete = true)
                items += fresh.mapNotNull { messageToRemoteMedia(it, accountId) }
                val nextId = fresh.minOf { it.id }
                if (nextId <= 0L || (fromId != 0L && nextId >= fromId)) {
                    return RemoteIndexScan(items, complete = true)
                }
                fromId = nextId
            }
        }

        /** Messages with a real id, excluding the boundary TDLib re-sends. */
        private fun TdApi.Messages.newerThan(fromId: Long): List<TdApi.Message> =
            messages.filter { it.id > 0L && (fromId == 0L || it.id < fromId) }
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
        val suffix = if (extension.isNotEmpty() && extension.length <= MAX_STAGED_SUFFIX_LENGTH) ".$extension" else ""
        val safeName = FileTypes.sanitizeName(item.displayName, suffix)
        val staged = File(dir, "${System.currentTimeMillis()}_${seq.incrementAndGet()}_$safeName")
        val digest = MessageDigest.getInstance("SHA-256")
        var complete = false
        return try {
            val input = context.contentResolver.openInputStream(item.uri) ?: return null
            input.use { source ->
                staged.outputStream().use { output -> Hashing.copy(source, output, digest) }
            }
            complete = true
            Staged(staged, Hashing.hex(digest.digest()))
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
        deviceUri = item.uri.toString(),
    )
}
