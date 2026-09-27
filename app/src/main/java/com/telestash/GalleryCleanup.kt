package com.telestash

import com.telestash.data.ExactHashCandidate
import com.telestash.data.ItemDao
import com.telestash.td.TgClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DuplicateItem(
    val messageId: Long,
    val chatId: Long,
    val contentHash: String,
)

data class DuplicateGroup(
    val items: List<DuplicateItem>,
    val keepMessageId: Long,
)

data class GalleryHealth(
    val duplicateGroups: List<DuplicateGroup>,
) {
    val removableCount: Int get() = duplicateGroups.sumOf { it.items.size - 1 }
}

data class CleanupResult(
    val removed: Int,
    val failed: Int,
    val error: String? = null,
)

private fun ExactHashCandidate.toDuplicateItem() =
    DuplicateItem(
        messageId = messageId,
        chatId = chatId,
        contentHash = contentHash.orEmpty(),
    )

class GalleryCleanup(
    private val dao: ItemDao,
    private val client: () -> TgClient?,
    private val accountId: suspend () -> Long,
) {
    suspend fun scan(): GalleryHealth =
        withContext(Dispatchers.IO) {
            val groups =
                dao
                    .cleanupCandidates()
                    .filter { !it.contentHash.isNullOrBlank() }
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

    private fun DuplicateGroup.verifiedExtras(): List<DuplicateItem> {
        val keep = items.firstOrNull { it.messageId == keepMessageId } ?: return emptyList()
        if (items.any { it.contentHash != keep.contentHash }) return emptyList()
        return items.filter { it.messageId != keepMessageId }
    }

    suspend fun clean(health: GalleryHealth): CleanupResult =
        withContext(Dispatchers.IO) {
            val doomed =
                health.duplicateGroups
                    .flatMap { it.verifiedExtras() }
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
                val ids = items.map { it.messageId }
                try {
                    c.deleteMessages(chatId, ids.toLongArray())
                    dao.deleteAll(ids)
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
