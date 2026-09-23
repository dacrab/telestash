package com.telestash.model

import com.telestash.util.FileTypes
import org.drinkless.tdlib.TdApi

/** A media item shown in the gallery, extracted from a Saved Messages message. */
data class StashItem(
    val messageId: Long,
    val chatId: Long,
    val date: Int,
    val fileId: Int,
    val fileName: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
    val localPath: String?,
    /** MediaStore uri of the device original, if this item is an auto-backup of it. */
    val deviceUri: String? = null,
    /** SHA-256 hex of the device original; set only for device backups. */
    val contentHash: String? = null,
    /** Saved Messages topic id (album); 0 = main gallery. */
    val topicId: Long = 0L,
    /** 64-bit perceptual hash; 0 = not computed. */
    val phash: Long = 0L,
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
                        fileName = name,
                        sizeBytes = c.document.document.expectedSize,
                        isVideo = isGalleryVideo(c.document.mimeType, name),
                        localPath =
                            c.document.document.local.path
                                .ifBlank { null },
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
                        fileName = "photo_${msg.id}.jpg",
                        sizeBytes = biggest.photo.expectedSize,
                        isVideo = false,
                        localPath =
                            biggest.photo.local.path
                                .ifBlank { null },
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
                        fileName = c.video.fileName.ifBlank { "video_${msg.id}.mp4" },
                        sizeBytes = c.video.video.expectedSize,
                        isVideo = true,
                        localPath =
                            c.video.video.local.path
                                .ifBlank { null },
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

        private fun isGalleryVideo(
            mime: String,
            name: String,
        ): Boolean = mime.startsWith("video/") || FileTypes.isVideoName(name)

        /**
         * Accept image/video mimes; a blank mime (draft/legacy documents)
         * falls back to a name-based guess.
         */
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
