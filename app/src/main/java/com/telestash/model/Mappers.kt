package com.telestash.model

import com.telestash.data.ItemEntity
import com.telestash.data.RemoteMediaEntity
import org.drinkless.tdlib.TdApi

fun ItemEntity.toStashItem() =
    StashItem(
        messageId = messageId,
        chatId = chatId,
        date = date,
        fileId = fileId,
        fileName = fileName,
        sizeBytes = sizeBytes,
        isVideo = isVideo,
        localPath = localPath,
        deviceUri = deviceUri,
        contentHash = contentHash,
        thumbFileId = thumbFileId,
        miniJpeg = miniJpeg,
    )

/** Converts a cloud message into the database representation. */
fun StashItem.toEntity(
    topicId: Long,
    deviceUri: String? = null,
    contentHash: String? = null,
) = ItemEntity(
    messageId = messageId,
    chatId = chatId,
    date = date,
    fileId = fileId,
    fileName = fileName,
    sizeBytes = sizeBytes,
    isVideo = isVideo,
    localPath = localPath,
    deviceUri = deviceUri,
    contentHash = contentHash,
    topicId = topicId,
    thumbFileId = thumbFileId,
    miniJpeg = miniJpeg,
)

fun messageToEntity(
    message: TdApi.Message,
    topicId: Long = 0L,
): ItemEntity? = StashItem.fromMessage(message)?.toEntity(topicId = topicId)

fun messageToRemoteMedia(
    message: TdApi.Message,
    accountId: Long,
    topicId: Long = 0L,
    contentHash: String? = null,
): RemoteMediaEntity? {
    val item = StashItem.fromMessage(message) ?: return null
    val remoteUniqueId = item.remoteUniqueId?.takeIf { it.isNotBlank() } ?: return null
    val remoteFileId = item.remoteFileId?.takeIf { it.isNotBlank() } ?: return null
    val remoteType =
        when (message.content) {
            is TdApi.MessagePhoto -> "photo"
            is TdApi.MessageVideo -> "video"
            is TdApi.MessageDocument -> "document"
            else -> "document"
        }
    return RemoteMediaEntity(
        accountId = accountId,
        messageId = message.id,
        chatId = item.chatId,
        topicId = topicId,
        fileId = item.fileId,
        remoteUniqueId = remoteUniqueId,
        fileName = item.fileName,
        sizeBytes = item.sizeBytes,
        isVideo = item.isVideo,
        contentHash = contentHash,
        remoteFileId = remoteFileId,
        remoteType = remoteType,
    )
}
