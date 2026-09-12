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
import com.telestash.model.StashItem
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface AuthUi {
    data object NeedCredentials : AuthUi
    data object Loading : AuthUi
    data object Phone : AuthUi
    data class Code(val hint: String) : AuthUi
    data class Password(val hint: String) : AuthUi
    data object Registration : AuthUi
    data object Ready : AuthUi
}

data class GalleryUi(
    val items: List<StashItem> = emptyList(),
    val albums: List<AlbumEntity> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val syncing: Boolean = false,
    /** multi-select state; null = selection mode off. */
    val selected: Set<Long>? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    private val dao by lazy { StashDbHolder.db(app).itemDao() }

    private val _auth = MutableStateFlow<AuthUi>(AuthUi.Loading)
    val auth: StateFlow<AuthUi> = _auth.asStateFlow()

    private val _gallery = MutableStateFlow(GalleryUi())
    val gallery: StateFlow<GalleryUi> = _gallery.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private var client: TgClient? = null
    private var savedChatId = 0L
    private var oldestLoaded = Long.MAX_VALUE
    private var started = false
    private var viewingTopic = 0L // 0 = main gallery

    val autoBackup = settings.autoBackup
    val wifiOnly = settings.wifiOnly
    val chargingOnly = settings.chargingOnly
    val backupFolders = settings.backupFolders
    val dedupeEnabled = settings.dedupeEnabled

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
            runCatching { c.start() }
                .onFailure { e ->
                    _toasts.tryEmit("TDLib init failed: ${e.message}")
                    _auth.value = AuthUi.NeedCredentials
                    return@launch
                }

            // observe Room cache -> gallery
            launch {
                dao.observeMainItems().collect { entities ->
                    if (viewingTopic == 0L) {
                        _gallery.value = _gallery.value.copy(items = entities.map(::toItem))
                    }
                }
            }
            launch {
                dao.observeAlbums().collect { albums ->
                    _gallery.value = _gallery.value.copy(albums = albums)
                }
            }
            launch {
                dao.observeTopic(viewingTopic).collect { entities ->
                    if (viewingTopic != 0L) {
                        _gallery.value = _gallery.value.copy(items = entities.map(::toItem))
                    }
                }
            }

            // drive auth UI from authorization state
            c.updates
                .filterIsInstance<TdApi.UpdateAuthorizationState>()
                .map { it.authorizationState }
                .collect { state ->
                    when (state) {
                        is TdApi.AuthorizationStateWaitTdlibParameters -> runCatching { c.start() }
                        is TdApi.AuthorizationStateWaitPhoneNumber -> _auth.value = AuthUi.Phone
                        is TdApi.AuthorizationStateWaitCode -> _auth.value =
                            AuthUi.Code(describeCode(state.codeInfo))
                        is TdApi.AuthorizationStateWaitPassword -> _auth.value =
                            AuthUi.Password(state.passwordHint.orEmpty())
                        is TdApi.AuthorizationStateWaitRegistration -> _auth.value = AuthUi.Registration
                        is TdApi.AuthorizationStateReady -> {
                            _auth.value = AuthUi.Ready
                            runCatching {
                                savedChatId = c.savedMessagesChatId()
                                syncHistory()
                                syncAlbums()
                            }
                        }
                        else -> Unit
                    }
                }
        }
    }

    private fun toItem(e: ItemEntity) = StashItem(
        messageId = e.messageId,
        date = e.date,
        chatId = e.chatId,
        fileId = e.fileId,
        fileName = e.fileName,
        sizeBytes = e.sizeBytes,
        isVideo = e.isVideo,
        localPath = e.localPath,
        deviceUri = e.deviceUri,
        topicId = e.topicId,
        phash = e.phash,
    )

    private fun describeCode(info: TdApi.AuthenticationCodeInfo): String = when (val t = info.type) {
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

    fun saveCredentials(apiId: String, apiHash: String) = viewModelScope.launch(Dispatchers.IO) {
        val id = apiId.trim().toIntOrNull()
        if (id == null || id <= 0 || apiHash.trim().length < 8) {
            _toasts.tryEmit("Invalid credentials")
            return@launch
        }
        settings.setCredentials(id, apiHash.trim())
        _toasts.tryEmit("Saved. Starting Telegram client...")
        TgClient.shutdown()
        _auth.value = AuthUi.Loading
        val c = TgClient.getOrCreate(getApplication(), id, apiHash.trim())
        client = c
        runCatching { c.start() }
            .onFailure { e -> _toasts.tryEmit("TDLib init failed: ${e.message}") }
    }

    // ---------- auth actions ----------

    fun submitPhone(phone: String) = vmIo { c -> c.setPhone(phone.trim()) }
    fun submitCode(code: String) = vmIo { c -> c.checkCode(code.trim()) }
    fun submitPassword(pw: String) = vmIo { c -> c.checkPassword(pw) }
    fun submitRegistration(name: String) = vmIo { c -> c.registerUser(name.trim()) }

    private fun vmIo(block: suspend (TgClient) -> Unit) {
        val c = client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block(c) }.onFailure { e -> _toasts.tryEmit(authError(e)) }
        }
    }

    private fun authError(e: Throwable): String {
        val msg = e.message ?: "unknown error"
        return if (e is TdException && e.isFloodWait) "Too many attempts. Wait ${e.floodWaitSeconds}s"
        else "Error: $msg"
    }

    // ---------- gallery sync ----------

    fun refresh() {
        if (_auth.value is AuthUi.Ready) {
            viewModelScope.launch(Dispatchers.IO) {
                dao.clear()
                oldestLoaded = Long.MAX_VALUE
                syncHistory()
                syncAlbums()
            }
        }
    }

    private suspend fun syncHistory() {
        val c = client ?: return
        _gallery.value = _gallery.value.copy(syncing = true)
        try {
            var fromId = 0L
            val fetched = mutableListOf<ItemEntity>()
            while (true) {
                val page = c.history(savedChatId, fromId, 100)
                if (page.messages.isEmpty()) break
                fetched.addAll(page.messages.mapNotNull(::msgToEntity))
                fromId = page.messages.last().id
                if (fetched.size >= 1000) break
            }
            if (fetched.isNotEmpty()) dao.upsertAll(fetched)
            if (fetched.isNotEmpty()) {
                val minId = fetched.minOf { it.messageId }
                if (minId < oldestLoaded) oldestLoaded = minId
            }
        } catch (_: Exception) {
        } finally {
            _gallery.value = _gallery.value.copy(syncing = false)
        }
    }

    private suspend fun syncAlbums() {
        val c = client ?: return
        runCatching {
            val topics = c.loadTopics()
            val albums = topics.mapNotNull { t ->
                val name = when (val ty = t.type) {
                    else -> "Album ${t.id}"
                }
                AlbumEntity(topicId = t.id, name = name, order = t.order)
            }
            dao.upsertAlbums(albums)
            // sync each topic's items into Room
            for (t in topics) {
                runCatching {
                    val msgs = c.topicHistory(t.id, 0L, 100)
                    dao.upsertAll(msgs.messages.mapNotNull(::msgToEntity).map { e ->
                        e.copy(topicId = t.id)
                    })
                }
            }
        }
    }

    private fun msgToEntity(m: TdApi.Message): ItemEntity? {
        val item = StashItem.fromMessage(m) ?: return null
        return ItemEntity(
            messageId = item.messageId,
            chatId = item.chatId,
            date = item.date,
            fileId = item.fileId,
            fileName = item.fileName,
            sizeBytes = item.sizeBytes,
            isVideo = item.isVideo,
            localPath = item.localPath,
            deviceUri = null,
            topicId = 0L,
        )
    }

    fun loadMore() {
        val cur = _gallery.value
        if (cur.loadingMore || oldestLoaded == Long.MAX_VALUE || client == null) return
        if (savedChatId == 0L || viewingTopic != 0L) return
        _gallery.value = cur.copy(loadingMore = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = client!!.history(savedChatId, oldestLoaded, 100)
                val entities = page.messages
                    .filter { it.id < oldestLoaded }
                    .mapNotNull(::msgToEntity)
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
        viewingTopic = topicId
        viewModelScope.launch(Dispatchers.IO) {
            if (topicId != 0L) {
                val c = client ?: return@launch
                runCatching {
                    val msgs = c.topicHistory(topicId, 0L, 100)
                    dao.upsertAll(msgs.messages.mapNotNull(::msgToEntity).map { it.copy(topicId = topicId) })
                }
            }
        }
    }

    fun closeTopic() {
        viewingTopic = 0L
    }

    fun createAlbum(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val c = client ?: return@launch
        runCatching {
            val id = c.ensureAlbumTopic(name)
            dao.upsertAlbums(listOf(AlbumEntity(id, name, System.currentTimeMillis())))
            _toasts.tryEmit("Album \"$name\" created")
        }.onFailure { e -> _toasts.tryEmit("Could not create album: ${e.message}") }
    }

    fun deleteAlbum(topicId: Long) = viewModelScope.launch(Dispatchers.IO) {
        val c = client ?: return@launch
        runCatching {
            // delete all messages in topic then drop the album row
            val msgs = c.topicHistory(topicId, 0L, 100)
            if (msgs.messages.isNotEmpty()) {
                c.deleteMessages(savedChatId, msgs.messages.map { it.id }.toLongArray())
            }
            dao.deleteAlbum(topicId)
            val itemsInTopic = dao.observeTopic(topicId).first()
            dao.deleteAll(itemsInTopic.map { it.messageId })
            _toasts.tryEmit("Album deleted")
        }.onFailure { e -> _toasts.tryEmit("Could not delete album: ${e.message}") }
    }

    /** Add currently selected items to an album (existing or new). */
    fun addSelectedToAlbum(albumTopicId: Long, newAlbumName: String?) =
        viewModelScope.launch(Dispatchers.IO) {
            val c = client ?: return@launch
            val sel = _gallery.value.selected ?: return@launch
            if (sel.isEmpty()) return@launch
            runCatching {
                val topicId = if (newAlbumName != null) c.ensureAlbumTopic(newAlbumName) else albumTopicId
                val moved = c.moveToTopic(sel.toLongArray(), topicId)
                // update Room: re-key under topic
                dao.deleteAll(sel.toList())
                val entities = moved.mapNotNull { m ->
                    StashItem.fromMessage(m)?.let {
                        ItemEntity(
                            messageId = it.messageId, chatId = it.chatId, date = it.date,
                            fileId = it.fileId, fileName = it.fileName, sizeBytes = it.sizeBytes,
                            isVideo = it.isVideo, localPath = it.localPath, deviceUri = null,
                            topicId = topicId
                        )
                    }
                }
                dao.upsertAll(entities)
                if (newAlbumName != null) {
                    dao.upsertAlbums(listOf(AlbumEntity(topicId, newAlbumName, System.currentTimeMillis())))
                }
                _toasts.tryEmit("Added to album")
            }.onFailure { e -> _toasts.tryEmit("Move failed: ${e.message}") }
            exitSelectionMode()
        }

    // ---------- selection ----------

    fun toggleSelect(id: Long) {
        val cur = _gallery.value
        val sel = cur.selected ?: return
        _gallery.value = cur.copy(selected = (sel + id).let { if (id in sel) it - id else it })
    }

    fun enterSelection(id: Long) {
        _gallery.value = _gallery.value.copy(selected = setOf(id))
    }

    fun exitSelectionMode() {
        _gallery.value = _gallery.value.copy(selected = null)
    }

    fun selectAll() {
        _gallery.value = _gallery.value.copy(selected = _gallery.value.items.map { it.messageId }.toSet())
    }

    // ---------- uploads ----------

    fun upload(uris: List<Uri>, topicId: Long = 0L) = viewModelScope.launch(Dispatchers.IO) {
        val c = client ?: return@launch
        val dedupe = settings.dedupeEnabled.first()
        var ok = 0
        var skipped = 0
        var fail = 0
        for (uri in uris) {
            try {
                val staged = stageUri(uri) ?: error("Cannot open selected file")
                if (dedupe && checkDuplicate(staged)) {
                    staged.delete()
                    skipped++
                    continue
                }
                val msg = c.uploadDocument(staged.absolutePath, staged.name, topicId)
                StashItem.fromMessage(msg)?.let {
                    dao.upsertAll(
                        listOf(
                            ItemEntity(
                                messageId = it.messageId, chatId = it.chatId, date = it.date,
                                fileId = it.fileId, fileName = it.fileName, sizeBytes = it.sizeBytes,
                                isVideo = it.isVideo, localPath = staged.absolutePath,
                                deviceUri = uri.toString(), topicId = topicId,
                                phash = PerceptualHash.compute(staged) ?: 0L
                            )
                        )
                    )
                }
                staged.delete()
                ok++
            } catch (_: Exception) {
                fail++
            }
        }
        _toasts.tryEmit(
            when {
                skipped > 0 && fail > 0 -> "Uploaded $ok, skipped $skipped duplicates, failed $fail"
                skipped > 0 -> "Uploaded $ok, skipped $skipped duplicates"
                fail > 0 -> "Uploaded $ok, failed $fail"
                else -> "Uploaded $ok file(s)"
            }
        )
    }

    private suspend fun checkDuplicate(staged: File): Boolean {
        val hash = PerceptualHash.compute(staged) ?: return false
        val existing = dao.allPhashes()
        return existing.any { PerceptualHash.isProbablyDuplicate(it.phash, hash) }
    }

    private fun stageUri(uri: Uri): File? {
        val resolver = getApplication<Application>().contentResolver
        val name = queryDisplayName(resolver, uri)
            ?: "photo_${System.currentTimeMillis()}.jpg"
        val dir = File(getApplication<Application>().cacheDir, "upload").apply { mkdirs() }
        val staged = File(dir, name)
        resolver.openInputStream(uri)?.use { input ->
            staged.outputStream().use { input.copyTo(it) }
        } ?: return null
        return staged
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { csr -> if (csr.moveToFirst()) csr.getString(0) else null }

    // ---------- item actions ----------

    fun download(item: StashItem) = viewModelScope.launch(Dispatchers.IO) {
        val c = client ?: return@launch
        runCatching {
            val path = c.download(item.fileId)
            saveToGallery(File(path), item.fileName, item.isVideo)
        }.onSuccess { _toasts.tryEmit("Saved to gallery") }
            .onFailure { e -> _toasts.tryEmit("Download failed: ${e.message}") }
    }

    fun downloadSelected() = viewModelScope.launch(Dispatchers.IO) {
        val c = client ?: return@launch
        val sel = _gallery.value.selected ?: return@launch
        var ok = 0
        for (id in sel) {
            val item = _gallery.value.items.find { it.messageId == id } ?: continue
            runCatching {
                val path = c.download(item.fileId)
                saveToGallery(File(path), item.fileName, item.isVideo)
                ok++
            }
        }
        _toasts.tryEmit("Saved $ok to gallery")
        exitSelectionMode()
    }

    fun delete(item: StashItem) = viewModelScope.launch(Dispatchers.IO) {
        val c = client ?: return@launch
        runCatching { c.deleteMessages(item.chatId, longArrayOf(item.messageId)) }
            .onSuccess {
                dao.deleteAll(listOf(item.messageId))
                _toasts.tryEmit("Deleted")
            }
            .onFailure { e -> _toasts.tryEmit("Delete failed: ${e.message}") }
    }

    fun deleteSelected() = viewModelScope.launch(Dispatchers.IO) {
        val c = client ?: return@launch
        val sel = _gallery.value.selected ?: return@launch
        val items = _gallery.value.items.filter { it.messageId in sel }
        runCatching {
            // group by chat (Saved Messages is one chat, but be safe)
            items.groupBy { it.chatId }.forEach { (chat, list) ->
                c.deleteMessages(chat, list.map { it.messageId }.toLongArray())
            }
            dao.deleteAll(sel.toList())
            _toasts.tryEmit("Deleted ${sel.size} item(s)")
        }.onFailure { e -> _toasts.tryEmit("Delete failed: ${e.message}") }
        exitSelectionMode()
    }

    private suspend fun saveToGallery(src: File, displayName: String, isVideo: Boolean) =
        withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val collection = if (isVideo)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    if (isVideo) "video/mp4" else guessMime(displayName)
                )
            }
            val uri = app.contentResolver.insert(collection, values)
                ?: error("Could not create MediaStore entry")
            app.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: error("Could not open output stream")
        }

    private fun guessMime(name: String) = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".heic", true) || name.endsWith(".heif", true) -> "image/heic"
        name.endsWith(".gif", true) -> "image/gif"
        else -> "image/jpeg"
    }

    // ---------- settings / backup ----------

    fun setAutoBackup(enabled: Boolean, wifiOnly: Boolean, chargingOnly: Boolean) =
        viewModelScope.launch(Dispatchers.IO) {
            settings.setAutoBackup(enabled)
            settings.setWifiOnly(wifiOnly)
            settings.setChargingOnly(chargingOnly)
            scheduleBackup(enabled, wifiOnly, chargingOnly)
            _toasts.tryEmit(if (enabled) "Auto-backup enabled" else "Auto-backup disabled")
        }

    fun setBackupFolders(folders: Set<String>) = viewModelScope.launch(Dispatchers.IO) {
        settings.setBackupFolders(folders)
        _toasts.tryEmit(
            if (folders.isEmpty()) "Backing up all folders"
            else "Backing up ${folders.size} folder(s)"
        )
    }

    fun setDedupe(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        settings.setDedupeEnabled(enabled)
    }

    private fun scheduleBackup(enabled: Boolean, wifiOnly: Boolean, chargingOnly: Boolean) {
        val wm = WorkManager.getInstance(getApplication())
        if (!enabled) {
            wm.cancelUniqueWork(BackupWorker.PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(chargingOnly)
            .build()
        val req = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(BackupWorker.PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun backupNow() {
        val wm = WorkManager.getInstance(getApplication())
        wm.enqueueUniqueWork(
            "telestash-backup-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().build()
        )
    }

    fun logout() = viewModelScope.launch(Dispatchers.IO) {
        client?.logout()
        dao.clear()
        TgClient.shutdown()
        _auth.value = AuthUi.Loading
    }
}
