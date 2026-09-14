package com.telestash

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.telestash.backup.BackupWorker
import com.telestash.data.AlbumEntity
import com.telestash.data.ItemEntity
import com.telestash.data.SettingsRepository
import com.telestash.data.StashDbHolder
import com.telestash.device.DeviceMedia
import com.telestash.device.NewMediaWatcher
import com.telestash.device.PendingMedia
import com.telestash.model.StashItem
import com.telestash.model.messageToEntity
import com.telestash.model.toEntity
import com.telestash.model.toStashItem
import com.telestash.td.TdException
import com.telestash.td.TgClient
import com.telestash.util.PerceptualHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    val items: List<StashItem> = emptyList(),
    val albums: List<AlbumEntity> = emptyList(),
    val loadingMore: Boolean = false,
    val syncing: Boolean = false,
    /** Upload progress: done count out of total, null = not uploading. */
    val uploadProgress: Pair<Int, Int>? = null,
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
        const val SYNC_MAX_ITEMS = 1000
        const val PICK_LIMIT = 100
        const val SCROLL_PRELOAD_THRESHOLD = 12
        const val BACKUP_INTERVAL_HOURS = 6L
        const val BACKOFF_SECONDS = 30L
        const val MAX_FAILED_NAMES_IN_TOAST = 3
        private const val SECONDS_PER_MINUTE = 60
    }

    private val settings = SettingsRepository(app)
    private val dao by lazy { StashDbHolder.db(app).itemDao() }

    private val _auth = MutableStateFlow<AuthUi>(AuthUi.Loading)
    val auth: StateFlow<AuthUi> = _auth.asStateFlow()

    private val _gallery = MutableStateFlow(GalleryUi())
    val gallery: StateFlow<GalleryUi> = _gallery.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private var client: TgClient? = null
    private var oldestLoaded = Long.MAX_VALUE
    private var started = false
    private val newMediaWatcher =
        NewMediaWatcher(getApplication(), viewModelScope) { count, autoUpload ->
            if (autoUpload) {
                uploadPending()
            } else {
                _gallery.value = _gallery.value.copy(newMediaCount = count)
            }
        }

    val autoBackup = settings.autoBackup
    val wifiOnly = settings.wifiOnly
    val chargingOnly = settings.chargingOnly
    val backupFolders = settings.backupFolders
    val dedupeEnabled = settings.dedupeEnabled
    val instantUpload = settings.instantUpload

    /** Start (or restart after credentials change) the TDLib client + collectors. */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch(Dispatchers.IO) {
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

    /** Room -> UI. flatMapLatest so switching albums re-subscribes correctly. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeGallery() {
        viewModelScope.launch {
            _gallery
                .flatMapLatest { g ->
                    if (g.openTopicId == 0L) dao.observeMainItems() else dao.observeTopic(g.openTopicId)
                }.collect { entities ->
                    _gallery.value = _gallery.value.copy(items = entities.map { it.toStashItem() })
                    val min = entities.minOfOrNull { it.messageId } ?: Long.MAX_VALUE
                    if (min < oldestLoaded) oldestLoaded = min
                }
        }
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
                        // Watch for new camera-roll media while signed in.
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

    // ---------- credentials onboarding ----------

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

        // recreate the client and wire everything up again via start()
        TgClient.shutdown()
        client = null
        started = false
        _auth.value = AuthUi.Loading
        start()
    }

    // ---------- auth actions ----------

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
                runCatching { block(c) }.onFailure { e -> _toasts.tryEmit(userError(e)) }
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

    // ---------- gallery sync ----------

    private suspend fun syncAll() {
        val c = client ?: return
        _gallery.value = _gallery.value.copy(syncing = true)
        try {
            var fromId = 0L
            val fetched = mutableListOf<ItemEntity>()
            while (fetched.size < SYNC_MAX_ITEMS) {
                val page = c.history(fromId, HISTORY_PAGE_SIZE)
                if (page.messages.isEmpty()) break
                fetched.addAll(page.messages.mapNotNull { messageToEntity(it) })
                fromId = page.messages.last().id
            }
            dao.upsertAll(fetched)
            syncAlbums()
        } catch (_: Exception) {
        } finally {
            _gallery.value = _gallery.value.copy(syncing = false)
        }
    }

    private suspend fun syncAlbums() {
        val c = client ?: return
        runCatching {
            val topics = c.loadTopics()
            dao.upsertAlbums(
                topics.map { AlbumEntity(topicId = it.id, name = "Album ${it.id}", order = it.order) },
            )
            for (t in topics) {
                runCatching {
                    val msgs = c.topicHistory(t.id, 0L, HISTORY_PAGE_SIZE)
                    dao.upsertAll(msgs.messages.mapNotNull { messageToEntity(it, topicId = t.id) })
                }
            }
        }
    }

    fun loadMore() {
        val cur = _gallery.value
        if (cur.loadingMore || cur.openTopicId != 0L) return
        if (oldestLoaded == Long.MAX_VALUE || client == null) return
        _gallery.value = cur.copy(loadingMore = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = client!!.history(oldestLoaded, HISTORY_PAGE_SIZE)
                val entities =
                    page.messages
                        .filter { it.id < oldestLoaded }
                        .mapNotNull { messageToEntity(it) }
                if (entities.isNotEmpty()) {
                    dao.upsertAll(entities)
                    oldestLoaded = entities.minOf { it.messageId }
                }
            } catch (_: Exception) {
            } finally {
                _gallery.value = _gallery.value.copy(loadingMore = false)
            }
        }
    }

    // ---------- albums ----------

    fun openTopic(topicId: Long) {
        _gallery.value = _gallery.value.copy(openTopicId = topicId, selected = null)
        if (topicId != 0L) {
            viewModelScope.launch(Dispatchers.IO) {
                val c = client ?: return@launch
                runCatching {
                    val msgs = c.topicHistory(topicId, 0L, HISTORY_PAGE_SIZE)
                    dao.upsertAll(msgs.messages.mapNotNull { messageToEntity(it, topicId) })
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

    fun deleteAlbum(topicId: Long) =
        viewModelScope.launch(Dispatchers.IO) {
            val c = client ?: return@launch
            runCatching {
                val msgs = c.topicHistory(topicId, 0L, HISTORY_PAGE_SIZE)
                if (msgs.messages.isNotEmpty()) {
                    c.deleteMessages(
                        msgs.messages.first().chatId,
                        msgs.messages.map { it.id }.toLongArray(),
                    )
                }
                dao.deleteAlbum(topicId)
                _toasts.tryEmit("Album deleted")
            }.onFailure { e -> _toasts.tryEmit("Could not delete album: ${e.message}") }
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
            dao.upsertAll(moved.mapNotNull { messageToEntity(it, topicId = topicId) })
            if (newAlbumName != null) {
                dao.upsertAlbums(
                    listOf(AlbumEntity(topicId, newAlbumName, System.currentTimeMillis())),
                )
            }
            _toasts.tryEmit("Moved to album")
        }.onFailure { e -> _toasts.tryEmit("Move failed: ${e.message}") }
        exitSelectionMode()
    }

    // ---------- selection ----------

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

    fun selectAll() {
        val items = _gallery.value.items
        _gallery.value = _gallery.value.copy(selected = items.map { it.messageId }.toSet())
        if (items.size < SYNC_MAX_ITEMS) return
        // Endless scroll means "all" is really "all loaded so far".
        _toasts.tryEmit("Selected ${items.size} loaded items - scroll down to load more")
    }

    // ---------- uploads ----------

    private enum class UploadOutcome { UPLOADED, SKIPPED_DUPLICATE, FAILED }

    private val uploadMutex = Mutex()

    /** Dismiss the "new media detected" banner without uploading. */
    fun dismissNewMedia() {
        _gallery.value = _gallery.value.copy(newMediaCount = 0)
    }

    /**
     * Upload every not-yet-backed-up device media item (used by the new-media
     * banner, instant upload, and the backup worker when the app is running).
     */
    fun uploadPending() {
        if (_gallery.value.uploadProgress != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val c = client ?: return@launch
            val daoRef = dao
            val allowed = settings.backupFolders.first()
            val known = daoRef.allDeviceUris().toSet()
            val pending = DeviceMedia.pending(getApplication(), known, allowed)
            _gallery.value = _gallery.value.copy(newMediaCount = 0)
            if (pending.isEmpty()) return@launch
            uploadMutex.withLock {
                val dedupe = settings.dedupeEnabled.first()
                var failed = 0
                val failedNames = mutableListOf<String>()

                pending.forEachIndexed { i, item ->
                    _gallery.value = _gallery.value.copy(uploadProgress = i to pending.size)
                    when (uploadPendingOne(c, daoRef, item, dedupe)) {
                        UploadOutcome.UPLOADED -> Unit
                        UploadOutcome.SKIPPED_DUPLICATE -> Unit
                        UploadOutcome.FAILED -> {
                            failed++
                            failedNames.add(item.displayName)
                        }
                    }
                }
                _gallery.value = _gallery.value.copy(uploadProgress = null)

                if (failed > 0) {
                    val sample = failedNames.take(MAX_FAILED_NAMES_IN_TOAST).joinToString()
                    val msg =
                        if (failed == pending.size) {
                            "Backup failed: $sample"
                        } else {
                            "Backed up ${pending.size - failed}, failed $failed: $sample"
                        }
                    _toasts.tryEmit(msg)
                } else {
                    _toasts.tryEmit("Backed up ${pending.size} new item(s)")
                }
            }
        }
    }

    /** Backup one device media item; returns UPLOADED, SKIPPED (dupe) or FAILED. */
    private suspend fun uploadPendingOne(
        c: TgClient,
        dao: com.telestash.data.ItemDao,
        item: PendingMedia,
        dedupe: Boolean,
    ): UploadOutcome {
        val staged = stageUri(item.uri) ?: return UploadOutcome.FAILED
        try {
            var phash = 0L
            if (dedupe && !item.isVideo) {
                phash = PerceptualHash.compute(staged) ?: 0L
                if (phash != 0L && dao.allPhashes().any { PerceptualHash.isProbablyDuplicate(it, phash) }) {
                    // record without uploading so this uri is never re-scanned
                    dao.upsertAll(listOf(item.markerEntity()))
                    return UploadOutcome.SKIPPED_DUPLICATE
                }
            }
            val msg = c.uploadDocument(staged.absolutePath, item.displayName)
            StashItem.fromMessage(msg)?.let {
                dao.upsertAll(listOf(it.toEntity(deviceUri = item.uri.toString(), phash = phash)))
            }
            return UploadOutcome.UPLOADED
        } catch (_: Exception) {
            return UploadOutcome.FAILED
        } finally {
            staged.delete()
        }
    }

    /** Synthetic Room row marking a perceptual duplicate as "handled". */
    private fun PendingMedia.markerEntity() =
        com.telestash.data.ItemEntity(
            messageId = -uri.hashCode().toLong(),
            chatId = 0L,
            date = 0,
            fileId = 0,
            fileName = displayName,
            sizeBytes = sizeBytes,
            isVideo = isVideo,
            localPath = null,
            deviceUri = uri.toString(),
            phash = 0L,
        )

    fun upload(
        uris: List<Uri>,
        topicId: Long = 0L,
    ) = viewModelScope.launch(Dispatchers.IO) {
        val c = client ?: return@launch
        uploadMutex.withLock {
            val dedupe = settings.dedupeEnabled.first()
            var failed = 0
            val failedNames = mutableListOf<String>()

            uris.forEachIndexed { i, uri ->
                _gallery.value = _gallery.value.copy(uploadProgress = i to uris.size)
                if (uploadOne(c, uri, topicId, dedupe) == UploadOutcome.FAILED) {
                    failed++
                    failedNames.add(queryDisplayName(getApplication(), uri) ?: "file ${i + 1}")
                }
            }
            _gallery.value = _gallery.value.copy(uploadProgress = null)

            val ok = uris.size - failed
            val failedSample = failedNames.take(MAX_FAILED_NAMES_IN_TOAST).joinToString()
            _toasts.tryEmit(
                when {
                    failed == 0 -> "Uploaded ${uris.size} file(s)"
                    ok > 0 -> "Uploaded $ok, failed $failed: $failedSample"
                    else -> "Upload failed: $failedSample"
                },
            )
        }
    }

    private suspend fun uploadOne(
        c: TgClient,
        uri: Uri,
        topicId: Long,
        dedupe: Boolean,
    ): UploadOutcome {
        val staged = stageUri(uri) ?: return UploadOutcome.FAILED
        try {
            val phash =
                if (dedupe && !looksLikeVideo(staged.name)) {
                    PerceptualHash.compute(staged) ?: 0L
                } else {
                    0L
                }

            if (phash != 0L && dao.allPhashes().any { PerceptualHash.isProbablyDuplicate(it, phash) }) {
                return UploadOutcome.SKIPPED_DUPLICATE
            }

            val msg = c.uploadDocument(staged.absolutePath, staged.name, topicId)
            StashItem.fromMessage(msg)?.let {
                dao.upsertAll(listOf(it.toEntity(deviceUri = uri.toString(), phash = phash)))
            }
            return UploadOutcome.UPLOADED
        } catch (_: Exception) {
            return UploadOutcome.FAILED
        } finally {
            staged.delete()
        }
    }

    /** Copy a content uri into the cache dir so TDLib can send it by local path. */
    private fun stageUri(uri: Uri): File? {
        val resolver = getApplication<Application>().contentResolver
        val name =
            queryDisplayName(resolver, uri)
                ?: "photo_${System.currentTimeMillis()}.jpg"
        // disambiguate same-named picks within one session
        val dir = File(getApplication<Application>().cacheDir, "upload").apply { mkdirs() }
        val staged = File(dir, "${System.currentTimeMillis()}_$name")
        return resolver.openInputStream(uri)?.use { input ->
            staged.outputStream().use { input.copyTo(it) }
            staged
        }
    }

    private fun queryDisplayName(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): String? =
        resolver
            .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { csr -> if (csr.moveToFirst()) csr.getString(0) else null }

    private fun looksLikeVideo(name: String) =
        when {
            name.endsWith(".mp4", true) ||
                name.endsWith(".mov", true) ||
                name.endsWith(".mkv", true) ||
                name.endsWith(".avi", true) ||
                name.endsWith(".webm", true) -> true
            else -> false
        }

    // ---------- item actions ----------

    fun download(item: StashItem) = downloadItems(listOf(item))

    fun downloadSelected() {
        val sel = _gallery.value.selected ?: return
        val items = _gallery.value.items.filter { it.messageId in sel }
        downloadItems(items) // exits selection mode when done
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

    fun deleteSelected() {
        val sel = _gallery.value.selected ?: return
        val items = _gallery.value.items.filter { it.messageId in sel }
        deleteItems(items)
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
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else guessMime(displayName))
            }
        val uri =
            app.contentResolver.insert(collection, values)
                ?: error("Could not create MediaStore entry")
        app.contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: error("Could not open output stream")
    }

    private fun guessMime(name: String) =
        when {
            name.endsWith(".png", true) -> "image/png"
            name.endsWith(".webp", true) -> "image/webp"
            name.endsWith(".heic", true) || name.endsWith(".heif", true) -> "image/heic"
            name.endsWith(".gif", true) -> "image/gif"
            else -> "image/jpeg"
        }

    // ---------- settings / backup ----------

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
            if (enabled) uploadPending()
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
    }

    fun backupNow() {
        _toasts.tryEmit("Backing up now...")
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            "telestash-backup-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().build(),
        )
    }

    fun logout() =
        viewModelScope.launch(Dispatchers.IO) {
            newMediaWatcher.stop()
            client?.logout()
            dao.clear()
            TgClient.shutdown()
            client = null
            oldestLoaded = Long.MAX_VALUE
            _gallery.value = GalleryUi()
            _auth.value = AuthUi.Loading
        }
}
