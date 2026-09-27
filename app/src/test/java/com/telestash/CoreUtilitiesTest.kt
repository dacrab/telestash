package com.telestash

import com.telestash.model.messageToRemoteMedia
import com.telestash.td.TdException
import com.telestash.util.FileTypes
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTypesTest {
    @Test
    fun videoNamesUseTheirContainerMimeType() {
        assertTrue(FileTypes.isVideoName("clip.MOV"))
        assertEquals("video/quicktime", FileTypes.videoMime("clip.MOV"))
        assertEquals("video/x-matroska", FileTypes.videoMime("clip.mkv"))
        assertEquals("video/mp4", FileTypes.videoMime("clip.mp4"))
    }

    @Test
    fun imageNamesUseTheirImageMimeType() {
        assertEquals("image/png", FileTypes.imageMime("photo.PNG"))
        assertEquals("image/heic", FileTypes.imageMime("photo.heic"))
        assertEquals("image/jpeg", FileTypes.imageMime("photo.unknown"))
    }
}

class TdExceptionTest {
    @Test
    fun floodWaitIsParsedWithoutLosingNonFloodErrors() {
        val flood = TdException("FLOOD_WAIT_42 try later")
        assertTrue(flood.isFloodWait)
        assertEquals(42L, flood.floodWaitSeconds)
        assertTrue(!TdException("network unavailable").isFloodWait)
    }
}

class RemoteMediaMappingTest {
    @Test
    fun documentKeepsRemoteIdentityAndExactSize() {
        val message = documentMessage(42L, "remote-42")
        val remote = messageToRemoteMedia(message, accountId = 12L, contentHash = "hash")

        assertEquals(12L, remote?.accountId)
        assertEquals(42L, remote?.messageId)
        assertEquals(9, remote?.fileId)
        assertEquals(1234L, remote?.sizeBytes)
        assertEquals("remote-42", remote?.remoteUniqueId)
        assertEquals("remote-file-remote-42", remote?.remoteFileId)
        assertEquals("hash", remote?.contentHash)
    }

    @Test
    fun mediaWithoutRemoteIdentityIsNotIndexed() {
        assertNull(messageToRemoteMedia(documentMessage(43L, null), accountId = 12L))
    }

    private fun documentMessage(
        messageId: Long,
        remoteId: String?,
    ): TdApi.Message {
        val file =
            TdApi.File(
                9,
                1234L,
                1200L,
                TdApi.LocalFile("", false, false, false, false, 0L, 0L, 0L),
                remoteId?.let { TdApi.RemoteFile("remote-file-$it", it, false, true, 1234L) },
            )
        val document = TdApi.Document("photo.jpg", "image/jpeg", null, null, file)
        return TdApi.Message().apply {
            id = messageId
            chatId = 7L
            content = TdApi.MessageDocument(document, null)
        }
    }
}
