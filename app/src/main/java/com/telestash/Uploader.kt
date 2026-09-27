package com.telestash

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.telestash.backup.BackupEngine
import com.telestash.backup.BackupGate
import com.telestash.data.ItemDao
import com.telestash.data.SettingsRepository
import com.telestash.device.PendingMedia
import com.telestash.td.TdException
import com.telestash.td.TgClient
import com.telestash.util.FileTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class UploadProgress(
    val done: Int,
    val total: Int,
)

/**
 * Thin UI-facing wrapper over [BackupEngine], which owns the upload, dedupe
 * and locking. Each entry point reports progress and surfaces a toast.
 */
class Uploader(
    private val scope: CoroutineScope,
    private val context: Context,
    private val dao: ItemDao,
    private val settings: SettingsRepository,
    private val client: () -> TgClient?,
    private val progress: MutableStateFlow<UploadProgress?>,
    private val setNewMediaCount: (Int) -> Unit,
    private val toast: (String) -> Unit,
) {
    private val uploadInProgress = AtomicBoolean(false)
    private val jobs = mutableSetOf<Job>()

    companion object {
        const val MAX_FAILED_NAMES_IN_TOAST = 3
    }

    /** Scans for not-yet-backed-up device media and uploads it (explicit action). */
    fun uploadPending(auto: Boolean = false) {
        launchTracked {
            uploadInternal(null, auto, verb = "Backed up", updatesPendingCount = true)
        }
    }

    /**
     * Upload an already-scanned pending list. With [auto] (new-media watcher,
     * instant-upload toggle) the Wi-Fi/charging gate applies; explicit uploads
     * bypass it.
     */
    fun uploadPendingItems(
        pending: List<PendingMedia>,
        auto: Boolean = false,
    ) {
        launchTracked {
            uploadInternal(pending, auto, verb = "Backed up", updatesPendingCount = true)
        }
    }

    /** Upload manually picked media, optionally into an album topic. */
    fun upload(
        uris: List<Uri>,
        topicId: Long = 0L,
    ): Job =
        launchTracked {
            val c = client() ?: return@launchTracked
            val pending =
                uris.mapNotNull { uri ->
                    val name = queryDisplayName(context.contentResolver, uri) ?: return@mapNotNull null
                    BackupEngine.pendingFromUri(
                        uri,
                        name,
                        FileTypes.isVideoName(name),
                        querySize(context.contentResolver, uri),
                    )
                }
            if (pending.isEmpty()) return@launchTracked
            uploadInternal(
                pending,
                auto = false,
                verb = "Uploaded",
                topicId = topicId,
                updatesPendingCount = false,
                resolvedClient = c,
            )
        }

    private suspend fun uploadInternal(
        pending: List<PendingMedia>?,
        auto: Boolean,
        verb: String,
        topicId: Long = 0L,
        updatesPendingCount: Boolean,
        resolvedClient: TgClient? = null,
    ) {
        if (!canUpload(auto)) return
        val items = pending ?: BackupEngine.pending(context, dao, settings)
        if (items.isEmpty()) {
            if (updatesPendingCount) setNewMediaCount(0)
            return
        }
        val c = resolvedClient ?: client()
        if (c == null) {
            if (updatesPendingCount) setNewMediaCount(items.size)
            toast("Telegram is not connected")
            return
        }
        if (!uploadInProgress.compareAndSet(false, true)) {
            toast("An upload is already in progress")
            return
        }
        try {
            uploadItems(c, items, verb, topicId, updatesPendingCount)
        } finally {
            uploadInProgress.set(false)
        }
    }

    private suspend fun canUpload(auto: Boolean): Boolean {
        if (!auto) return true
        val blocked = BackupGate.isBlocked(context, settings) ?: return true
        toast(blocked)
        return false
    }

    private suspend fun uploadItems(
        c: TgClient,
        items: List<PendingMedia>,
        verb: String,
        topicId: Long,
        updatesPendingCount: Boolean,
    ) {
        try {
            progress.value = UploadProgress(0, items.size)
            val summary =
                BackupEngine.backupAll(
                    context,
                    c,
                    dao,
                    items,
                    settings.dedupeEnabled.first(),
                    remoteDedupe = settings.remoteDedupeEnabled.first(),
                    topicId = topicId,
                    onProgress = { done, total ->
                        progress.value = UploadProgress(done, total)
                    },
                )
            if (updatesPendingCount) setNewMediaCount(summary.failed)
            toast(uploadSummary(summary, verb))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportUploadFailure(e, items.size, updatesPendingCount)
        } finally {
            progress.value = null
        }
    }

    private fun reportUploadFailure(
        e: Exception,
        itemCount: Int,
        updatesPendingCount: Boolean,
    ) {
        if (updatesPendingCount) setNewMediaCount(itemCount)
        val message =
            if (e is TdException && e.isFloodWait) {
                "Backup paused: Telegram asks to wait ${e.floodWaitSeconds}s"
            } else {
                "Backup failed: ${e.message}"
            }
        toast(message)
    }

    /** Human-readable result for the toast. */
    private fun uploadSummary(
        s: BackupEngine.Summary,
        verb: String,
    ): String =
        buildString {
            val uploaded = if (s.uploaded == 1) "1 item" else "${s.uploaded} items"
            append("$verb $uploaded")
            if (s.skipped > 0) append(", skipped ${s.skipped} already handled")
            if (s.failed > 0) {
                append(", failed ${s.failed}")
                val names = s.failedNames.take(MAX_FAILED_NAMES_IN_TOAST)
                if (names.isNotEmpty()) append(": ${names.joinToString()}")
            }
            if (s.possibleRemoteMatches.isNotEmpty()) {
                append(
                    ", possible Saved Messages match(es) left unchanged: " +
                        s.possibleRemoteMatches.joinToString(),
                )
            }
        }

    private fun queryDisplayName(
        resolver: ContentResolver,
        uri: Uri,
    ): String? {
        val cursor =
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?: return null
        return cursor.use { csr -> if (csr.moveToFirst()) csr.getString(0) else null }
    }

    private fun querySize(
        resolver: ContentResolver,
        uri: Uri,
    ): Long {
        val cursor =
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?: return 0L
        return cursor.use { csr ->
            if (csr.moveToFirst() && !csr.isNull(0)) csr.getLong(0) else 0L
        }
    }

    private fun launchTracked(block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(Dispatchers.IO) { block() }
        synchronized(jobs) { jobs.add(job) }
        job.invokeOnCompletion { synchronized(jobs) { jobs.remove(job) } }
        return job
    }

    suspend fun cancelAndJoin() {
        val current = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
        current.forEach { it.cancel() }
        current.forEach { it.join() }
        uploadInProgress.set(false)
    }
}
