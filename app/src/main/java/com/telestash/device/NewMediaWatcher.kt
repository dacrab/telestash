package com.telestash.device

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import com.telestash.backup.BackupEngine
import com.telestash.data.SettingsRepository
import com.telestash.data.StashDbHolder
import com.telestash.td.TgClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Watches MediaStore for newly added media. Camera shots land in MediaStore
 * only after the write finishes, so a debounced observer sees complete files.
 * Trashed items never trigger it (they don't pass [DeviceMedia] visibility
 * filtering). The pending list is handed to [onNewMedia] so the upload path
 * doesn't have to re-scan MediaStore.
 */
class NewMediaWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onNewMedia: (pending: List<PendingMedia>, autoUpload: Boolean) -> Unit,
) {
    companion object {
        private const val DEBOUNCE_MS = 2_500L
    }

    private var observer: ContentObserver? = null
    private var debounceJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())

    /** Start observing gallery changes; no-op if already running. */
    fun start() {
        if (observer != null) return
        val o =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    debounceJob?.cancel()
                    debounceJob =
                        scope.launch(Dispatchers.Default) {
                            delay(DEBOUNCE_MS)
                            runCatching { notifyPending() }
                        }
                }
            }
        observer = o
        context.contentResolver.registerContentObserver(
            DeviceMedia.imageCollection(),
            true,
            o,
        )
        context.contentResolver.registerContentObserver(
            DeviceMedia.videoCollection(),
            true,
            o,
        )
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        debounceJob?.cancel()
        debounceJob = null
    }

    /** Find new, not-yet-backed-up media and report it via the callback. */
    suspend fun notifyPending() {
        if (TgClient.peek() == null) return
        val dao = StashDbHolder.db(context).itemDao()
        val settings = SettingsRepository(context)
        val pending = BackupEngine.pending(context, dao, settings.backupFolders.first())
        if (pending.isNotEmpty()) {
            onNewMedia(pending, settings.instantUpload.first())
        }
    }
}
