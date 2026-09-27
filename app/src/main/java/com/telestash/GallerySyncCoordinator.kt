package com.telestash

import com.telestash.data.AlbumEntity
import com.telestash.data.ItemDao
import com.telestash.data.ItemEntity
import com.telestash.data.SettingsRepository
import com.telestash.model.messageToEntity
import com.telestash.model.messageToRemoteMedia
import com.telestash.td.TdException
import com.telestash.td.TgClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.coroutineContext

internal class GallerySyncCoordinator(
    private val scope: CoroutineScope,
    private val gallery: MutableStateFlow<GalleryUi>,
    private val dao: ItemDao,
    private val settings: SettingsRepository,
    private val client: () -> TgClient?,
    private val toast: (String) -> Unit,
    private val historyPageSize: Int,
    private val syncMaxItems: Int,
    private val loadMorePages: Int,
    private val albumCaptionPrefix: String,
) {
    private companion object {
        const val REMOTE_UPSERT_CHUNK = 500
        const val TOPIC_REQUEST_TIMEOUT_MS = 20_000L
    }

    private var oldestLoaded = Long.MAX_VALUE
    private val topicCursors = mutableMapOf<Long, Long>()
    private var topicJob: Job? = null
    private var mainHasMore = true
    private val trackedJobs = mutableSetOf<Job>()
    private var accountId = 0L

    fun setAccountId(value: Long) {
        accountId = value
    }

    suspend fun syncAll() {
        trackCurrentJob()
        val c =
            client() ?: run {
                untrackCurrentJob()
                return
            }
        ensureAccountId(c)
        val savedOldest = settings.lastSyncedOldestId()

        restoreMainCursor(savedOldest)
        gallery.update { it.copy(syncing = true, syncError = false) }
        try {
            val newestPage = c.history(0L, historyPageSize)
            val newestId = newestPage.messages.maxOfOrNull { it.id } ?: 0L
            val savedNewest = settings.lastSyncedNewestId()
            if (shouldSkipSync(newestId, savedNewest, savedOldest)) return

            val scan = collectInitialHistory(c, newestPage)
            if (scan.items.isNotEmpty()) upsertRemote(scan.items)
            val persistedOldest = chooseCursor(scan.oldestRawId, savedOldest, oldestLoaded)
            if (persistedOldest != Long.MAX_VALUE) oldestLoaded = persistedOldest
            mainHasMore = calculateMainHasMore(scan, persistedOldest)
            publishMainHasMore()
            settings.setSyncWatermark(newestId, persistedOldest)
            syncAlbums()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is TdException && e.message.orEmpty().contains("request aborted", ignoreCase = true)) {
                return
            }
            gallery.update { it.copy(syncError = true) }
            toast("Could not load gallery: ${e.message}")
        } finally {
            untrackCurrentJob()
            gallery.update { it.copy(syncing = false) }
        }
    }

    fun loadMore() {
        val current = gallery.value
        if (current.loadingMore || client() == null) return
        if (current.openTopicId == 0L) {
            loadMoreMain()
        } else {
            loadMoreTopic(current.openTopicId)
        }
    }

    fun openTopic(topicId: Long) {
        if (topicId == 0L) return
        val current = gallery.value
        if (current.openTopicId == 0L) mainHasMore = current.hasMore
        topicJob?.cancel()
        topicCursors[topicId] = Long.MAX_VALUE
        gallery.update {
            it.copy(
                openTopicId = topicId,
                selected = null,
                loadingMore = true,
                syncError = false,
                hasMore = true,
            )
        }
        topicJob =
            launchTracked tracked@{
                try {
                    val c = client() ?: return@tracked
                    val page =
                        withTimeout(TOPIC_REQUEST_TIMEOUT_MS) {
                            c.topicHistory(topicId, 0L, historyPageSize)
                        }
                    val fresh = page.messages.filter { it.id > 0L }
                    if (fresh.isNotEmpty()) {
                        indexRemoteMessages(fresh, topicId)
                        upsertRemote(fresh.mapNotNull { messageToEntity(it, topicId = topicId) })
                        topicCursors[topicId] = fresh.minOf { it.id }
                    }
                    if (isTopicActive(topicId)) {
                        gallery.update { it.copy(hasMore = fresh.isNotEmpty()) }
                    }
                } catch (_: TimeoutCancellationException) {
                    if (isTopicActive(topicId)) {
                        gallery.update { it.copy(syncError = true, hasMore = false) }
                        toast("Showing saved items. Could not refresh this album.")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (isTopicActive(topicId)) gallery.update { it.copy(syncError = true) }
                } finally {
                    if (isTopicActive(topicId)) {
                        gallery.update { it.copy(loadingMore = false) }
                    }
                }
            }
    }

    fun closeTopic() {
        topicJob?.cancel()
        topicJob = null
        topicCursors.clear()
        gallery.update {
            it.copy(
                openTopicId = 0L,
                selected = null,
                loadingMore = false,
                syncError = false,
                hasMore = mainHasMore,
            )
        }
    }

    suspend fun resetForAccountChange() {
        resetState()
        dao.clearAllAccountData()
        settings.clearSyncWatermarks()
    }

    suspend fun resetAfterLogout() {
        resetState()
        dao.clearGalleryData()
        settings.clearSyncWatermarks()
    }

    suspend fun upsertRemote(items: List<ItemEntity>) {
        items.chunked(REMOTE_UPSERT_CHUNK).forEach { dao.upsertRemote(it) }
    }

    suspend fun indexRemoteMessages(
        messages: List<TdApi.Message>,
        topicId: Long = 0L,
    ) {
        if (messages.isEmpty()) return
        val c = client() ?: return
        val id = ensureAccountId(c)
        messages
            .mapNotNull { messageToRemoteMedia(it, id, topicId) }
            .chunked(REMOTE_UPSERT_CHUNK)
            .forEach { dao.upsertRemoteMedia(it) }
    }

    private suspend fun ensureAccountId(c: TgClient): Long {
        if (accountId == 0L) accountId = c.currentUserId()
        return accountId
    }

    private fun restoreMainCursor(savedOldest: Long) {
        if (oldestLoaded != Long.MAX_VALUE) return
        if (savedOldest <= 0L || savedOldest == Long.MAX_VALUE) return
        oldestLoaded = savedOldest
    }

    private suspend fun shouldSkipSync(
        newestId: Long,
        savedNewest: Long,
        savedOldest: Long,
    ): Boolean {
        if (newestId == 0L) return false
        if (newestId != savedNewest) return false
        if (savedOldest == Long.MAX_VALUE) return false
        return dao.hasGalleryItems()
    }

    private suspend fun collectInitialHistory(
        c: TgClient,
        firstPage: TdApi.Messages,
    ): HistoryScan {
        val items = mutableListOf<ItemEntity>()
        val seenRawIds = mutableSetOf<Long>()
        var page = firstPage
        var fromId = 0L
        var scanned = 0
        var oldestRawId = Long.MAX_VALUE
        var exhausted = false
        var keepGoing = true
        while (scanned < syncMaxItems && keepGoing) {
            indexRemoteMessages(page.messages.toList())
            val step = scanPage(page, syncMaxItems - scanned, seenRawIds)
            items += step.items
            scanned += step.scanned
            oldestRawId = minOf(oldestRawId, step.oldestRawId)
            exhausted = step.exhausted
            if (step.stop || scanned >= syncMaxItems) {
                keepGoing = false
            } else {
                val nextFrom = step.lastId
                val invalidCursor = nextFrom <= 0L
                val didNotMoveBack = fromId != 0L && nextFrom >= fromId
                if (invalidCursor || didNotMoveBack) {
                    keepGoing = false
                } else {
                    fromId = nextFrom
                    page = c.history(fromId, minOf(historyPageSize, syncMaxItems - scanned))
                }
            }
        }
        return HistoryScan(items, oldestRawId, exhausted)
    }

    private fun scanPage(
        page: TdApi.Messages,
        remaining: Int,
        seenRawIds: MutableSet<Long>,
    ): HistoryPage {
        if (page.messages.isEmpty()) {
            return HistoryPage(emptyList(), 0, Long.MAX_VALUE, Long.MAX_VALUE, true, true)
        }
        val messages = page.messages.take(remaining)
        val items = mutableListOf<ItemEntity>()
        var consumed = 0
        var oldestRawId = Long.MAX_VALUE
        var repeated = false
        for (message in messages) {
            if (message.id <= 0L || !seenRawIds.add(message.id)) {
                repeated = true
                break
            }
            consumed++
            oldestRawId = minOf(oldestRawId, message.id)
            messageToEntity(message)?.let(items::add)
        }
        return HistoryPage(
            items = items,
            scanned = consumed,
            oldestRawId = oldestRawId,
            lastId = messages.getOrNull(consumed - 1)?.id ?: Long.MAX_VALUE,
            exhausted = false,
            stop = repeated || consumed == 0,
        )
    }

    private fun chooseCursor(
        oldestRawId: Long,
        savedOldest: Long,
        loadedOldest: Long,
    ): Long =
        listOf(oldestRawId, savedOldest, loadedOldest)
            .filter { it > 0L && it != Long.MAX_VALUE }
            .minOrNull() ?: Long.MAX_VALUE

    private fun calculateMainHasMore(
        scan: HistoryScan,
        persistedOldest: Long,
    ): Boolean {
        if (!scan.exhausted) return true
        val hasOlderRawMessages = scan.oldestRawId != Long.MAX_VALUE
        return hasOlderRawMessages && persistedOldest < scan.oldestRawId
    }

    private fun publishMainHasMore() {
        gallery.update { state ->
            if (state.openTopicId == 0L) state.copy(hasMore = mainHasMore) else state
        }
    }

    private fun loadMoreMain() {
        if (oldestLoaded == Long.MAX_VALUE) return
        gallery.update { it.copy(loadingMore = true) }
        launchTracked tracked@{
            try {
                val c = client() ?: return@tracked
                val newestWatermark = settings.lastSyncedNewestId()
                val batch = collectMoreMain(c, oldestLoaded)
                if (batch.items.isNotEmpty()) upsertRemote(batch.items)
                if (batch.fromId < oldestLoaded) {
                    oldestLoaded = batch.fromId
                    settings.setSyncWatermark(newestWatermark, batch.fromId)
                }
                mainHasMore = !batch.exhausted
                publishMainHasMore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Could not load more: ${e.message}")
            } finally {
                gallery.update { state ->
                    if (state.openTopicId == 0L) state.copy(loadingMore = false) else state
                }
            }
        }
    }

    private suspend fun collectMoreMain(
        c: TgClient,
        startFromId: Long,
    ): MainPageBatch {
        val entities = mutableListOf<ItemEntity>()
        val seenRawIds = mutableSetOf<Long>()
        var fromId = startFromId
        var pagesLeft = loadMorePages
        var exhausted = false
        var done = false
        while (pagesLeft > 0 && !done) {
            pagesLeft--
            val page = c.history(fromId, historyPageSize)
            indexRemoteMessages(page.messages.toList())
            val step = scanMoreMainPage(page, fromId, seenRawIds)
            entities += step.items
            fromId = step.fromId
            exhausted = step.exhausted
            done = step.done
        }
        return MainPageBatch(fromId, exhausted, entities)
    }

    private fun scanMoreMainPage(
        page: TdApi.Messages,
        fromId: Long,
        seenRawIds: MutableSet<Long>,
    ): MainPageStep {
        val fresh = page.messages.filter { it.id in 1 until fromId }
        if (fresh.isEmpty()) return MainPageStep(emptyList(), fromId, true, true)
        val consumed = fresh.takeWhile { seenRawIds.add(it.id) }
        val items = consumed.mapNotNull { messageToEntity(it) }
        if (consumed.isEmpty()) return MainPageStep(emptyList(), fromId, false, true)
        val nextFrom = consumed.minOf { it.id }
        if (nextFrom >= fromId) return MainPageStep(items, fromId, false, true)
        return MainPageStep(items, nextFrom, false, false)
    }

    private fun loadMoreTopic(topicId: Long) {
        val cursor = topicCursors[topicId] ?: return
        if (cursor == Long.MAX_VALUE || !gallery.value.hasMore) return
        gallery.update { it.copy(loadingMore = true) }
        launchTracked tracked@{
            try {
                val c = client() ?: return@tracked
                val fromId = cursor
                val page =
                    withTimeout(TOPIC_REQUEST_TIMEOUT_MS) {
                        c.topicHistory(topicId, fromId, historyPageSize)
                    }
                val fresh = page.messages.filter { it.id in 1 until fromId }
                if (fresh.isEmpty()) {
                    gallery.update { state ->
                        if (state.openTopicId == topicId) state.copy(hasMore = false) else state
                    }
                    return@tracked
                }
                indexRemoteMessages(fresh, topicId)
                upsertRemote(fresh.mapNotNull { messageToEntity(it, topicId = topicId) })
                if (isTopicActive(topicId)) {
                    topicCursors[topicId] = fresh.minOf { it.id }
                    // A non-empty page always leaves an older cursor to resume from.
                    gallery.update { it.copy(hasMore = true) }
                }
            } catch (_: TimeoutCancellationException) {
                if (isTopicActive(topicId)) {
                    gallery.update { it.copy(syncError = true, hasMore = false) }
                    toast("Showing saved items. Could not refresh this album.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isTopicActive(topicId)) toast("Could not load this album: ${e.message}")
            } finally {
                if (isTopicActive(topicId)) {
                    gallery.update { it.copy(loadingMore = false) }
                }
            }
        }
    }

    private suspend fun syncAlbums() {
        val c = client() ?: return
        try {
            c.loadTopics().forEach { topic ->
                try {
                    syncAlbum(c, topic)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun syncAlbum(
        c: TgClient,
        topic: TdApi.SavedMessagesTopic,
    ) {
        val messages = c.topicHistory(topic.id, 0L, historyPageSize)
        indexRemoteMessages(messages.messages.toList(), topic.id)
        upsertRemote(messages.messages.mapNotNull { messageToEntity(it, topicId = topic.id) })
        if (dao.albumName(topic.id) == null) {
            dao.upsertAlbums(listOf(AlbumEntity(topic.id, albumNameFrom(messages.messages), topic.order)))
        }
    }

    private fun albumNameFrom(messages: Array<TdApi.Message>): String {
        val caption =
            messages
                .mapNotNull { message ->
                    (message.content as? TdApi.MessageText)
                        ?.text
                        ?.text
                        ?.takeIf { it.startsWith(albumCaptionPrefix) }
                }.firstOrNull()
        return caption?.removePrefix(albumCaptionPrefix)?.ifBlank { "Album" } ?: "Album"
    }

    private fun isTopicActive(topicId: Long): Boolean = gallery.value.openTopicId == topicId

    private suspend fun trackCurrentJob() {
        val job = coroutineContext[Job] ?: return
        synchronized(trackedJobs) { trackedJobs.add(job) }
        job.invokeOnCompletion { synchronized(trackedJobs) { trackedJobs.remove(job) } }
    }

    private suspend fun untrackCurrentJob() {
        val job = coroutineContext[Job] ?: return
        synchronized(trackedJobs) { trackedJobs.remove(job) }
    }

    private fun launchTracked(block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(Dispatchers.IO) { block() }
        synchronized(trackedJobs) { trackedJobs.add(job) }
        job.invokeOnCompletion { synchronized(trackedJobs) { trackedJobs.remove(job) } }
        return job
    }

    private suspend fun cancelTrackedJobsAndJoin() {
        val jobs = synchronized(trackedJobs) { trackedJobs.toList().also { trackedJobs.clear() } }
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
    }

    private suspend fun resetState() {
        cancelTrackedJobsAndJoin()
        accountId = 0L
        oldestLoaded = Long.MAX_VALUE
        topicJob?.cancel()
        topicJob?.join()
        topicJob = null
        topicCursors.clear()
        mainHasMore = true
        gallery.update { GalleryUi() }
    }

    private data class HistoryScan(
        val items: List<ItemEntity>,
        val oldestRawId: Long,
        val exhausted: Boolean,
    )

    private data class HistoryPage(
        val items: List<ItemEntity>,
        val scanned: Int,
        val oldestRawId: Long,
        val lastId: Long,
        val exhausted: Boolean,
        val stop: Boolean,
    )

    private data class MainPageBatch(
        val fromId: Long,
        val exhausted: Boolean,
        val items: List<ItemEntity>,
    )

    private data class MainPageStep(
        val items: List<ItemEntity>,
        val fromId: Long,
        val exhausted: Boolean,
        val done: Boolean,
    )
}
