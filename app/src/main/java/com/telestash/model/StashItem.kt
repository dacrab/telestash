package com.telestash.model

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
    /** Saved Messages topic id (album); 0 = main gallery. */
    val topicId: Long = 0L,
    /** 64-bit perceptual hash; 0 = not computed. */
    val phash: Long = 0L,
) {
    val backedUpDeviceUri: String? get() = deviceUri

    companion object {
        fun fromMessage(msg: TdApi.Message): StashItem? {
            return when (val c = msg.content) {
                is TdApi.MessageDocument -> {
                    val name = c.document.fileName.ifBlank { "file" }
                    val mime = c.document.mimeType
                    StashItem(
                        messageId = msg.id,
                        chatId = msg.chatId,
                        date = msg.date,
                        fileId = c.document.document.id,
                        fileName = name,
                        sizeBytes = c.document.document.expectedSize,
                        isVideo = mime.startsWith("video/") || name.endsWith(".mp4", true)
                            || name.endsWith(".mov", true) || name.endsWith(".mkv", true),
                        localPath = c.document.document.local.path.ifBlank { null },
                    )
                }
                is TdApi.MessagePhoto -> {
                    val biggest = c.photo.sizes.lastOrNull() ?: return null
                    StashItem(
                        messageId = msg.id,
                        chatId = msg.chatId,
                        date = msg.date,
                        fileId = biggest.photo.id,
                        fileName = "photo_${msg.id}.jpg",
                        sizeBytes = biggest.photo.expectedSize,
                        isVideo = false,
                        localPath = biggest.photo.local.path.ifBlank { null },
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
                        localPath = c.video.video.local.path.ifBlank { null },
                    )
                }
                else -> null
            }
        }
    }
}
