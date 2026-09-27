package com.telestash

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.telestash.backup.BackupEngine
import com.telestash.data.AlbumEntity
import com.telestash.data.SettingsRepository
import com.telestash.data.StashDbHolder
import com.telestash.data.TopicStats
import com.telestash.device.NewMediaWatcher
import com.telestash.device.PendingMedia
import com.telestash.model.StashItem
import com.telestash.model.toStashItem
import com.telestash.td.TdException
import com.telestash.td.TgClient
import com.telestash.ui.gallery.TimelineRow
import com.telestash.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

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
    val hasMore: Boolean = true,
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
    private var started = false
    private var sessionJob: Job? = null
    private var authCollectorJob: Job? = null
    private var galleryCollectorJob: Job? = null

    private val gallerySync by lazy {
        GallerySyncCoordinator(
            scope = viewModelScope,
            gallery = _gallery,
            dao = dao,
            settings = settings,
            client = { client },
            toast = { message -> _toasts.tryEmit(message) },
            historyPageSize = HISTORY_PAGE_SIZE,
            syncMaxItems = SYNC_MAX_ITEMS,
            loadMorePages = LOAD_MORE_PAGES,
            albumCaptionPrefix = TgClient.ALBUM_CAPTION_PREFIX,
        )
    }
    private val mediaStoreSaver by lazy { MediaStoreSaver(getApplication(), dao) }
    private val selectionCoordinator by lazy {
        SelectionCoordinator(
            scope = viewModelScope,
            gallery = _gallery,
            dao = dao,
            client = { client },
            saver = mediaStoreSaver,
            upsertRemote = { items -> gallerySync.upsertRemote(items) },
            indexRemote = { messages, topicId -> gallerySync.indexRemoteMessages(messages, topicId) },
            toast = { message -> _toasts.tryEmit(message) },
        )
    }
    private val backupScheduler by lazy { BackupScheduler(getApplication()) }

    val autoBackup = settings.autoBackup
    val wifiOnly = settings.wifiOnly
    val chargingOnly = settings.chargingOnly
    val backupFolders = settings.backupFolders
    val dedupeEnabled = settings.dedupeEnabled
    val remoteDedupeEnabled = settings.remoteDedupeEnabled
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
        if (tab != TAB_ALBUMS && _gallery.value.openTopicId != 0L) {
            closeTopic()
        }
        _selectedTab.value = tab
    }

    /** Re-pull the newest page and fold any new remote media into Room. */
    fun refresh() {
        if (client == null || _gallery.value.syncing) return
        viewModelScope.launch(Dispatchers.IO) { gallerySync.syncAll() }
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
            _gallery.update { it.copy(newMediaCount = pending.size) }
            if (autoUpload) {
                viewModelScope.launch(Dispatchers.IO) {
                    if (settings.autoBackup.first() && settings.instantUpload.first()) {
                        uploadPendingItems(pending, auto = true)
                    }
                }
            }
        }

    /** Start (or restart after credentials change) the TDLib client + collectors. */
    fun start() {
        if (started) return
        started = true
        sessionJob =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    BackupEngine.sweepStaleStaged(getApplication())
                    val (apiId, apiHash) = settings.credentialsOnce()
                    if (apiId == 0 || apiHash.isBlank()) {
                        _auth.value = AuthUi.NeedCredentials
                        return@launch
                    }
                    val c = TgClient.getOrCreate(getApplication(), apiId, apiHash)
                    client = c
                    observeGallery()
                    c.start()
                    authCollectorJob =
                        launch(Dispatchers.IO) {
                            collectAuthStates(c)
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    authCollectorJob?.cancel()
                    authCollectorJob = null
                    galleryCollectorJob?.cancel()
                    galleryCollectorJob = null
                    reportStartupError(e)
                }
            }
    }

    private fun reportStartupError(e: Throwable) {
        _authError.value = userError(e)
        _toasts.tryEmit("TDLib init failed: ${e.message ?: "unknown error"}")
        _auth.value = AuthUi.NeedCredentials
    }

    private fun cancelSessionJobs() {
        authCollectorJob?.cancel()
        authCollectorJob = null
        galleryCollectorJob?.cancel()
        galleryCollectorJob = null
        sessionJob?.cancel()
        sessionJob = null
    }

    private suspend fun cancelSessionJobsAndJoin() {
        val jobs = listOfNotNull(authCollectorJob, galleryCollectorJob, sessionJob)
        cancelSessionJobs()
        for (job in jobs) job.join()
    }

    /** Room -> UI: album chips. The photo timeline itself pages via galleryPaging. */
    private fun observeGallery() {
        if (galleryCollectorJob?.isActive == true) return
        galleryCollectorJob =
            viewModelScope.launch {
                dao.observeAlbums().collect { albums ->
                    _gallery.update { it.copy(albums = albums) }
                }
            }
    }

    private suspend fun prepareAccount(c: TgClient) {
        uploader.cancelAndJoin()
        val userId = c.currentUserId()
        val previousUserId = settings.telegramUserId()
        val unownedData =
            previousUserId == 0L &&
                (
                    dao.hasGalleryItems() ||
                        dao.hasRemoteMedia() ||
                        dao.allContentHashes().isNotEmpty() ||
                        dao.allUploadedHashes().isNotEmpty()
                )
        if (unownedData || (previousUserId != 0L && previousUserId != userId)) {
            gallerySync.resetForAccountChange()
            BackupEngine.invalidateRemoteIndexCache()
            _selectedTab.value = TAB_PHOTOS
            _toasts.emit("Telegram account changed; local backup state was reset")
        }
        gallerySync.setAccountId(userId)
        settings.setTelegramUserId(userId)
    }

    private suspend fun collectAuthStates(c: TgClient) {
        try {
            handleAuthorizationState(c, c.refreshAuthorizationState())
            c.updates
                .filterIsInstance<TdApi.UpdateAuthorizationState>()
                .map { it.authorizationState }
                .collect { state -> handleAuthorizationState(c, state) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportStartupError(e)
        }
    }

    private suspend fun handleAuthorizationState(
        c: TgClient,
        state: TdApi.AuthorizationState,
    ) {
        if (client !== c) return
        _authError.value = null
        when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> _auth.value = AuthUi.Phone
            is TdApi.AuthorizationStateWaitCode -> _auth.value = AuthUi.Code(describeCode(state.codeInfo))
            is TdApi.AuthorizationStateWaitPassword -> _auth.value = AuthUi.Password(state.passwordHint.orEmpty())
            is TdApi.AuthorizationStateWaitRegistration -> _auth.value = AuthUi.Registration
            is TdApi.AuthorizationStateReady -> {
                val wasReady = _auth.value is AuthUi.Ready
                if (!wasReady) prepareAccount(c)
                _auth.value = AuthUi.Ready
                if (!wasReady) {
                    viewModelScope.launch(Dispatchers.IO) { gallerySync.syncAll() }
                    newMediaWatcher.start()
                }
            }
            else -> Unit
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

        newMediaWatcher.stop()
        uploader.cancelAndJoin()
        client = null
        cancelSessionJobsAndJoin()
        TgClient.shutdownAndWait()
        started = false
        _authError.value = null
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

    private val _authBusy = MutableStateFlow(false)
    val authBusy: StateFlow<Boolean> = _authBusy.asStateFlow()

    fun submitPhone(phone: String) = authIo { it.setPhone(phone.trim()) }

    fun submitCode(code: String) = authIo { it.checkCode(code.trim()) }

    fun submitPassword(pw: String) = authIo { it.checkPassword(pw) }

    fun submitRegistration(name: String) = authIo { it.registerUser(name.trim()) }

    /** Auth requests are one-at-a-time; Telegram rate-limits duplicate sends. */
    private fun authIo(block: suspend (TgClient) -> Unit) {
        val c = client ?: return
        if (!_authBusy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _authError.value = userError(e)
            } finally {
                _authBusy.value = false
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

    fun loadMore() = gallerySync.loadMore()

    fun openTopic(topicId: Long) = gallerySync.openTopic(topicId)

    fun closeTopic() = gallerySync.closeTopic()

    fun createAlbum(name: String) =
        viewModelScope.launch(Dispatchers.IO) {
            val c = client ?: return@launch
            try {
                val id = c.createAlbumTopic(name)
                dao.upsertAlbums(listOf(AlbumEntity(id, name, System.currentTimeMillis())))
                _toasts.tryEmit("Album \"$name\" created")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toasts.tryEmit("Could not create album: ${e.message}")
            }
        }

    fun addSelectedToAlbum(
        albumTopicId: Long,
        newAlbumName: String?,
    ) = selectionCoordinator.addSelectedToAlbum(albumTopicId, newAlbumName)

    fun toggleSelect(id: Long) = selectionCoordinator.toggleSelect(id)

    fun enterSelection(id: Long) = selectionCoordinator.enterSelection(id)

    fun exitSelectionMode() = selectionCoordinator.exitSelectionMode()

    fun selectAll(loaded: List<StashItem>) = selectionCoordinator.selectAll(loaded)

    private val uploader by lazy {
        Uploader(
            viewModelScope,
            getApplication<Application>(),
            dao,
            settings,
            { client },
            _uploadProgress,
            { count -> _gallery.update { gallery -> gallery.copy(newMediaCount = count) } },
        ) { msg -> _toasts.tryEmit(msg) }
    }

    /** Dismiss the "new media detected" banner without uploading. */
    fun dismissNewMedia() {
        _gallery.update { it.copy(newMediaCount = 0) }
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

    fun download(item: StashItem) = selectionCoordinator.download(item)

    fun downloadSelected() = selectionCoordinator.downloadSelected()

    fun delete(item: StashItem) = selectionCoordinator.delete(item)

    fun deleteSelected() = selectionCoordinator.deleteSelected()

    private val galleryCleanup by lazy {
        GalleryCleanup(
            dao = dao,
            client = { client },
            accountId = { client?.currentUserId() ?: 0L },
        )
    }

    suspend fun scanGalleryHealth(): GalleryHealth = galleryCleanup.scan()

    suspend fun cleanGallery(health: GalleryHealth): CleanupResult {
        val result = galleryCleanup.clean(health)
        val message =
            when {
                result.failed == 0 -> "Removed ${result.removed} duplicate item(s)"
                result.removed == 0 -> "Cleanup failed: ${result.error ?: "unknown error"}"
                else -> "Removed ${result.removed}; ${result.failed} cleanup failure(s)"
            }
        _toasts.emit(message)
        return result
    }

    fun setAutoBackup(
        enabled: Boolean,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
    ) = viewModelScope.launch(Dispatchers.IO) {
        settings.setBackupPreferences(enabled, wifiOnly, chargingOnly)
        backupScheduler.schedule(
            enabled = enabled,
            wifiOnly = wifiOnly,
            chargingOnly = chargingOnly,
            intervalHours = BACKUP_INTERVAL_HOURS,
            backoffSeconds = BACKOFF_SECONDS,
        )
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

    fun setRemoteDedupe(enabled: Boolean) =
        viewModelScope.launch(Dispatchers.IO) {
            settings.setRemoteDedupeEnabled(enabled)
        }

    fun setInstantUpload(enabled: Boolean) =
        viewModelScope.launch(Dispatchers.IO) {
            settings.setInstantUpload(enabled)
            if (enabled && settings.autoBackup.first()) uploadPending(auto = true)
        }

    fun backupNow() {
        _toasts.tryEmit("Backing up now…")
        uploader.uploadPending(auto = false)
    }

    fun logout() =
        viewModelScope.launch(Dispatchers.IO) {
            newMediaWatcher.stop()
            uploader.cancelAndJoin()
            val c = client
            if (c != null) {
                try {
                    c.logout()
                    if (!waitForAuthDrop(c)) {
                        _toasts.tryEmit("Telegram did not confirm logout; try again")
                        return@launch
                    }
                    TgClient.shutdownAndWait()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _toasts.tryEmit("Telegram logout needs retry: ${e.message}")
                    return@launch
                }
            }
            cancelSessionJobsAndJoin()
            client = null
            started = false
            gallerySync.resetAfterLogout()
            BackupEngine.invalidateRemoteIndexCache()
            _auth.value = AuthUi.Loading
            start()
        }

    private suspend fun waitForAuthDrop(c: TgClient): Boolean =
        withTimeoutOrNull(LOGOUT_TIMEOUT_MS) {
            c.updates
                .filterIsInstance<TdApi.UpdateAuthorizationState>()
                .map { it.authorizationState }
                .first { it is TdApi.AuthorizationStateClosed }
        } != null

    override fun onCleared() {
        newMediaWatcher.stop()
        cancelSessionJobs()
    }
}
