package com.telestash

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.telestash.backup.BackupWorker
import com.telestash.data.ItemEntity
import com.telestash.data.SettingsRepository
import com.telestash.data.StashDbHolder
import com.telestash.model.StashItem
import com.telestash.td.TdException
import com.telestash.td.TgClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
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

    val autoBackup = settings.autoBackup
    val wifiOnly = settings.wifiOnly
    val chargingOnly = settings.chargingOnly

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

            // observe gallery from Room cache
            launch {
                dao.observeAll().collect { entities ->
                    _gallery.value = _gallery.value.copy(items = entities.map(::toItem))
                    if (oldestLoaded == Long.MAX_VALUE && entities.isNotEmpty()) {
                        oldestLoaded = entities.minOf { it.messageId }
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
    )

    private fun describeCode(info: TdApi.AuthenticationCodeInfo): String = when (info.type) {
        is TdApi.AuthenticationCodeTypeSms -> "code sent via SMS"
        is TdApi.AuthenticationCodeTypeCall -> "code sent via call"
        is TdApi.AuthenticationCodeTypeFlashCall -> "code sent via flash call"
        is TdApi.AuthenticationCodeTypeMissedCall -> "code sent via missed call"
        is TdApi.AuthenticationCodeTypeFragment -> "code sent via Fragment"
        else -> "code sent"
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
        restartTg()
    }

    private suspend fun restartTg() {
        TgClient.shutdown()
        _auth.value = AuthUi.Loading
        val (apiId, apiHash) = settings.credentialsOnce()
        val c = TgClient.getOrCreate(getApplication(), apiId, apiHash)
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
            runCatching { block(c) }.onFailure { e ->
                _toasts.tryEmit(authError(e))
            }
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
            }
        }
    }

    private suspend fun syncHistory() {
        val c = client ?: return
        _gallery.value = _gallery.value.copy(syncing = true)
        try {
            var fromId = 0L
            val fetched = mutableListOf<ItemEntity>()
            // newest-first: walk pages until we hit a message id we already have
            while (true) {
                val page = c.history(savedChatId, fromId, 100)
                if (page.messages.isEmpty()) break
                val entities = page.messages.mapNotNull { m -> StashItem.fromMessage(m)?.let { toEntity(it, m) } }
                fetched.addAll(entities)
                val last = page.messages.last().id
                if (last >= fromId && fromId != 0L) break
                fromId = last
                if (entities.size < 100 && fetched.size >= 1000) break
                if (fetched.size >= 1000) break
            }
            if (fetched.isNotEmpty()) dao.upsertAll(fetched)
            if (fetched.isNotEmpty()) {
                val minId = fetched.minOf { it.messageId }
                if (minId < oldestLoaded) oldestLoaded = minId
            }
        } catch (e: Exception) {
            if (e is TdException && e.isFloodWait) {
                // non-fatal
            }
        } finally {
            _gallery.value = _gallery.value.copy(syncing = false)
        }
    }

    private fun toEntity(item: StashItem, m: TdApi.Message): ItemEntity {
        val uri = item.deviceUri
        return ItemEntity(
            messageId = item.messageId,
            chatId = item.chatId,
            date = item.date,
            fileId = item.fileId,
            fileName = item.fileName,
            sizeBytes = item.sizeBytes,
            isVideo = item.isVideo,
            localPath = item.localPath,
            deviceUri = uri,
        )
    }

    fun loadMore() {
        val cur = _gallery.value
        if (cur.loadingMore || oldestLoaded == Long.MAX_VALUE || client == null) return
        if (savedChatId == 0L) return
        _gallery.value = cur.copy(loadingMore = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = client!!.history(savedChatId, oldestLoaded, 100)
                val entities = page.messages
                    .filter { it.id < oldestLoaded }
                    .mapNotNull { m -> StashItem.fromMessage(m)?.let { toEntity(it, m) } }
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

    // ---------- uploads ----------

    fun upload(uris: List<Uri>) = viewModelScope.launch(Dispatchers.IO) {
        val c = client ?: return@launch
        var ok = 0
        var fail = 0
        for (uri in uris) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val name = queryDisplayName(resolver, uri)
                    ?: "photo_${System.currentTimeMillis()}.jpg"
                val dir = File(getApplication<Application>().cacheDir, "upload").apply { mkdirs() }
                val staged = File(dir, name)
                resolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { input.copyTo(it) }
                } ?: error("Cannot open selected file")

                val msg = c.uploadDocument(staged.absolutePath, staged.name)
                StashItem.fromMessage(msg)?.let { dao.upsertAll(listOf(toEntity(it, msg))) }
                staged.delete()
                ok++
            } catch (e: Exception) {
                fail++
            }
        }
        _toasts.tryEmit(if (fail == 0) "Uploaded $ok file(s)" else "Uploaded $ok, failed $fail")
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

    fun delete(item: StashItem) = viewModelScope.launch(Dispatchers.IO) {
        val c = client ?: return@launch
        runCatching { c.deleteMessages(item.chatId, longArrayOf(item.messageId)) }
            .onSuccess {
                dao.deleteAll(listOf(item.messageId))
                _toasts.tryEmit("Deleted")
            }
            .onFailure { e -> _toasts.tryEmit("Delete failed: ${e.message}") }
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
        wm.enqueueUniquePeriodicWork(
            BackupWorker.PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }

    fun backupNow() {
        val wm = WorkManager.getInstance(getApplication())
        val req = androidx.work.OneTimeWorkRequestBuilder<BackupWorker>().build()
        wm.enqueueUniqueWork("telestash-backup-now", androidx.work.ExistingWorkPolicy.KEEP, req)
    }

    fun logout() = viewModelScope.launch(Dispatchers.IO) {
        client?.logout()
        dao.clear()
        TgClient.shutdown()
        _auth.value = AuthUi.Loading
    }
}
