package com.telestash.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.telestash.AccountGuard
import com.telestash.BackupScheduler
import com.telestash.data.SettingsRepository
import com.telestash.data.StashDbHolder
import com.telestash.td.TdException
import com.telestash.td.TgClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodically scans MediaStore for new media and uploads it via [BackupEngine].
 * Has no UI, so it bootstraps the TDLib session itself; runs foreground so a
 * long transfer survives Doze.
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

        /** Floor for a flood retry whose requested delay we could not read. */
        private const val MIN_FLOOD_RETRY_SECONDS = 60L

        /** Refresh the foreground notification every N uploaded items. */
        private const val PROGRESS_NOTIFY_EVERY = 5
    }

    /** Why [ensureTdlib] could not hand back an authorized session. */
    private sealed interface TdlibSession {
        /** No credentials stored: there is nothing to back up until the user sets them. */
        object NoCredentials : TdlibSession

        /** Session replay has not landed yet, which is normal right after start(). */
        object NotAuthorizedYet : TdlibSession

        class Ready(
            val client: TgClient,
        ) : TdlibSession
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val settings = SettingsRepository(applicationContext)
            if (!settings.autoBackup.first()) return@withContext Result.success()
            val client =
                when (val session = ensureTdlib()) {
                    TdlibSession.NoCredentials -> return@withContext Result.success()
                    TdlibSession.NotAuthorizedYet -> return@withContext Result.retry()
                    is TdlibSession.Ready -> session.client
                }

            BackupEngine.sweepStaleStaged(applicationContext)
            val dao = StashDbHolder.db(applicationContext).itemDao()
            val accountId =
                try {
                    val (userId, reset) = AccountGuard.ensureOwnedBy(client, settings, dao)
                    if (reset) BackupEngine.invalidateRemoteIndexCache()
                    userId
                } catch (e: TdException) {
                    if (e.isFloodWait) {
                        scheduleFloodRetry(e.floodWaitSeconds)
                        return@withContext Result.success()
                    }
                    // "my_id" is briefly unavailable on a cold start.
                    return@withContext Result.retry()
                }
            val candidates =
                BackupEngine.pending(applicationContext, dao, settings.backupFolders.first())
            if (candidates.isEmpty()) return@withContext Result.success()

            if (!startForeground(candidates.size)) return@withContext Result.retry()
            try {
                val summary =
                    BackupEngine.backupAll(
                        applicationContext,
                        client,
                        dao,
                        candidates,
                        settings.dedupeEnabled.first(),
                        remoteDedupe = settings.remoteDedupeEnabled.first(),
                        accountId = accountId,
                        onProgress = { done, total -> updateProgress(done, total) },
                    )
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

    private suspend fun startForeground(total: Int): Boolean {
        BackupNotifications.ensureChannel(applicationContext)
        return postProgress(0, total)
    }

    /**
     * Refreshes the notification at most every few items, not once per item:
     * a 500-item backup would otherwise post 500 system notifications.
     */
    private suspend fun updateProgress(
        done: Int,
        total: Int,
    ) {
        if (done == total || done % PROGRESS_NOTIFY_EVERY == 0) postProgress(done, total)
    }

    private suspend fun postProgress(
        done: Int,
        total: Int,
    ): Boolean =
        try {
            setForeground(
                ForegroundInfo(
                    BackupNotifications.NOTIFICATION_ID,
                    BackupNotifications.progress(applicationContext, done, total),
                ),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    /** One-shot pass that starts after the server's requested flood-wait. */
    private suspend fun scheduleFloodRetry(floodWaitSeconds: Long?) {
        // Never drop the retry: that is how a rate limit turns into a ban.
        val waitSeconds = floodWaitSeconds?.takeIf { it > 0 } ?: MIN_FLOOD_RETRY_SECONDS
        val settings = SettingsRepository(applicationContext)
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            oneTimeRequest(settings, waitSeconds),
        )
    }

    private suspend fun oneTimeRequest(
        settings: SettingsRepository,
        initialDelaySeconds: Long,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            .setConstraints(BackupScheduler.constraints(settings.wifiOnly.first(), settings.chargingOnly.first()))
            .build()

    private suspend fun ensureTdlib(): TdlibSession {
        val settings = SettingsRepository(applicationContext)
        val (apiId, apiHash) = settings.credentialsOnce()
        if (apiId == 0 || apiHash.isBlank()) return TdlibSession.NoCredentials
        // Reuse the running client: creating a second one would build a rival session.
        val client =
            TgClient.peek()?.takeIf { it.isAuthorized } ?: run {
                TgClient.getOrCreate(applicationContext, apiId, apiHash).also {
                    it.start()
                    it.refreshAuthorizationState()
                }
            }
        if (!client.isAuthorized) client.refreshAuthorizationState()
        if (client.isAuthorized) return TdlibSession.Ready(client)
        // The worker can run right after app death: give the session replay time to land.
        var waited = 0L
        while (waited < AUTH_REPLAY_TIMEOUT_MS) {
            if (client.isAuthorized) return TdlibSession.Ready(client)
            delay(AUTH_REPLAY_POLL_MS)
            waited += AUTH_REPLAY_POLL_MS
        }
        return TdlibSession.NotAuthorizedYet
    }
}
