package com.telestash

import com.telestash.data.ExactHashCandidate
import com.telestash.data.ItemDao
import com.telestash.td.TgClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One message that duplicates another by exact content hash. */
data class DuplicateItem(
    val messageId: Long,
    val chatId: Long,
    val contentHash: String,
)

/** Byte-identical messages; [keepMessageId] is the oldest and is kept. */
data class DuplicateGroup(
    val items: List<DuplicateItem>,
    val keepMessageId: Long,
)

/** Result of a duplicate scan: everything [GalleryCleanup.clean] could remove. */
data class GalleryHealth(
    val duplicateGroups: List<DuplicateGroup>,
) {
    val removableCount: Int get() = duplicateGroups.sumOf { it.items.size - 1 }
}

/** Outcome of a cleanup run: messages removed and messages that failed. */
data class CleanupResult(
    val removed: Int,
    val failed: Int,
    val error: String? = null,
)

private fun ExactHashCandidate.toDuplicateItem() =
    DuplicateItem(
        messageId = messageId,
        chatId = chatId,
        contentHash = contentHash,
    )

/** Finds exact-content duplicate messages and deletes all but the oldest. */
class GalleryCleanup(
    private val dao: ItemDao,
    private val client: () -> TgClient?,
    private val accountId: suspend () -> Long,
) {
    /** Groups the local index by content hash and keeps every group with duplicates. */
    suspend fun scan(): GalleryHealth =
        withContext(Dispatchers.IO) {
            val groups =
                dao
                    .cleanupCandidates()
                    .groupBy { it.contentHash }
                    .values
                    .filter { it.size > 1 }
                    .map { candidates ->
                        DuplicateGroup(
                            items = candidates.map { it.toDuplicateItem() },
                            keepMessageId = candidates.minOf { it.messageId },
                        )
                    }
            GalleryHealth(groups)
        }

    /** Every item in the group except the one we keep. */
    private fun DuplicateGroup.extras(): List<DuplicateItem> = items.filter { it.messageId != keepMessageId }

    /** Deletes every duplicate except the kept one, cloud first, then local rows. */
    suspend fun clean(health: GalleryHealth): CleanupResult =
        withContext(Dispatchers.IO) {
            val doomed =
                health.duplicateGroups
                    .flatMap { it.extras() }
                    .distinctBy { it.messageId }
            if (doomed.isEmpty()) return@withContext CleanupResult(0, 0)

            val c = client()
            if (c == null) {
                return@withContext CleanupResult(0, doomed.size, "Telegram is not connected")
            }
            val currentAccountId =
                try {
                    accountId()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withContext CleanupResult(0, doomed.size, e.message ?: "unknown error")
                }

            var removed = 0
            val errors = mutableListOf<String>()
            doomed.groupBy { it.chatId }.forEach { (chatId, items) ->
                // Cloud first: the reverse order would leave local rows pointing
                // at messages the user just lost.
                try {
                    val ids = items.map { it.messageId }
                    c.deleteMessages(chatId, ids)
                    dao.deleteByIds(ids)
                    dao.deleteRemoteMediaByMessageIds(currentAccountId, ids)
                    removed += items.size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += e.message ?: "unknown error"
                }
            }
            CleanupResult(
                removed = removed,
                failed = doomed.size - removed,
                error = errors.firstOrNull(),
            )
        }
}
