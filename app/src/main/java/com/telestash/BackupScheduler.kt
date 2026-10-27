package com.telestash

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.telestash.backup.BackupWorker
import java.util.concurrent.TimeUnit

/** Owns the periodic backup job and the network/charging constraints it runs under. */
internal class BackupScheduler(
    context: Context,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    /** Enables or cancels the periodic job and (re)applies the current constraints. */
    fun schedule(
        enabled: Boolean,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
    ) {
        if (!enabled) {
            workManager.cancelUniqueWork(BackupWorker.PERIODIC)
            workManager.cancelUniqueWork(BackupWorker.ONE_SHOT)
            return
        }
        val constraints = constraints(wifiOnly, chargingOnly)
        val periodic =
            PeriodicWorkRequestBuilder<BackupWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
        workManager.enqueueUniquePeriodicWork(BackupWorker.PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        // Also run now, so enabling backup doesn't wait out the first interval.
        workManager.enqueueUniqueWork(
            BackupWorker.ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints).build(),
        )
    }

    companion object {
        /** Telegram is an always-on service; a few hours of drift is harmless. */
        private const val INTERVAL_HOURS = 6L
        private const val BACKOFF_SECONDS = 30L

        /** Shared with the worker so a delayed retry uses identical rules. */
        fun constraints(
            wifiOnly: Boolean,
            chargingOnly: Boolean,
        ): Constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(chargingOnly)
                .build()
    }
}
