package com.telestash.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/** Notification for the background backup pass, shown while the worker runs. */
object BackupNotifications {
    const val CHANNEL_ID = "telestash-backup"
    const val NOTIFICATION_ID = 1

    /** Creates the low-importance backup channel; safe to call repeatedly. */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Backup", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    /** Builds the ongoing "N of M" progress notification (indeterminate when total is unknown). */
    fun progress(
        context: Context,
        done: Int,
        total: Int,
    ): Notification =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backing up")
            .setContentText("$done of $total items")
            .setProgress(total, done, total <= 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
}
