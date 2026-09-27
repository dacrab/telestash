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

internal class BackupScheduler(
    context: Context,
) {
    private val app = context.applicationContext

    fun schedule(
        enabled: Boolean,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        intervalHours: Long,
        backoffSeconds: Long,
    ) {
        val workManager = WorkManager.getInstance(app)
        if (!enabled) {
            workManager.cancelUniqueWork(BackupWorker.PERIODIC)
            workManager.cancelUniqueWork(BackupWorker.ONE_SHOT)
            return
        }
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(chargingOnly)
                .build()
        val periodic =
            PeriodicWorkRequestBuilder<BackupWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, backoffSeconds, TimeUnit.SECONDS)
                .build()
        workManager.enqueueUniquePeriodicWork(
            BackupWorker.PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
        workManager.enqueueUniqueWork(
            BackupWorker.ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints).build(),
        )
    }
}
