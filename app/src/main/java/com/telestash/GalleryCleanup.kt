package com.telestash

import com.telestash.data.ItemDao
import com.telestash.model.StashItem
import com.telestash.model.toStashItem
import com.telestash.td.TgClient
import com.telestash.util.PerceptualHash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One set of interchangeable rows; the oldest is kept, the rest can go. */
data class DuplicateGroup(
    val items: List<StashItem>,
    val keepMessageId: Long,
)

/** Result of a gallery health scan: unfixable rows plus duplicate groups. */
data class GalleryHealth(
    /** Rows whose file never arrived (size 0): previews can never load. */
    val broken: List<StashItem>,
    /** Content-identical groups (exact perceptual hash / name+size). */
    val duplicateGroups: List<DuplicateGroup>,
) {
    val removableCount: Int get() = broken.size + duplicateGroups.sumOf { it.items.size - 1 }
}

/** Gallery health scan and cleanup, owned by [AppViewModel]. */
class GalleryCleanup(
    private val scope: CoroutineScope,
    private val dao: ItemDao,
    private val client: () -> TgClient?,
    private val toast: (String) -> Unit,
) {
    private companion object {
        /** Room writes below 4000 rows can lock long enough to jank the grid. */
        const val BACKFILL_CHUNK = 500
    }

    /** Scan Room for unloadable (size 0) rows and content-duplicate groups. */
    suspend fun scan(): GalleryHealth =
        withContext(Dispatchers.IO) {
            backfillHashes()
            val broken = dao.brokenItems().map { it.toStashItem() }
            val rest = dao.cleanupCandidates()
            val hashGroups =
                rest
                    .filter { !it.isVideo && it.phash != 0L }
                    .groupBy { it.phash }
                    .values
                    .filter { it.size > 1 }
            val nameGroups =
                rest
                    .groupBy { it.fileName to it.sizeBytes }
                    .values
                    .filter { it.size > 1 }
            // Same item can match both ways: first claim wins, so every row
            // is deleted at most once and groups keep at least the oldest.
            val claimed = mutableSetOf<Long>()
            val groups = mutableListOf<DuplicateGroup>()
            (hashGroups + nameGroups).forEach { candidates ->
                val fresh = candidates.filter { it.messageId !in claimed }
                if (fresh.size > 1) {
                    val keep = fresh.minByOrNull { it.messageId } ?: return@forEach
                    claimed.addAll(fresh.map { it.messageId })
                    groups.add(
                        DuplicateGroup(
                            items = fresh.map { it.toStashItem() },
                            keepMessageId = keep.messageId,
                        ),
                    )
                }
            }
            GalleryHealth(broken, groups)
        }

    /**
     * Fills missing photo hashes from stored minithumbnails (no downloads).
     * Mini-derived hashes use the same average-hash, so they match full-file
     * hashes. Idempotent: rows that already have a hash are skipped. Chunked so
     * a first-run backfill of thousands of rows can't drench Room in one write.
     */
    suspend fun backfillHashes(): Int =
        withContext(Dispatchers.IO) {
            val updated =
                dao
                    .needsPhash()
                    .mapNotNull { e ->
                        val bytes = e.miniJpeg ?: return@mapNotNull null
                        val hash = PerceptualHash.computeBytes(bytes) ?: return@mapNotNull null
                        if (hash == 0L) null else e.copy(phash = hash)
                    }
            if (updated.isNotEmpty()) {
                updated.chunked(BACKFILL_CHUNK).forEach { dao.upsertAll(it) }
            }
            updated.size
        }

    /** Delete scan results (broken rows + duplicate extras), oldest kept. */
    fun clean(health: GalleryHealth) {
        val doomed =
            health.broken +
                health.duplicateGroups.flatMap { g ->
                    g.items.filter { it.messageId != g.keepMessageId }
                }
        if (doomed.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val c = client() ?: return@launch
            // Local rows first: if the cloud delete fails, the next sync
            // simply restores them instead of leaving invisible orphans.
            dao.deleteAll(doomed.map { it.messageId })
            runCatching {
                doomed.groupBy { it.chatId }.forEach { (chat, list) ->
                    c.deleteMessages(chat, list.map { it.messageId }.toLongArray())
                }
            }.onFailure { e ->
                toast("Cleanup incomplete, will retry on sync: ${e.message}")
                return@launch
            }
            toast("Removed ${doomed.size} item(s)")
        }
    }
}
