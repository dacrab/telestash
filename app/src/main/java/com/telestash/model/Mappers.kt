package com.telestash.model

import com.telestash.data.ItemEntity
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
        topicId = topicId,
        phash = phash,
        thumbFileId = thumbFileId,
        miniJpeg = miniJpeg,
    )

/**
 * [topicId] is required because a cloud-synced message never carries it, and
 * forgetting it was a real bug. [deviceUri]/[contentHash]/[phash] default to
 * their "absent" values.
 */
fun StashItem.toEntity(
    topicId: Long,
    deviceUri: String? = null,
    contentHash: String? = null,
    phash: Long = 0L,
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
    phash = phash,
    topicId = topicId,
    thumbFileId = thumbFileId,
    miniJpeg = miniJpeg,
)

fun messageToEntity(
    message: TdApi.Message,
    topicId: Long = 0L,
): ItemEntity? = StashItem.fromMessage(message)?.toEntity(topicId = topicId)
