package com.telestash.model

import com.telestash.data.ItemEntity

/** Message <-> entity <-> UI-model mappings, shared by ViewModel and BackupWorker. */

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
        topicId = topicId,
        phash = phash,
    )

fun StashItem.toEntity(
    deviceUri: String? = this.deviceUri,
    phash: Long = this.phash,
    topicId: Long = this.topicId,
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
    phash = phash,
    topicId = topicId,
)

fun messageToEntity(
    m: org.drinkless.tdlib.TdApi.Message,
    topicId: Long = 0L,
): ItemEntity? = StashItem.fromMessage(m)?.toEntity(topicId = topicId)
