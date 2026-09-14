package com.telestash.device

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** A device gallery folder (MediaStore bucket) with backup-able media. */
data class DeviceFolder(
    val name: String,
    val photoCount: Int,
    val videoCount: Int,
) {
    val totalCount: Int get() = photoCount + videoCount

    /** "123 photos · 45 videos" summary for the settings list. */
    fun countLabel(): String =
        buildString {
            if (photoCount > 0) append("$photoCount photos")
            if (videoCount > 0) {
                if (isNotEmpty()) append(" · ")
                append("$videoCount videos")
            }
        }
}

/** A visible (non-trashed) device media item that hasn't been backed up yet. */
data class PendingMedia(
    val uri: Uri,
    val displayName: String,
    val bucket: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
)

/**
 * Shared MediaStore queries for the backup worker, the new-media watcher and
 * the settings folder list. Trashed, pending and hidden (dot-prefixed, e.g.
 * .trashed / .thumbnails) media is always skipped so deleted files are never
 * backed up.
 */
object DeviceMedia {
    /** Image collection covering all external volumes, API-level safe. */
    fun imageCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    /** Video collection covering all external volumes, API-level safe. */
    fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    /** Primary-volume image collection, for saving downloads back to the gallery. */
    fun imageCollectionPrimary(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    /** Primary-volume video collection, for saving downloads back to the gallery. */
    fun videoCollectionPrimary(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    /** Folders containing at least one visible image or video, busiest first. */
    fun folders(context: Context): List<DeviceFolder> {
        val photos = bucketCounts(context, imageCollection(), isVideo = false)
        val videos = bucketCounts(context, videoCollection(), isVideo = true)
        return (photos.keys + videos.keys)
            .filter { it.isNotBlank() }
            .map { DeviceFolder(it, photos[it] ?: 0, videos[it] ?: 0) }
            .sortedWith(compareByDescending<DeviceFolder> { it.totalCount }.thenBy { it.name })
    }

    /**
     * Visible items in [allowedFolders] (all folders when empty) whose URIs are
     * not in [knownUris] yet — i.e. media waiting to be backed up.
     */
    fun pending(
        context: Context,
        knownUris: Set<String>,
        allowedFolders: Set<String>,
    ): List<PendingMedia> {
        val out = mutableListOf<PendingMedia>()
        for (isVideo in listOf(false, true)) {
            val collection = if (isVideo) videoCollection() else imageCollection()
            forEachVisible(context, collection, isVideo) { item ->
                if (allowedFolders.isEmpty() || item.bucket in allowedFolders) out.add(item)
            }
        }
        return out.filter { it.uri.toString() !in knownUris }
    }

    // ---------- internals ----------

    private fun bucketCounts(
        context: Context,
        collection: Uri,
        isVideo: Boolean,
    ): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        forEachVisible(context, collection, isVideo) { item ->
            counts[item.bucket] = (counts[item.bucket] ?: 0) + 1
        }
        return counts
    }

    /** Query one collection, skipping trashed/pending/hidden rows. */
    private fun forEachVisible(
        context: Context,
        collection: Uri,
        isVideo: Boolean,
        add: (PendingMedia) -> Unit,
    ) {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val pathColumn =
            if (modern) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection =
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                pathColumn,
            )
        val selection =
            if (modern) {
                "${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
            } else {
                null
            }
        context.contentResolver
            .query(collection, projection, selection, null, null)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val pathCol = cursor.getColumnIndex(pathColumn)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val bucket = cursor.getString(bucketCol)
                    val path = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                    if (name != null && bucket != null && isVisibleBucket(bucket, path)) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                        add(
                            PendingMedia(
                                uri = uri,
                                displayName = name,
                                bucket = bucket,
                                sizeBytes = cursor.getLong(sizeCol),
                                isVideo = isVideo,
                            ),
                        )
                    }
                }
            }
    }

    /**
     * True when the bucket isn't hidden (dot-prefixed: .thumbnails, pre-API .trashed,
     * etc.) and the path doesn't contain .trashed — MediaStore's trash feature
     * moves deleted files there and we never back those up.
     */
    private fun isVisibleBucket(
        bucket: String,
        path: String,
    ): Boolean = !bucket.startsWith(".") && !path.contains(".trashed")
}
