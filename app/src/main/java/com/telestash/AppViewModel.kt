package com.telestash

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.telestash.backup.BackupEngine
import com.telestash.backup.BackupWorker
import com.telestash.data.AlbumEntity
import com.telestash.data.ItemEntity
import com.telestash.data.SettingsRepository
import com.telestash.data.StashDbHolder
import com.telestash.data.TopicStats
import com.telestash.device.DeviceMedia
import com.telestash.device.NewMediaWatcher
import com.telestash.device.PendingMedia
import com.telestash.model.StashItem
import com.telestash.model.messageToEntity
import com.telestash.model.toStashItem
import com.telestash.td.TdException
import com.telestash.td.TgClient
import com.telestash.ui.gallery.TimelineRow
import com.telestash.util.FileTypes
import com.telestash.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface AuthUi {
    data object NeedCredentials : AuthUi

    data object Loading : AuthUi

    data object Phone : AuthUi

    data class Code(
        val hint: String,
    ) : AuthUi

    data class Password(
        val hint: String,
    ) : AuthUi

    data object Registration : AuthUi

    data object Ready : AuthUi
}

data class GalleryUi(
    val albums: List<AlbumEntity> = emptyList(),
    val loadingMore: Boolean = false,
    val syncing: Boolean = false,
    /** Last sync attempt failed; the gallery may be stale or empty. */
    val syncError: Boolean = false,
    /** Non-null = multi-select mode active with these message ids. */
    val selected: Set<Long>? = null,
    /** 0 = main gallery; otherwise the open album topic id. */
    val openTopicId: Long = 0L,
    /** New device media detected; count > 0 shows the "new media" banner. */
    val newMediaCount: Int = 0,
)

