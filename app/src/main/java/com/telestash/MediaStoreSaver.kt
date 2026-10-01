package com.telestash

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.telestash.data.ItemDao
import com.telestash.data.UploadedEntity
import com.telestash.device.DeviceMedia
import com.telestash.util.FileTypes
import com.telestash.util.Hashing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal class MediaStoreSaver(
    context: Context,
    private val dao: ItemDao,
) {
    private val app = context.applicationContext

    suspend fun save(
        source: File,
        displayName: String,
        isVideo: Boolean,
    ) = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        val collection =
            if (isVideo) {
                DeviceMedia.videoCollectionPrimary()
            } else {
                DeviceMedia.imageCollectionPrimary()
            }
        val usesPending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val values = contentValues(displayName, isVideo, usesPending)
        val uri =
            resolver.insert(collection, values)
                ?: error("Could not create MediaStore entry")
        var published = false
        try {
            val contentHash = copyAndHash(resolver, uri, source)
            publishIfNeeded(resolver, uri, usesPending)
            published = true
            try {
                dao.upsertUploaded(
                    listOf(
                        UploadedEntity(
                            contentHash = contentHash,
                            fileName = displayName,
                            sizeBytes = source.length(),
                            deviceUri = uri.toString(),
                            uploadedAt = System.currentTimeMillis(),
                        ),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        } catch (e: CancellationException) {
            if (!published) deletePartial(resolver, uri)
            throw e
        } catch (e: Exception) {
            if (!published) deletePartial(resolver, uri)
            throw e
        }
    }

    private fun contentValues(
        displayName: String,
        isVideo: Boolean,
        usesPending: Boolean,
    ): ContentValues =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(
                MediaStore.MediaColumns.MIME_TYPE,
                if (isVideo) FileTypes.videoMime(displayName) else FileTypes.imageMime(displayName),
            )
            if (usesPending) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

    private fun copyAndHash(
        resolver: ContentResolver,
        uri: Uri,
        source: File,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> Hashing.copy(input, output, digest) }
        } ?: error("Could not open output stream")
        return Hashing.hex(digest.digest())
    }

    private fun publishIfNeeded(
        resolver: ContentResolver,
        uri: Uri,
        usesPending: Boolean,
    ) {
        if (!usesPending) return
        val published =
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
        if (resolver.update(uri, published, null, null) != 1) {
            error("Could not publish MediaStore entry")
        }
    }

    private fun deletePartial(
        resolver: ContentResolver,
        uri: Uri,
    ) {
        runCatching { resolver.delete(uri, null, null) }
    }
}
