package com.telestash.model

import org.drinkless.tdlib.TdApi

/** A media item shown in the gallery, extracted from a Saved Messages document message. */
data class StashItem(
    val messageId: Long,
    val chatId: Long,
    val date: Int,
    val fileId: Int,
    val fileName: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
    val width: Int,
    val height: Int,
    val minithumb: ByteArray?,
    val localPath: String?,
) {
    companion object {
        fun fromMessage(msg: TdApi.Message): StashItem? {
            return when (val c = msg.content) {
                is TdApi.MessageDocument -> {
                    val name = c.document.fileName.ifBlank { "photo" }
                    StashItem(
                        messageId = msg.id,
                        chatId = msg.chatId,
                        date = msg.date,
                        fileId = c.document.document.id,
                        fileName = name,
                        sizeBytes = c.document.document.expectedSize,
                        isVideo = c.document.mimeType.startsWith("video/"),
                        width = 0,
                        height = 0,
                        minithumb = c.document.minithumbnail?.data,
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
                        width = biggest.width,
                        height = biggest.height,
                        minithumb = c.photo.minithumbnail?.data,
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
                        width = c.video.width,
                        height = c.video.height,
                        minithumb = c.video.minithumbnail?.data,
                        localPath = c.video.video.local.path.ifBlank { null },
                    )
                }
                else -> null
            }
        }
    }
}
