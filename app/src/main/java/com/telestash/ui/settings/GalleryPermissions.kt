package com.telestash.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Gallery and notification permission state for the settings screen, plus the
 * launchers that change it. Permission results only arrive through the
 * activity result APIs, so the flags are refreshed when those return.
 */
@Stable
class GalleryPermissions internal constructor(
    val hasMediaAccess: Boolean,
    val isPartial: Boolean,
    val notificationsDenied: Boolean,
    private val onRequestBackupAccess: () -> Unit,
    private val onRequestNotifications: () -> Unit,
    private val onOpenAppSettings: () -> Unit,
) {
    fun requestBackupAccess() = onRequestBackupAccess()

    fun requestNotifications() = onRequestNotifications()

    fun openAppSettings() = onOpenAppSettings()
}

@Composable
fun rememberGalleryPermissions(): GalleryPermissions {
    val context = LocalContext.current
    var hasMediaAccess by remember { mutableStateOf(MediaPermissions.hasGalleryAccess(context)) }
    var isPartial by remember { mutableStateOf(MediaPermissions.hasPartialGalleryAccess(context)) }
    var notificationsDenied by remember { mutableStateOf(false) }

    fun refreshFromSystem() {
        hasMediaAccess = MediaPermissions.hasGalleryAccess(context)
        isPartial = MediaPermissions.hasPartialGalleryAccess(context)
    }

    val backupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshFromSystem()
        }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsDenied = !granted
        }
    val appSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshFromSystem()
        }

    return GalleryPermissions(
        hasMediaAccess = hasMediaAccess,
        isPartial = isPartial,
        notificationsDenied = notificationsDenied,
        onRequestBackupAccess = { backupLauncher.launch(MediaPermissions.neededForBackup()) },
        onRequestNotifications = {
            if (MediaPermissions.needsNotificationPermission()) {
                notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onOpenAppSettings = { appSettingsLauncher.launch(appSettingsIntent(context)) },
    )
}

private fun appSettingsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
