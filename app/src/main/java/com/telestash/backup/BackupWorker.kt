package com.telestash.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.telestash.data.SettingsRepository
import com.telestash.data.StashDbHolder
import com.telestash.td.TdException
import com.telestash.td.TgClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Periodically scans MediaStore for images/videos not yet backed up and
 * uploads them as documents to Saved Messages (see [BackupEngine]).
 */
class BackupWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {
    companion object {
        const val PERIODIC = "telestash-backup"
        const val ONE_SHOT = "telestash-backup-now"
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val client = TgClient.peek() ?: return@withContext Result.failure()
            if (!client.isStarted) return@withContext Result.retry()

            val dao = StashDbHolder.db(applicationContext).itemDao()
            val settings = SettingsRepository(applicationContext)
            val candidates =
                BackupEngine.pending(applicationContext, dao, settings.backupFolders.first())
            if (candidates.isEmpty()) return@withContext Result.success()

            val summary =
                try {
                    BackupEngine.backupAll(
                        applicationContext,
                        client,
                        dao,
                        candidates,
                        settings.dedupeEnabled.first(),
                    )
                } catch (e: TdException) {
                    // FLOOD_WAIT etc: retry the batch later from where it left off
                    // (uploaded items are already recorded in Room).
                    if (e.isFloodWait) return@withContext Result.retry() else throw e
                }
            if (summary.allFailed) Result.retry() else Result.success()
        }
}
