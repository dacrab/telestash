package com.telestash.ui.settings

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Which gallery permissions this Android version actually needs for backup. */
object MediaPermissions {
    /**
     * Full or partial gallery access. Partial (Android 14+ "select photos")
     * counts: TeleStash backs up exactly the items the user selected.
     */
    fun hasGalleryAccess(context: Context): Boolean =
        hasImageAccess(context) || hasVideoAccess(context) || hasSelectedMediaAccess(context)

    /** True when access is granted but not for the whole library. */
    fun hasPartialGalleryAccess(context: Context): Boolean =
        hasGalleryAccess(context) && !(hasImageAccess(context) && hasVideoAccess(context))

    fun hasImageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(context, READ_MEDIA_IMAGES)
        } else {
            granted(context, READ_EXTERNAL_STORAGE)
        }

    fun hasVideoAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(context, READ_MEDIA_VIDEO)
        } else {
            granted(context, READ_EXTERNAL_STORAGE)
        }

    private fun hasSelectedMediaAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(context, READ_MEDIA_VISUAL_USER_SELECTED)

    fun hasNotificationAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, POST_NOTIFICATIONS)

    /** True on Android 13+, where backup progress needs a user-granted permission. */
    fun needsNotificationPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The gallery permissions to request on this Android version. */
    fun neededForBackup(): Array<String> =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
            else ->
                // Pre-Q there's no scoped storage, so writing downloads back to
                // the gallery needs the legacy write permission too.
                arrayOf(READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE)
        }

    private fun granted(
        context: Context,
        permission: String,
    ): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
