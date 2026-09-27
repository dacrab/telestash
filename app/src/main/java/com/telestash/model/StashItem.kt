package com.telestash.model

import com.telestash.util.FileTypes
import org.drinkless.tdlib.TdApi

/** A media item shown in the gallery, extracted from a Saved Messages message. */
data class StashItem(
    val messageId: Long,
    val chatId: Long,
    val date: Int,
    val fileId: Int,
    val remoteUniqueId: String? = null,
    val remoteFileId: String? = null,
    val fileName: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
    val localPath: String?,
    /** MediaStore uri of the device original, if this item is an auto-backup of it. */
    val deviceUri: String? = null,
    /** SHA-256 hex of the device original; set only for device backups. */
    val contentHash: String? = null,
    /** TDLib thumbnail file id for cheap grid previews; 0 = none. */
    val thumbFileId: Int = 0,
    /** TDLib minithumbnail JPEG bytes for an instant placeholder; null if none. */
    val miniJpeg: ByteArray? = null,
) {
    companion object {
        private val IMAGE_EXTENSIONS =
            setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif")

        fun fromMessage(msg: TdApi.Message): StashItem? {
            return when (val c = msg.content) {
                is TdApi.MessageDocument -> {
                    val name = c.document.fileName.ifBlank { "file" }
                    // The stash is a photo/video gallery: anything else would
                    // only ever render as a broken cell.
                    if (!isGalleryMime(c.document.mimeType, name)) return null
                    StashItem(
                        messageId = msg.id,
                        chatId = msg.chatId,
                        date = msg.date,
                        fileId = c.document.document.id,
                        remoteUniqueId =
                            c.document.document.remote
                                ?.uniqueId,
                        remoteFileId =
                            c.document.document.remote
                                ?.id,
                        fileName = name,
                        sizeBytes = fileSize(c.document.document),
                        isVideo = isGalleryVideo(c.document.mimeType, name),
                        localPath = localPath(c.document.document),
                        thumbFileId =
                            c.document.thumbnail
                                ?.file
                                ?.id ?: 0,
                        miniJpeg = c.document.minithumbnail?.data,
                    )
                }
                is TdApi.MessagePhoto -> {
                    val sizes = c.photo.sizes
                    if (sizes.isEmpty()) return null
                    val biggest = sizes.maxBy { it.width * it.height }
                    val smallest = sizes.minBy { it.width * it.height }
                    StashItem(
                        messageId = msg.id,
                        chatId = msg.chatId,
                        date = msg.date,
                        fileId = biggest.photo.id,
                        remoteUniqueId = biggest.photo.remote?.uniqueId,
                        remoteFileId = biggest.photo.remote?.id,
                        fileName = "photo_${msg.id}.jpg",
                        sizeBytes = fileSize(biggest.photo),
                        isVideo = false,
                        localPath = localPath(biggest.photo),
                        thumbFileId = smallest.photo.id,
                        miniJpeg = c.photo.minithumbnail?.data,
                    )
                }
                is TdApi.MessageVideo -> {
                    StashItem(
                        messageId = msg.id,
                        chatId = msg.chatId,
                        date = msg.date,
                        fileId = c.video.video.id,
                        remoteUniqueId =
                            c.video.video.remote
                                ?.uniqueId,
                        remoteFileId =
                            c.video.video.remote
                                ?.id,
                        fileName = c.video.fileName.ifBlank { "video_${msg.id}.mp4" },
                        sizeBytes = fileSize(c.video.video),
                        isVideo = true,
                        localPath = localPath(c.video.video),
                        thumbFileId =
                            c.video.thumbnail
                                ?.file
                                ?.id ?: 0,
                        miniJpeg = c.video.minithumbnail?.data,
                    )
                }
                else -> null
            }
        }

        private fun localPath(file: TdApi.File): String? =
            file.local
                ?.path
                ?.takeIf { file.local?.isDownloadingCompleted == true }
                ?.takeIf { it.isNotBlank() }

        private fun fileSize(file: TdApi.File): Long = file.size.takeIf { it > 0L } ?: file.expectedSize

        private fun isGalleryVideo(
            mime: String,
            name: String,
        ): Boolean = mime.startsWith("video/") || FileTypes.isVideoName(name)

        /** Accept image/video MIME types and fall back to the filename when blank. */
        private fun isGalleryMime(
            mime: String,
            name: String,
        ): Boolean {
            if (mime.startsWith("image/") || mime.startsWith("video/")) return true
            if (mime.isBlank()) return isGalleryVideo(mime, name) || isStillImageName(name)
            return false
        }

        private fun isStillImageName(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in IMAGE_EXTENSIONS
        }
    }
}
