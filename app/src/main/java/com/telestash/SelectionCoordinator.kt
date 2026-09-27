package com.telestash

import com.telestash.data.AlbumEntity
import com.telestash.data.ItemDao
import com.telestash.data.ItemEntity
import com.telestash.model.StashItem
import com.telestash.model.messageToEntity
import com.telestash.model.toStashItem
import com.telestash.td.TgClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File

internal class SelectionCoordinator(
    private val scope: CoroutineScope,
    private val gallery: MutableStateFlow<GalleryUi>,
    private val dao: ItemDao,
    private val client: () -> TgClient?,
    private val saver: MediaStoreSaver,
    private val upsertRemote: suspend (List<ItemEntity>) -> Unit,
    private val indexRemote: suspend (List<TdApi.Message>, Long) -> Unit,
    private val toast: (String) -> Unit,
) {
    fun download(item: StashItem): Job = downloadItems(listOf(item))

    fun downloadSelected() {
        val ids = gallery.value.selected ?: return
        if (ids.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val items = resolveSelectedItems(ids)
            if (items.isNotEmpty()) downloadItemsInCurrentScope(items)
        }
    }

    fun delete(item: StashItem): Job = deleteItems(listOf(item))

    fun deleteSelected() {
        val ids = gallery.value.selected ?: return
        if (ids.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val items = resolveSelectedItems(ids)
            if (items.isNotEmpty()) deleteItemsInCurrentScope(items)
        }
    }

    fun addSelectedToAlbum(
        albumTopicId: Long,
        newAlbumName: String?,
    ): Job =
        scope.launch(Dispatchers.IO) {
            val c = client() ?: return@launch
            val selected = gallery.value.selected ?: return@launch
            if (selected.isEmpty()) return@launch
            try {
                val topicId = newAlbumName?.let { c.createAlbumTopic(it) } ?: albumTopicId
                val moved = c.moveToTopic(selected.toLongArray(), topicId)
                val accountId = c.currentUserId()
                dao.deleteAll(selected.toList())
                dao.deleteRemoteMediaByMessageIds(accountId, selected.toList())
                indexRemote(moved, topicId)
                upsertRemote(moved.mapNotNull { messageToEntity(it, topicId = topicId) })
                if (newAlbumName != null) {
                    dao.upsertAlbums(
                        listOf(AlbumEntity(topicId, newAlbumName, System.currentTimeMillis())),
                    )
                }
                toast("Moved to album")
                exitSelectionMode()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Move failed: ${e.message}")
            }
        }

    fun toggleSelect(id: Long) {
        gallery.update { current ->
            val selected = current.selected
            if (selected == null) {
                current
            } else {
                val next = if (id in selected) selected - id else selected + id
                current.copy(selected = next.takeIf { it.isNotEmpty() })
            }
        }
    }

    fun enterSelection(id: Long) {
        gallery.update { it.copy(selected = setOf(id)) }
    }

    fun exitSelectionMode() {
        gallery.update { it.copy(selected = null) }
    }

    fun selectAll(loaded: List<StashItem>) {
        val ids = loaded.mapTo(mutableSetOf()) { it.messageId }
        gallery.update { it.copy(selected = ids.takeIf { set -> set.isNotEmpty() }) }
        if (ids.isNotEmpty()) toast("Selected ${ids.size} loaded items")
    }

    private fun downloadItems(items: List<StashItem>): Job =
        scope.launch(Dispatchers.IO) {
            downloadItemsInCurrentScope(items)
        }

    private fun deleteItems(items: List<StashItem>): Job =
        scope.launch(Dispatchers.IO) {
            deleteItemsInCurrentScope(items)
        }

    private suspend fun resolveSelectedItems(ids: Set<Long>): List<StashItem> {
        if (ids.isEmpty()) return emptyList()
        return dao.itemsByIds(ids.toList()).map { it.toStashItem() }
    }

    private suspend fun downloadItemsInCurrentScope(items: List<StashItem>) {
        if (items.isEmpty()) return
        val c = client() ?: return
        var saved = 0
        for (item in items) {
            if (downloadOne(c, item)) saved++
        }
        val failed = items.size - saved
        toast(
            when {
                saved == 1 && items.size == 1 -> "Saved to gallery"
                failed == 0 -> "Saved $saved items to gallery"
                else -> "Saved $saved of ${items.size} items to gallery"
            },
        )
        if (failed == 0) exitSelectionMode()
    }

    private suspend fun downloadOne(
        c: TgClient,
        item: StashItem,
    ): Boolean =
        try {
            val path = c.download(item.fileId)
            saver.save(File(path), item.fileName, item.isVideo)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private suspend fun deleteItemsInCurrentScope(items: List<StashItem>) {
        if (items.isEmpty()) return
        val c = client() ?: return
        try {
            deleteRemote(c, items)
            val ids = items.map { it.messageId }
            val accountId = c.currentUserId()
            dao.deleteAll(ids)
            dao.deleteRemoteMediaByMessageIds(accountId, ids)
            toast(if (items.size == 1) "Deleted" else "Deleted ${items.size} items")
            exitSelectionMode()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast("Delete failed: ${e.message}")
        }
    }

    private suspend fun deleteRemote(
        c: TgClient,
        items: List<StashItem>,
    ) {
        items.groupBy { it.chatId }.forEach { (chatId, group) ->
            c.deleteMessages(chatId, group.map { it.messageId }.toLongArray())
        }
    }
}
