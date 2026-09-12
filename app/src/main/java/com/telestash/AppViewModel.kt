package com.telestash

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.telestash.model.StashItem
import com.telestash.td.TdException
import com.telestash.td.TgClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

/** Telegram API credentials. Get your own at https://my.telegram.org (see README). */
object TgCredentials {
    const val API_ID = 0          // TODO: set your api_id
    const val API_HASH = ""       // TODO: set your api_hash
}

sealed interface AuthUi {
    data object Loading : AuthUi
    data object Phone : AuthUi
    data class Code(val hint: String) : AuthUi
    data class Password(val hint: String) : AuthUi
    data object Registration : AuthUi
    data object Ready : AuthUi
}

sealed interface GalleryUi {
    data object Loading : GalleryUi
    data class Ready(val items: List<StashItem>, val loadingMore: Boolean) : GalleryUi
}

data class Toast(val message: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val client by lazy { TgClient.get(app, TgCredentials.API_ID, TgCredentials.API_HASH) }

    private val _auth = MutableStateFlow<AuthUi>(AuthUi.Loading)
    val auth: StateFlow<AuthUi> = _auth.asStateFlow()

    private val _gallery = MutableStateFlow<GalleryUi>(GalleryUi.Loading)
    val gallery: StateFlow<GalleryUi> = _gallery.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private val items = mutableListOf<StashItem>()
    private var oldestLoadedId = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch(Dispatchers.IO) {
            // initialize TDLib
            runCatching { client.start() }
                .onFailure { e -> _toasts.emit("TDLib error: ${e.message}"); return@launch }

            // drive auth UI from authorization state
            client.updates
                .filterIsInstance<TdApi.UpdateAuthorizationState>()
                .map { it.authorizationState }
                .collect { state ->
                    when (state) {
                        is TdApi.AuthorizationStateWaitTdlibParameters -> {
                            runCatching { client.start() }
                        }
                        is TdApi.AuthorizationStateWaitPhoneNumber -> _auth.value = AuthUi.Phone
                        is TdApi.AuthorizationStateWaitCode -> _auth.value = AuthUi.Code(describeCode(state.codeInfo))
                        is TdApi.AuthorizationStateWaitPassword -> _auth.value = AuthUi.Password(state.passwordHint.orEmpty())
                        is TdApi.AuthorizationStateWaitRegistration -> _auth.value = AuthUi.Registration
                        is TdApi.AuthorizationStateReady -> {
                            _auth.value = AuthUi.Ready
                            refresh()
                        }
                        else -> Unit
                    }
                }
        }
    }

    private fun describeCode(info: TdApi.AuthenticationCodeInfo): String {
        val t = info.type
        return when (t) {
            is TdApi.AuthenticationCodeTypeSms -> "code sent via SMS"
            is TdApi.AuthenticationCodeTypeCall -> "code sent via call"
            is TdApi.AuthenticationCodeTypeFlashCall -> "code sent via flash call"
            is TdApi.AuthenticationCodeTypeMissedCall -> "code sent via missed call"
            is TdApi.AuthenticationCodeTypeFragment -> "code sent via Fragment"
            is TdApi.AuthenticationCodeTypeFirebaseAndroid -> "code sent via Firebase"
            else -> "code sent"
        }
    }

    fun submitPhone(phone: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { client.setPhone(phone.trim()) }
            .onFailure { e -> _toasts.emit(authError(e)) }
    }

    fun submitCode(code: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { client.checkCode(code.trim()) }
            .onFailure { e -> _toasts.emit(authError(e)) }
    }

    fun submitPassword(pw: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { client.checkPassword(pw) }
            .onFailure { e -> _toasts.emit(authError(e)) }
    }

    fun submitRegistration(name: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { client.registerUser(name.trim()) }
            .onFailure { e -> _toasts.emit(authError(e)) }
    }

    private fun authError(e: Throwable): String {
        val msg = if (e is TdException) e.message else e.message ?: "unknown error"
        return if (e is TdException && e.isFloodWait) "Too many attempts. Wait ${e.floodWaitSeconds}s"
        else "Error: $msg"
    }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        _gallery.value = GalleryUi.Loading
        items.clear()
        loadHistory(0)
        _gallery.value = GalleryUi.Ready(items.toList(), false)
    }

    fun loadMore() {
        val cur = _gallery.value as? GalleryUi.Ready ?: return
        if (cur.loadingMore || oldestLoadedId <= 1L) return
        _gallery.value = GalleryUi.Ready(items.toList(), true)
        viewModelScope.launch(Dispatchers.IO) {
            loadHistory(oldestLoadedId)
            _gallery.value = GalleryUi.Ready(items.toList(), false)
        }
    }

    private suspend fun loadHistory(fromId: Long) {
        runCatching {
            val msgs = client.listPhotos(100, fromId)
            msgs.messages
                .filter { it.id < fromId || fromId == 0L }
                .mapNotNull { m ->
                    StashItem.fromMessage(m).also {
                        if (it != null && (oldestLoadedId == 0L || m.id < oldestLoadedId)) oldestLoadedId = m.id
                    }
                }
                .let { items.addAll(it) }
        }.onFailure { e ->
            if (e is TdException && e.isFloodWait) {
                kotlinx.coroutines.delay(e.floodWaitSeconds * 1000L)
            }
        }
    }

    /** Upload a local photo (from the photo picker) to Telegram. */
    fun upload(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val name = queryDisplayName(uri) ?: "photo_${System.currentTimeMillis()}.jpg"
            val cacheDir = File(getApplication<Application>().cacheDir, "upload").apply { mkdirs() }
            val staged = File(cacheDir, name)
            resolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            } ?: error("Cannot open selected file")

            client.uploadPhoto(staged.absolutePath, staged.name)
        }.onSuccess { msg ->
            val item = StashItem.fromMessage(msg)
            if (item != null) {
                items.add(0, item)
                _gallery.value = GalleryUi.Ready(items.toList(), false)
            }
            _toasts.emit("Uploaded to Telegram")
        }.onFailure { e ->
            _toasts.emit("Upload failed: ${e.message}")
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Download a full-quality copy back to the device gallery. */
    fun download(item: StashItem) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val path = client.downloadPhoto(item.fileId)
            saveToGallery(File(path), item.fileName)
        }.onSuccess {
            _toasts.emit("Saved to gallery")
        }.onFailure { e ->
            _toasts.emit("Download failed: ${e.message}")
        }
    }

    private suspend fun saveToGallery(src: File, displayName: String): Unit = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val isVideo = isVideoName(displayName)
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else null

        if (collection != null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            }
            val uri = app.contentResolver.insert(collection, values)
                ?: error("Could not create MediaStore entry")
            app.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: error("Could not open output stream")
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(
                if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            )
            dir.mkdirs()
            src.copyTo(File(dir, displayName), overwrite = true)
        }
    }

    private fun isVideoName(name: String) =
        name.endsWith(".mp4", true) || name.endsWith(".mov", true) || name.endsWith(".mkv", true)

    fun delete(item: StashItem) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            client.deleteMessages(item.chatId, longArrayOf(item.messageId))
        }.onSuccess {
            items.removeAll { it.messageId == item.messageId }
            _gallery.value = GalleryUi.Ready(items.toList(), false)
            _toasts.emit("Deleted")
        }.onFailure { e -> _toasts.emit("Delete failed: ${e.message}") }
    }

    fun logout() = viewModelScope.launch(Dispatchers.IO) {
        client.logout()
    }

    /** Local (possibly partial) file path for a gallery thumbnail, if cached by TDLib. */
    fun localPathFor(item: StashItem): String? = item.localPath
}
