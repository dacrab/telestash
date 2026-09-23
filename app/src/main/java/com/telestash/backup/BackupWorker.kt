package com.telestash.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.telestash.data.SettingsRepository
import com.telestash.data.StashDbHolder
import com.telestash.td.TdException
import com.telestash.td.TgClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodically scans MediaStore for new images/videos and uploads them via
 * [BackupEngine]. Runs with no UI, so it bootstraps the TDLib session itself —
 * this is what keeps backups going while the app stays closed. Runs as a
 * foreground service so a long transfer survives Doze and the user can see it.
 */
class BackupWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {
    companion object {
        const val PERIODIC = "telestash-backup"
        const val ONE_SHOT = "telestash-backup-now"

        /** Bounded wait for TDLib to replay the local session after start(). */
        private const val AUTH_REPLAY_TIMEOUT_MS = 15_000L
        private const val AUTH_REPLAY_POLL_MS = 250L

        /** Refresh the foreground notification every N uploaded items. */
        private const val PROGRESS_NOTIFY_EVERY = 5
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val client = ensureTdlib() ?: return@withContext Result.retry()

            BackupEngine.sweepStaleStaged(applicationContext)
            val dao = StashDbHolder.db(applicationContext).itemDao()
            val settings = SettingsRepository(applicationContext)
            val candidates =
                BackupEngine.pending(applicationContext, dao, settings.backupFolders.first())
            if (candidates.isEmpty()) return@withContext Result.success()

            startForeground(candidates.size)
            try {
                val summary =
                    BackupEngine.exclusive {
                        BackupEngine.backupAll(
                            applicationContext,
                            client,
                            dao,
                            candidates,
                            settings.dedupeEnabled.first(),
                            onProgress = { done, total -> updateProgress(done, total) },
                        )
                    }
                if (summary.allFailed) Result.retry() else Result.success()
            } catch (e: TdException) {
                // FLOOD_WAIT: retry after the server-requested pause instead of
                // hammering the default 30s exponential backoff.
                if (e.isFloodWait) {
                    scheduleFloodRetry(e.floodWaitSeconds)
                    Result.success()
                } else {
                    throw e
                }
            }
        }

    private fun startForeground(total: Int) {
        BackupNotifications.ensureChannel(applicationContext)
        runCatching {
            setForegroundAsync(
                ForegroundInfo(
                    BackupNotifications.NOTIFICATION_ID,
                    BackupNotifications.progress(applicationContext, 0, total),
                ),
            )
        }
    }

    /** Refresh the notification at most a few times per run, not once per item. */
    private fun updateProgress(
        done: Int,
        total: Int,
    ) {
        if (done == total || done % PROGRESS_NOTIFY_EVERY == 0) {
            BackupNotifications.ensureChannel(applicationContext)
            runCatching {
                setForegroundAsync(
                    ForegroundInfo(
                        BackupNotifications.NOTIFICATION_ID,
                        BackupNotifications.progress(applicationContext, done, total),
                    ),
                )
            }
        }
    }

    /** One-shot pass that starts after the server's requested flood-wait. */
    private suspend fun scheduleFloodRetry(floodWaitSeconds: Long) {
        if (floodWaitSeconds <= 0) return
        val settings = SettingsRepository(applicationContext)
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(if (settings.wifiOnly.first()) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(settings.chargingOnly.first())
                .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>()
                .setInitialDelay(floodWaitSeconds, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .build(),
        )
    }

    /**
     * A usable TDLib client: the running one, or a fresh session restored from
     * the local database (no login needed once authorized). Null when there are
     * no stored credentials or Telegram wants an interactive login.
     */
    private suspend fun ensureTdlib(): TgClient? {
        val settings = SettingsRepository(applicationContext)
        val (apiId, apiHash) = settings.credentialsOnce()
        if (apiId == 0 || apiHash.isBlank()) return null
        // Reuse the running client: creating a second one would build a rival session.
        val client =
            TgClient.peek()?.takeIf { it.isAuthorized } ?: run {
                TgClient.getOrCreate(applicationContext, apiId, apiHash).also { it.start() }
            }
        if (client.isAuthorized) return client
        return try {
            // The worker can run right after app death: give the session replay time to land.
            var waited = 0L
            while (waited < AUTH_REPLAY_TIMEOUT_MS) {
                if (client.isAuthorized) return client
                delay(AUTH_REPLAY_POLL_MS)
                waited += AUTH_REPLAY_POLL_MS
            }
            null
        } catch (e: TdException) {
            if (e.isFloodWait) throw e
            null
        }
    }
}