class AppViewModel(
    app: Application,
) : AndroidViewModel(app) {
    companion object {
        const val MIN_API_HASH_LENGTH = 8
        const val HISTORY_PAGE_SIZE = 100

        /** Initial cloud-history sync cap; endless scroll appends beyond it. */
        const val SYNC_MAX_ITEMS = 4000

        /** Pages fetched per endless-scroll trigger (300 items). */
        const val LOAD_MORE_PAGES = 3
        const val PICK_LIMIT = 100
        const val SCROLL_PRELOAD_THRESHOLD = 12
        const val BACKUP_INTERVAL_HOURS = 6L
        const val BACKOFF_SECONDS = 30L
        const val ALBUM_CAPTION_PREFIX = "telestash-album:"
        const val FILTER_ALL = -1
        const val FILTER_PHOTOS = 0
        const val FILTER_VIDEOS = 1
        const val TAB_PHOTOS = 0
        const val TAB_ALBUMS = 1
        const val TAB_SETTINGS = 2
        private const val TIMELINE_PAGE_SIZE = 60
        private const val TIMELINE_PREFETCH_DISTANCE = 30
        private const val TIMELINE_MAX_SIZE = 300
        private const val SECONDS_PER_MINUTE = 60

        /** Room writes below 4000 rows can lock long enough to jank the grid. */
        private const val SYNC_UPSERT_CHUNK = 500

        /** How long logout waits for TDLib to drop the session before reset. */
        private const val LOGOUT_TIMEOUT_MS = 15_000L
    }

    private val settings = SettingsRepository(app)
    private val dao by lazy { StashDbHolder.db(app).itemDao() }

    private val _auth = MutableStateFlow<AuthUi>(AuthUi.Loading)
    val auth: StateFlow<AuthUi> = _auth.asStateFlow()

    /** Last auth error, shown inline on the active step; cleared on state change. */
    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _gallery = MutableStateFlow(GalleryUi())
    val gallery: StateFlow<GalleryUi> = _gallery.asStateFlow()

    /** Upload progress (done, total); separate flow so the grid never recomposes per item. */
    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private var client: TgClient? = null
    private var oldestLoaded = Long.MAX_VALUE
    private var started = false

    val autoBackup = settings.autoBackup
    val wifiOnly = settings.wifiOnly
    val chargingOnly = settings.chargingOnly
    val backupFolders = settings.backupFolders
    val dedupeEnabled = settings.dedupeEnabled
    val instantUpload = settings.instantUpload

    /** -1 = all media, 0 = photos only, 1 = videos only. */
    private val _mediaFilter = MutableStateFlow(FILTER_ALL)
    val mediaFilter: StateFlow<Int> = _mediaFilter.asStateFlow()

    /** Bottom-navigation tab: Photos / Albums / Settings. */
    private val _selectedTab = MutableStateFlow(TAB_PHOTOS)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    /** Item counts per album, for the Albums tab "N photos" labels. */
    val albumCounts: Flow<List<TopicStats>> = dao.observeTopicCounts()

    /**
     * Switch tabs. Leaving the Photos tab (back out of an album, or direct
     * tab tap while a topic is open) returns the timeline to the main library.
     */
    fun selectTab(tab: Int) {
        if (tab == TAB_PHOTOS && _selectedTab.value == TAB_ALBUMS && _gallery.value.openTopicId != 0L) {
            closeTopic()
        }
        _selectedTab.value = tab
    }

    /** Re-pull the newest page and fold any new remote media into Room. */
    fun refresh() {
        if (client == null || _gallery.value.syncing) return
        viewModelScope.launch(Dispatchers.IO) { syncAll() }
    }

    fun setMediaFilter(filter: Int) {
        _mediaFilter.value = filter
    }

    /**
     * Paged gallery timeline: Room pages stay bounded in memory. Month
     * separators are fused into the stream, and it rebuilds on topic or
     * filter change.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val galleryPaging: Flow<PagingData<TimelineRow>> =
        combine(
            _gallery.map { it.openTopicId }.distinctUntilChanged(),
            _mediaFilter,
            ::Pair,
        ).flatMapLatest { (topicId, filter) ->
            Pager(
                config =
                    PagingConfig(
                        pageSize = TIMELINE_PAGE_SIZE,
                        prefetchDistance = TIMELINE_PREFETCH_DISTANCE,
                        enablePlaceholders = false,
                        maxSize = TIMELINE_MAX_SIZE,
                    ),
                pagingSourceFactory = {
                    if (topicId == 0L) {
                        dao.pagingMainItems(filter)
                    } else {
                        dao.pagingTopicItems(topicId, filter)
                    }
                },
            ).flow
                .map { pagingData ->
                    pagingData
                        .map { TimelineRow.Photo(it.toStashItem()) }
                        .insertSeparators { before, after ->
                            val afterMonth =
                                after?.let { Format.monthYear(it.item.date) }
                                    ?: return@insertSeparators null
                            val beforeMonth =
                                before?.let { Format.monthYear(it.item.date) }
                            if (afterMonth != beforeMonth) TimelineRow.Month(afterMonth) else null
                        }
                }
        }.cachedIn(viewModelScope)

    /** Watches MediaStore and either auto-uploads or banners new device media. */
    private val newMediaWatcher =
        NewMediaWatcher(getApplication(), viewModelScope) { pending, autoUpload ->
            if (autoUpload) {
                uploadPendingItems(pending, auto = true)
            } else {
                _gallery.value = _gallery.value.copy(newMediaCount = pending.size)
            }
        }

    /** Start (or restart after credentials change) the TDLib client + collectors. */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch(Dispatchers.IO) {
            BackupEngine.sweepStaleStaged(getApplication())
            val (apiId, apiHash) = settings.credentialsOnce()
            if (apiId == 0 || apiHash.isBlank()) {
                _auth.value = AuthUi.NeedCredentials
                return@launch
            }
            val c = TgClient.getOrCreate(getApplication(), apiId, apiHash)
            client = c
            // Subscribe before driving TDLib, so the initial auth-state burst
            // (WaitTdlibParameters -> WaitPhoneNumber) can't slip past us.
            observeGallery()
            collectAuthStates(c)
            runCatching { c.start() }
                .onFailure { e ->
                    _toasts.tryEmit("TDLib init failed: ${e.message}")
                    _auth.value = AuthUi.NeedCredentials
                    return@launch
                }
        }
    }

    /** Room -> UI: album chips. The photo timeline itself pages via galleryPaging. */
    private fun observeGallery() {
        viewModelScope.launch {
            dao.observeAlbums().collect { albums ->
                _gallery.value = _gallery.value.copy(albums = albums)
            }
        }
    }

    private suspend fun collectAuthStates(c: TgClient) {
        c.updates
            .filterIsInstance<TdApi.UpdateAuthorizationState>()
            .map { it.authorizationState }
            .collect { state ->
                _authError.value = null
                when (state) {
                    is TdApi.AuthorizationStateWaitTdlibParameters ->
                        runCatching { c.start() }
                    is TdApi.AuthorizationStateWaitPhoneNumber ->
                        _auth.value = AuthUi.Phone
                    is TdApi.AuthorizationStateWaitCode ->
                        _auth.value = AuthUi.Code(describeCode(state.codeInfo))
                    is TdApi.AuthorizationStateWaitPassword ->
                        _auth.value = AuthUi.Password(state.passwordHint.orEmpty())
                    is TdApi.AuthorizationStateWaitRegistration ->
                        _auth.value = AuthUi.Registration
                    is TdApi.AuthorizationStateReady -> {
                        _auth.value = AuthUi.Ready
                        runCatching { syncAll() }
                        newMediaWatcher.start()
                    }
                    else -> Unit
                }
            }
    }

    private fun describeCode(info: TdApi.AuthenticationCodeInfo): String =
        when (val t = info.type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage ->
                "Code sent to your Telegram app (${t.length} digits)\nCheck your other Telegram sessions"
            is TdApi.AuthenticationCodeTypeSms -> "Code sent via SMS"
            is TdApi.AuthenticationCodeTypeCall -> "Code sent via phone call"
            is TdApi.AuthenticationCodeTypeFlashCall -> "Code sent via flash call"
            is TdApi.AuthenticationCodeTypeMissedCall -> "Code sent via missed call"
            is TdApi.AuthenticationCodeTypeFragment -> "Code sent via Fragment"
            else -> "Code sent"
        }

    fun saveCredentials(
        apiId: String,
        apiHash: String,
    ) = viewModelScope.launch(Dispatchers.IO) {
        val id = apiId.trim().toIntOrNull()
        if (id == null || id <= 0 || apiHash.trim().length < MIN_API_HASH_LENGTH) {
            _toasts.tryEmit("Invalid credentials")
            return@launch
        }
        settings.setCredentials(id, apiHash.trim())
        _toasts.tryEmit("Saved. Starting Telegram client...")

        TgClient.shutdown()
        client = null
        started = false
        _auth.value = AuthUi.Loading
        start()
    }

    /** Step back to the phone field from code/password/registration steps. */
    fun backToAuthStep() {
        _authError.value = null
        when (_auth.value) {
            is AuthUi.Code, is AuthUi.Password, is AuthUi.Registration -> _auth.value = AuthUi.Phone
            else -> Unit
        }
    }

    private val authBusy = MutableStateFlow(false)
    val authBusyPublic: StateFlow<Boolean> = authBusy.asStateFlow()

    fun submitPhone(phone: String) = authIo { it.setPhone(phone.trim()) }

    fun submitCode(code: String) = authIo { it.checkCode(code.trim()) }

    fun submitPassword(pw: String) = authIo { it.checkPassword(pw) }

    fun submitRegistration(name: String) = authIo { it.registerUser(name.trim()) }

    /** Auth requests are one-at-a-time; Telegram rate-limits duplicate sends. */
    private fun authIo(block: suspend (TgClient) -> Unit) {
        val c = client ?: return
        if (!authBusy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching { block(c) }.onFailure { e -> _authError.value = userError(e) }
            } finally {
                authBusy.value = false
            }
        }
    }

    private fun userError(e: Throwable): String {
        val msg = e.message ?: "unknown error"
        return if (e is TdException && e.isFloodWait) {
            val secs = e.floodWaitSeconds
            val wait =
                if (secs >= SECONDS_PER_MINUTE) {
                    "${secs / SECONDS_PER_MINUTE} min ${secs % SECONDS_PER_MINUTE}s"
                } else {
                    "${secs}s"
                }
            "Too many attempts. Telegram asks to wait $wait before retrying."
        } else {
            "Error: $msg"
        }
    }

    private suspend fun syncAll() {
        val c = client ?: return
        _gallery.value = _gallery.value.copy(syncing = true, syncError = false)
        try {
            // One cheap page first: if history is unchanged since the last sync
            // there is nothing new to fetch, so skip the full 4000-message
            // refetch (and the Room invalidation churn it causes) on every launch.
            val newestPage = c.history(0L, HISTORY_PAGE_SIZE)
            val newestId = newestPage.messages.firstOrNull()?.id ?: 0L
            if (
                newestId != 0L &&
                newestId == settings.lastSyncedNewestId() &&
                settings.lastSyncedOldestId() != Long.MAX_VALUE
            ) {
                return
            }
            var fromId = 0L
            val fetched = mutableListOf<ItemEntity>()
            while (fetched.size < SYNC_MAX_ITEMS) {
                val page = c.history(fromId, HISTORY_PAGE_SIZE)
                if (page.messages.isEmpty()) break
                fetched.addAll(page.messages.mapNotNull { messageToEntity(it) })
                fromId = page.messages.last().id
            }
            upsertChunked(fetched)
            val min = fetched.minOfOrNull { it.messageId } ?: Long.MAX_VALUE
            if (min < oldestLoaded) oldestLoaded = min
            settings.setSyncWatermark(newestId, min)
            syncAlbums()
            // Seed hashes for rows synced without them so the next backup
            // recognizes already-stored photos without downloading.
            galleryCleanup.backfillHashes()
        } catch (e: Exception) {
            _gallery.value = _gallery.value.copy(syncError = true)
            _toasts.tryEmit("Could not load gallery: ${e.message}")
        } finally {
            _gallery.value = _gallery.value.copy(syncing = false)
        }
    }

    private suspend fun syncAlbums() {
        val c = client ?: return
        runCatching {
            val topics = c.loadTopics()
            for (t in topics) {
                runCatching {
                    val msgs = c.topicHistory(t.id, 0L, HISTORY_PAGE_SIZE)
                    upsertChunked(msgs.messages.mapNotNull { messageToEntity(it, topicId = t.id) })
                    // Topics carry no name field; the album name lives in the
                    // first message's "telestash-album:" caption. Never clobber
                    // a locally known name with a fallback.
                    if (dao.albumName(t.id) == null) {
                        dao.upsertAlbums(
                            listOf(AlbumEntity(t.id, albumNameFrom(msgs.messages), t.order)),
                        )
                    }
                }
            }
        }
    }

    /** "telestash-album:Name" caption of the newest captioned message, or a fallback. */
    private fun albumNameFrom(messages: Array<TdApi.Message>): String =
        messages
            .mapNotNull { m ->
                (m.content as? TdApi.MessageText)?.text?.text?.takeIf { it.startsWith(ALBUM_CAPTION_PREFIX) }
            }.firstOrNull()
            ?.removePrefix(ALBUM_CAPTION_PREFIX)
            ?.ifBlank { "Album" }
            ?: "Album"

    fun loadMore() {
        val cur = _gallery.value
        if (cur.loadingMore || cur.openTopicId != 0L) return
        if (oldestLoaded == Long.MAX_VALUE || client == null) return
        _gallery.value = cur.copy(loadingMore = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Re-read inside the coroutine: the client can be torn down
                // between the guard above and this body running.
                val c = client ?: return@launch
                // Several pages per trigger: one page makes deep scrolling feel stuttery.
                val entities = mutableListOf<ItemEntity>()
                var fromId = oldestLoaded
                var pagesLeft = LOAD_MORE_PAGES
                while (pagesLeft-- > 0) {
                    val page = c.history(fromId, HISTORY_PAGE_SIZE)
                    val fresh = page.messages.filter { it.id < fromId }
                    if (fresh.isEmpty()) break
                    entities += fresh.mapNotNull { messageToEntity(it) }
                    fromId = fresh.minOf { it.id }
                }
                if (entities.isNotEmpty()) {
                    upsertChunked(entities)
                    oldestLoaded = entities.minOf { it.messageId }
                }
            } catch (e: Exception) {
                _toasts.tryEmit("Could not load more: ${e.message}")
            } finally {
                _gallery.value = _gallery.value.copy(loadingMore = false)
            }
        }
    }

    /** Upserts in bounded chunks so a huge sync can't drench Room in one go. */
    private suspend fun upsertChunked(items: List<ItemEntity>) {
        items.chunked(SYNC_UPSERT_CHUNK).forEach { dao.upsertAll(it) }
    }

    fun openTopic(topicId: Long) {
        _gallery.value = _gallery.value.copy(openTopicId = topicId, selected = null)
        if (topicId != 0L) {
            viewModelScope.launch(Dispatchers.IO) {
                val c = client ?: return@launch
                runCatching {
                    val msgs = c.topicHistory(topicId, 0L, HISTORY_PAGE_SIZE)
                    upsertChunked(msgs.messages.mapNotNull { messageToEntity(it, topicId = topicId) })
                }
            }
        }
    }

    fun closeTopic() {
        _gallery.value = _gallery.value.copy(openTopicId = 0L, selected = null)
    }

    fun createAlbum(name: String) =
        viewModelScope.launch(Dispatchers.IO) {
            val c = client ?: return@launch
            runCatching {
                val id = c.createAlbumTopic(name)
                dao.upsertAlbums(listOf(AlbumEntity(id, name, System.currentTimeMillis())))
                _toasts.tryEmit("Album \"$name\" created")
            }.onFailure { e -> _toasts.tryEmit("Could not create album: ${e.message}") }
        }

    /** Add currently selected items to an album (existing or newly created). */
    fun addSelectedToAlbum(
        albumTopicId: Long,
        newAlbumName: String?,
    ) = viewModelScope.launch(Dispatchers.IO) {
        val c = client ?: return@launch
        val sel = _gallery.value.selected ?: return@launch
        if (sel.isEmpty()) return@launch
        runCatching {
            val topicId = newAlbumName?.let { c.createAlbumTopic(it) } ?: albumTopicId
            val moved = c.moveToTopic(sel.toLongArray(), topicId)
            dao.deleteAll(sel.toList())
            upsertChunked(moved.mapNotNull { messageToEntity(it, topicId = topicId) })
            if (newAlbumName != null) {
                dao.upsertAlbums(
                    listOf(AlbumEntity(topicId, newAlbumName, System.currentTimeMillis())),
                )
            }
            _toasts.tryEmit("Moved to album")
        }.onFailure { e -> _toasts.tryEmit("Move failed: ${e.message}") }
        exitSelectionMode()
    }

    fun toggleSelect(id: Long) {
        val cur = _gallery.value
        val sel = cur.selected ?: return
        _gallery.value = cur.copy(selected = if (id in sel) sel - id else sel + id)
    }

    fun enterSelection(id: Long) {
        _gallery.value = _gallery.value.copy(selected = setOf(id))
    }

    fun exitSelectionMode() {
        _gallery.value = _gallery.value.copy(selected = null)
    }

    fun selectAll(loaded: List<StashItem>) {
        _gallery.value = _gallery.value.copy(selected = loaded.map { it.messageId }.toSet())
        if (loaded.size < SYNC_MAX_ITEMS) return
        // Paged timeline: "all" is really "all loaded so far".
        _toasts.tryEmit("Selected ${loaded.size} loaded items - scroll down to load more")
    }

    private val uploader by lazy {
        Uploader(
            viewModelScope,
            getApplication<Application>(),
            dao,
            settings,
            { client },
            _uploadProgress,
            { _gallery.value = _gallery.value.copy(newMediaCount = 0) },
        ) { msg -> _toasts.tryEmit(msg) }
    }

    /** Dismiss the "new media detected" banner without uploading. */
    fun dismissNewMedia() {
        _gallery.value = _gallery.value.copy(newMediaCount = 0)
    }

    fun uploadPending(auto: Boolean = false) = uploader.uploadPending(auto)

    /** Upload an already-scanned pending list (from the new-media watcher). */
    fun uploadPendingItems(
        pending: List<PendingMedia>,
        auto: Boolean = false,
    ) = uploader.uploadPendingItems(pending, auto)

    /** Upload manually picked media, optionally into an album topic. */
    fun upload(
        uris: List<Uri>,
        topicId: Long = 0L,
    ) = uploader.upload(uris, topicId)

    fun download(item: StashItem) = downloadItems(listOf(item))

    fun downloadSelected(loaded: List<StashItem>) {
        val sel = _gallery.value.selected ?: return
        downloadItems(loaded.filter { it.messageId in sel })
    }

    private fun downloadItems(items: List<StashItem>) =
        viewModelScope.launch(Dispatchers.IO) {
            val c = client ?: return@launch
            var ok = 0
            for (item in items) {
                runCatching {
                    val path = c.download(item.fileId)
                    saveToGallery(File(path), item.fileName, item.isVideo)
                    ok++
                }
            }
            _toasts.tryEmit(if (items.size == 1) "Saved to gallery" else "Saved $ok to gallery")
            exitSelectionMode()
        }

    fun delete(item: StashItem) = deleteItems(listOf(item))

    fun deleteSelected(loaded: List<StashItem>) {
        val sel = _gallery.value.selected ?: return
        deleteItems(loaded.filter { it.messageId in sel })
    }

    private fun deleteItems(items: List<StashItem>) =
        viewModelScope.launch(Dispatchers.IO) {
            val c = client ?: return@launch
            if (items.isEmpty()) return@launch
            runCatching {
                items.groupBy { it.chatId }.forEach { (chat, list) ->
                    c.deleteMessages(chat, list.map { it.messageId }.toLongArray())
                }
                dao.deleteAll(items.map { it.messageId })
                _toasts.tryEmit(if (items.size == 1) "Deleted" else "Deleted ${items.size} item(s)")
            }.onFailure { e -> _toasts.tryEmit("Delete failed: ${e.message}") }
            exitSelectionMode()
        }

    private val galleryCleanup by lazy {
        GalleryCleanup(viewModelScope, dao, { client }) { msg ->
            _toasts.tryEmit(msg)
        }
    }

    /** Scan Room for unloadable (size 0) rows and content-duplicate groups. */
    suspend fun scanGalleryHealth(): GalleryHealth = galleryCleanup.scan()

    /** Delete scan results (broken rows + duplicate extras), oldest kept. */
    fun cleanGallery(health: GalleryHealth) = galleryCleanup.clean(health)

    private suspend fun saveToGallery(
        src: File,
        displayName: String,
        isVideo: Boolean,
    ) = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val collection =
            if (isVideo) {
                DeviceMedia.videoCollectionPrimary()
            } else {
                DeviceMedia.imageCollectionPrimary()
            }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else FileTypes.imageMime(displayName))
            }
        val uri =
            app.contentResolver.insert(collection, values)
                ?: error("Could not create MediaStore entry")
        app.contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: error("Could not open output stream")
    }

    fun setAutoBackup(
        enabled: Boolean,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
    ) = viewModelScope.launch(Dispatchers.IO) {
        settings.setAutoBackup(enabled)
        settings.setWifiOnly(wifiOnly)
        settings.setChargingOnly(chargingOnly)
        scheduleBackup(enabled, wifiOnly, chargingOnly)
        _toasts.tryEmit(if (enabled) "Auto-backup enabled" else "Auto-backup disabled")
    }

    fun setBackupFolders(folders: Set<String>) =
        viewModelScope.launch(Dispatchers.IO) {
            settings.setBackupFolders(folders)
            _toasts.tryEmit(
                if (folders.isEmpty()) {
                    "Backing up all folders"
                } else {
                    "Backing up ${folders.size} folder(s)"
                },
            )
        }

    fun setDedupe(enabled: Boolean) =
        viewModelScope.launch(Dispatchers.IO) {
            settings.setDedupeEnabled(enabled)
        }

    fun setInstantUpload(enabled: Boolean) =
        viewModelScope.launch(Dispatchers.IO) {
            settings.setInstantUpload(enabled)
            if (enabled) uploadPending(auto = true)
        }

    private fun scheduleBackup(
        enabled: Boolean,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
    ) {
        val wm = WorkManager.getInstance(getApplication())
        if (!enabled) {
            wm.cancelUniqueWork(BackupWorker.PERIODIC)
            return
        }
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(chargingOnly)
                .build()
        val req =
            PeriodicWorkRequestBuilder<BackupWorker>(BACKUP_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
        wm.enqueueUniquePeriodicWork(BackupWorker.PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        // Kick off the first constrained pass right away instead of waiting for
        // the 6-hour schedule, so the chosen folders get backed up promptly.
        wm.enqueueUniqueWork(
            BackupWorker.ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints).build(),
        )
    }

    fun backupNow() {
        _toasts.tryEmit("Backing up now...")
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            BackupWorker.ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().build(),
        )
    }

    fun logout() =
        viewModelScope.launch(Dispatchers.IO) {
            newMediaWatcher.stop()
            val c = client
            if (c != null) {
                runCatching { c.logout() }
                waitForAuthDrop(c)
                TgClient.shutdown()
            }
            client = null
            started = false
            dao.clear()
            oldestLoaded = Long.MAX_VALUE
            _gallery.value = GalleryUi()
            // Restart against the same credentials: the TDLib database is now
            // unsigned, so the fresh client lands on the phone step.
            _auth.value = AuthUi.Loading
            start()
        }

    /** Boundedly wait until TDLib confirms the session really dropped. */
    private suspend fun waitForAuthDrop(c: TgClient) {
        withTimeoutOrNull(LOGOUT_TIMEOUT_MS) {
            c.updates
                .filterIsInstance<TdApi.UpdateAuthorizationState>()
                .map { it.authorizationState }
                .first { it is TdApi.AuthorizationStateWaitPhoneNumber }
        }
    }
}
