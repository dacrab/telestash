package com.telestash.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.telestash.backup.BackupEngine.Outcome
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
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val client = TgClient.peek() ?: return@withContext Result.failure()
            if (!client.isStarted) return@withContext Result.retry()

            val dao = StashDbHolder.db(applicationContext).itemDao()
            val settings = SettingsRepository(applicationContext)
            val candidates =
                BackupEngine.pending(applicationContext, dao, settings.backupFolders.first())
            val dedupe = settings.dedupeEnabled.first()

            var uploaded = 0
            var failures = 0
            for (cand in candidates) {
                if (isStopped) return@withContext Result.retry()
                if (dao.countByDeviceUri(cand.uri.toString()) > 0) continue
                try {
                    when (BackupEngine.backupOne(applicationContext, client, dao, cand, dedupe)) {
                        Outcome.UPLOADED -> uploaded++
                        Outcome.SKIPPED -> Unit
                        Outcome.FAILED -> failures++
                    }
                } catch (e: TdException) {
                    if (e.isFloodWait) return@withContext Result.retry()
                    failures++
                }
            }
            if (failures > 0 && uploaded == 0) Result.retry() else Result.success()
        }
}
