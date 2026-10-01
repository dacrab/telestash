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

/**
 * Indexes a message for cloud-side dedupe. Requires a remote identity: media
 * Telegram has not finished uploading has nothing stable to match against.
 */
fun messageToRemoteMedia(
    message: TdApi.Message,
    accountId: Long,
    topicId: Long = 0L,
    contentHash: String? = null,
): RemoteMediaEntity? {
    val parsed = parse(message) ?: return null
    val uniqueId = parsed.remoteUniqueId?.takeIf { it.isNotBlank() } ?: return null
    val fileId = parsed.remoteFileId?.takeIf { it.isNotBlank() } ?: return null
    return parsed.item.toRemoteMedia(
        accountId = accountId,
        topicId = topicId,
        remoteUniqueId = uniqueId,
        remoteFileId = fileId,
        remoteType = parsed.remoteType,
        contentHash = contentHash,
    )
}

private fun StashItem.toRemoteMedia(
    accountId: Long,
    topicId: Long,
    remoteUniqueId: String,
    remoteFileId: String,
    remoteType: String,
    contentHash: String?,
) = RemoteMediaEntity(
    accountId = accountId,
    messageId = messageId,
    chatId = chatId,
    topicId = topicId,
    fileId = fileId,
    remoteUniqueId = remoteUniqueId,
    fileName = fileName,
    sizeBytes = sizeBytes,
    isVideo = isVideo,
    contentHash = contentHash,
    remoteFileId = remoteFileId,
    remoteType = remoteType,
)
