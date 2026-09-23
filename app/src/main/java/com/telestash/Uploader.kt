package com.telestash

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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
    private val clearNewMediaBadge: () -> Unit,
    private val toast: (String) -> Unit,
) {
    companion object {
        const val MAX_FAILED_NAMES_IN_TOAST = 3
    }

    /** Scans for not-yet-backed-up device media and uploads it (explicit action). */
    fun uploadPending(auto: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            if (auto) {
                BackupGate.isBlocked(context, settings)?.let { blocked ->
                    toast(blocked)
                    return@launch
                }
            }
            clearNewMediaBadge()
            val pending = BackupEngine.pending(context, dao, settings)
            uploadPendingItems(pending, auto)
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
        if (pending.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            if (auto) {
                BackupGate.isBlocked(context, settings)?.let { blocked ->
                    toast(blocked)
                    return@launch
                }
            }
            val c = client() ?: return@launch
            val summary =
                backup {
                    BackupEngine.backupAll(context, c, dao, pending, settings.dedupeEnabled.first())
                } ?: return@launch
            toast(uploadSummary(pending.size, summary, verb = "Backed up"))
        }
    }

    /** Upload manually picked media, optionally into an album topic. */
    fun upload(
        uris: List<Uri>,
        topicId: Long = 0L,
    ): Job =
        scope.launch(Dispatchers.IO) {
            val c = client() ?: return@launch
            val pending =
                uris.mapNotNull { uri ->
                    val name = queryDisplayName(context.contentResolver, uri) ?: return@mapNotNull null
                    BackupEngine.pendingFromUri(uri, name, FileTypes.isVideoName(name))
                }
            if (pending.isEmpty()) return@launch
            val summary =
                backup {
                    BackupEngine.backupAll(context, c, dao, pending, settings.dedupeEnabled.first(), topicId)
                } ?: return@launch
            toast(uploadSummary(pending.size, summary, verb = "Uploaded"))
        }

    /**
     * Runs one backup pass and normalizes the outcome into a [BackupEngine.Summary];
     * always clears the progress row. Returns null when the pass was paused by a
     * rate limit (the flood-wait toast already told the user).
     */
    private suspend fun backup(block: suspend () -> BackupEngine.Summary): BackupEngine.Summary? =
        try {
            BackupEngine.exclusive {
                block().also { progress.value = null }
            }
        } catch (e: CancellationException) {
            progress.value = null
            throw e
        } catch (e: TdException) {
            progress.value = null
            if (e.isFloodWait) {
                toast("Backup paused: Telegram asks to wait ${e.floodWaitSeconds}s")
            }
            null
        }

    /** Human-readable result for the toast. */
    private fun uploadSummary(
        total: Int,
        s: BackupEngine.Summary,
        verb: String,
    ): String {
        val done =
            when {
                s.failed == 0 -> "$verb $total new item(s)"
                s.uploaded > 0 ->
                    "$verb ${s.uploaded}, failed ${s.failed}: " +
                        s.failedNames.take(MAX_FAILED_NAMES_IN_TOAST).joinToString()
                else -> "$verb failed: " + s.failedNames.take(MAX_FAILED_NAMES_IN_TOAST).joinToString()
            }
        return if (s.failed == 0 && s.skipped > 0) {
            "$done, skipped ${s.skipped} duplicate(s)"
        } else {
            done
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
}
