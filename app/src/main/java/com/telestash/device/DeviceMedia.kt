package com.telestash.device

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File

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
 * Shared MediaStore queries for the backup worker, new-media watcher and folder
 * list. Trashed, pending, dot-hidden and `.nomedia`-guarded media is skipped.
 */
object DeviceMedia {
    /** Levels from getExternalFilesDir (…/Android/data/<pkg>/files) up to the volume root. */
    private const val APP_FILES_DIR_DEPTH = 4

    /** All external volumes; [primaryOnly] restricts to the one the camera writes to. */
    private fun collection(
        images: Boolean,
        primaryOnly: Boolean = false,
    ): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return if (images) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        }
        val volume = if (primaryOnly) MediaStore.VOLUME_EXTERNAL_PRIMARY else MediaStore.VOLUME_EXTERNAL
        return if (images) {
            MediaStore.Images.Media.getContentUri(volume)
        } else {
            MediaStore.Video.Media.getContentUri(volume)
        }
    }

    /** Image collection across all external volumes. */
    fun imageCollection(): Uri = collection(images = true)

    /** Video collection across all external volumes. */
    fun videoCollection(): Uri = collection(images = false)

    /** Primary-volume image collection, for saving downloads back to the gallery. */
    fun imageCollectionPrimary(): Uri = collection(images = true, primaryOnly = true)

    /** Primary-volume video collection, for saving downloads back to the gallery. */
    fun videoCollectionPrimary(): Uri = collection(images = false, primaryOnly = true)

    /** Folders containing at least one visible image or video, busiest first. */
    fun folders(context: Context): List<DeviceFolder> {
        val photos = safeBucketCounts(context, imageCollection(), isVideo = false)
        val videos = safeBucketCounts(context, videoCollection(), isVideo = true)
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
            try {
                forEachVisible(context, collection, isVideo) { item ->
                    if (allowedFolders.isEmpty() || item.bucket in allowedFolders) out.add(item)
                }
            } catch (_: SecurityException) {
            }
        }
        return out.filter { it.uri.toString() !in knownUris }
    }

    private fun safeBucketCounts(
        context: Context,
        collection: Uri,
        isVideo: Boolean,
    ): Map<String, Int> =
        try {
            bucketCounts(context, collection, isVideo)
        } catch (_: SecurityException) {
            emptyMap()
        }

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

    /**
     * MediaStore visibility filter for this Android version. IS_PENDING exists
     * since API 29, but IS_TRASHED only since API 30 — referencing it earlier
     * throws IllegalArgumentException, so the levels are kept apart.
     */
    private fun visibilitySelection(): String? =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                "${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                "${MediaStore.MediaColumns.IS_PENDING} = 0"
            else -> null
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
            if (modern) {
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    pathColumn,
                    MediaStore.MediaColumns.VOLUME_NAME,
                )
            } else {
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    pathColumn,
                )
            }
        val selection = visibilitySelection()
        // .nomedia verdicts, cached per directory for the whole scan.
        val hiddenDirs = mutableMapOf<String, Boolean>()
        context.contentResolver
            .query(collection, projection, selection, null, null)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val pathCol = cursor.getColumnIndex(pathColumn)
                val volumeCol = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val path = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                    val bucket = cursor.getString(bucketCol) ?: rootBucketLabel(path)
                    val volume = if (volumeCol >= 0) cursor.getString(volumeCol) else null
                    if (name != null && isVisibleBucket(bucket, path)) {
                        if (isNoMediaDir(context, path, volume, hiddenDirs)) continue
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

    /** Fallback bucket for volume-root files, which have no parent folder. */
    private fun rootBucketLabel(path: String): String {
        val first = path.split('/').firstOrNull { it.isNotBlank() }
        return first ?: "(root)"
    }

    /**
     * True when the bucket isn't hidden (dot-prefixed: .thumbnails, pre-API .trashed,
     * etc.) and no path ancestor is hidden or trashed — the bucket name alone
     * can't see those (e.g. `.hidden/sub/photo.jpg` has bucket "sub").
     */
    private fun isVisibleBucket(
        bucket: String,
        path: String,
    ): Boolean {
        if (bucket.startsWith(".")) return false
        if (path.split('/').any { it.startsWith(".") && it.length > 1 }) return false
        return !path.contains(".trashed")
    }

    /**
     * True when a `.nomedia` in the row's own directory or any ancestor hides
     * it. Verdicts are cached per directory; unknown volumes resolve to false.
     */
    private fun isNoMediaDir(
        context: Context,
        path: String,
        volume: String?,
        cache: MutableMap<String, Boolean>,
    ): Boolean {
        for (dir in ancestorDirs(context, path, volume)) {
            val key = dir.absolutePath
            val hidden = cache.getOrPut(key) { File(dir, ".nomedia").exists() }
            if (hidden) return true
        }
        return false
    }

    /**
     * Ancestor directories of this row, innermost first; empty when the volume
     * can't be resolved (nothing is then treated as hidden).
     */
    private fun ancestorDirs(
        context: Context,
        path: String,
        volume: String?,
    ): List<File> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (volume != null && volume != MediaStore.VOLUME_EXTERNAL_PRIMARY) return emptyList()
            val root = primaryVolumeRoot(context) ?: return emptyList()
            val segs = path.split('/').filter { it.isNotBlank() }
            return segs.indices.map { i -> File(root, segs.take(i + 1).joinToString("/")) }
        }
        val dir = File(path).parentFile ?: return emptyList()
        return generateSequence(dir) { it.parentFile }.toList()
    }

    /**
     * Primary shared-storage root, used to turn a Q+ RELATIVE_PATH into real
     * directories. Null when unknown, in which case nothing is treated hidden.
     */
    private fun primaryVolumeRoot(context: Context): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storage = context.getSystemService(StorageManager::class.java)
            return storage?.storageVolumes?.firstOrNull { it.isPrimary }?.directory
        }
        var dir = context.getExternalFilesDir(null) ?: return null
        repeat(APP_FILES_DIR_DEPTH) { dir = dir.parentFile ?: return null }
        return dir
    }
}
