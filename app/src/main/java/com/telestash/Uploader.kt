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

/** Progress of one upload batch; the derived values clamp bad provider counts. */
data class UploadProgress(
    val done: Int,
    val total: Int,
) {
    /** Guards against a provider reporting more done than total; negative totals read as 0. */
    val safeTotal: Int get() = total.coerceAtLeast(0)

    val safeDone: Int get() = done.coerceIn(0, safeTotal)

    val fraction: Float get() = if (safeTotal > 0) safeDone.toFloat() / safeTotal else 0f

    /** "42%" while running, blank once there is nothing to divide by. */
    val percentLabel: String get() = if (safeTotal > 0) "${safeDone * 100 / safeTotal}%" else ""
}

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

    /** Scans for not-yet-backed-up device media and uploads it. */
    fun uploadPending(auto: Boolean = false) {
        launchTracked { run(null, auto = auto) }
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
        launchTracked { run(pending, auto = auto) }
    }

    /** Upload manually picked media, optionally into an album topic. */
    fun upload(
        uris: List<Uri>,
        topicId: Long = 0L,
    ): Job =
        launchTracked {
            val picks =
                uris.mapNotNull { uri ->
                    val name = queryDisplayName(context.contentResolver, uri) ?: return@mapNotNull null
                    BackupEngine.pendingFromUri(
                        uri,
                        name,
                        FileTypes.isVideoName(name),
                        querySize(context.contentResolver, uri),
                    )
                }
            // Manual picks never touch the pending banner: the user chose these
            // files, so nothing about the device's not-yet-backed-up media changed.
            run(picks, auto = false, topicId = topicId, updatesPendingCount = false)
        }

    /**
     * One upload pass. [pending] null means "scan the device first". [auto] marks
     * an implicit background trigger, which is subject to the Wi-Fi/charging gate.
     */
    private suspend fun run(
        pending: List<PendingMedia>?,
        auto: Boolean,
        topicId: Long = 0L,
        updatesPendingCount: Boolean = true,
    ) {
        if (auto) {
            val blocked = BackupGate.isBlocked(context, settings)
            if (blocked != null) {
                toast(blocked)
                return
            }
        }
        val items = pending ?: BackupEngine.pending(context, dao, settings.backupFolders.first())
        if (items.isEmpty()) {
            if (updatesPendingCount) setNewMediaCount(0)
            return
        }
        val c = client()
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
            uploadItems(c, items, topicId, updatesPendingCount)
        } finally {
            uploadInProgress.set(false)
        }
    }

    private suspend fun uploadItems(
        c: TgClient,
        items: List<PendingMedia>,
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
                    onProgress = { done, total -> progress.value = UploadProgress(done, total) },
                )
            if (updatesPendingCount) setNewMediaCount(summary.failed)
            toast(uploadSummary(summary))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (updatesPendingCount) setNewMediaCount(items.size)
            val message =
                if (e is TdException && e.isFloodWait) {
                    "Backup paused: Telegram asks to wait ${e.floodWaitSeconds}s"
                } else {
                    "Backup failed: ${e.message}"
                }
            toast(message)
        } finally {
            progress.value = null
        }
    }

    /** Human-readable result for the toast. */
    private fun uploadSummary(s: BackupEngine.Summary): String =
        buildString {
            append("Backed up ")
            append(if (s.uploaded == 1) "1 item" else "${s.uploaded} items")
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
