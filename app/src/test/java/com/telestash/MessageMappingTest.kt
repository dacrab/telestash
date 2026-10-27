package com.telestash

import com.telestash.model.StashItem
import com.telestash.model.messageToEntity
import com.telestash.model.messageToRemoteMedia
import com.telestash.td.TdException
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TdExceptionTest {
    @Test
    fun floodWaitIsParsedWithoutLosingNonFloodErrors() {
        val flood = TdException(429, "FLOOD_WAIT_42 try later")
        assertTrue(flood.isFloodWait)
        assertEquals(42L, flood.floodWaitSeconds)
        assertFalse(TdException(400, "network unavailable").isFloodWait)
    }

    @Test
    fun premiumFloodWaitIsRecognizedAndParsed() {
        val premium = TdException(420, "FLOOD_PREMIUM_WAIT_120")
        assertTrue(premium.isFloodWait)
        assertEquals(120L, premium.floodWaitSeconds)
    }

    @Test
    fun retryAfterMessageParsesAsFloodWait() {
        val retried = TdException(429, "retry after 30")
        assertTrue(retried.isFloodWait)
        assertEquals(30L, retried.floodWaitSeconds)
    }

    @Test
    fun unreadableFloodDelayIsNullNotZero() {
        assertEquals(null, TdException(429, "FLOOD_WAIT").floodWaitSeconds)
    }
}

/** The gallery only indexes media it can identify in the cloud by remote id. */
class MessageMappingTest {
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

    @Test
    fun galleryEntityCarriesTheAlbumTopic() {
        val item = messageToEntity(documentMessage(7L, "r7"), topicId = 555L)
        assertEquals(555L, item?.topicId)
        assertEquals("photo.jpg", item?.fileName)
    }

    @Test
    fun remoteTypeFollowsTheMessageKind() {
        assertEquals("document", messageToRemoteMedia(documentMessage(1L, "r1"), 5L)?.remoteType)

        val size = TdApi.PhotoSize("x", file("rp"), 10, 10, intArrayOf())
        val photo =
            TdApi.Message().apply {
                id = 2L
                chatId = 7L
                content =
                    TdApi.MessagePhoto(
                        TdApi.Photo(false, null, arrayOf(size)),
                        null,
                        null,
                        false,
                        false,
                        false,
                    )
            }
        assertEquals("photo", messageToRemoteMedia(photo, 5L)?.remoteType)
    }

    @Test
    fun videosAreRecognisedByExtensionWhenMimeIsBlank() {
        val message =
            documentMessage(4L, "r4").apply {
                content =
                    TdApi.MessageDocument(
                        TdApi.Document("clip.MP4", "", null, null, file("r4")),
                        null,
                    )
            }
        val item = messageToEntity(message)
        assertTrue("a video with a blank MIME should still be a video", item?.isVideo == true)
    }

    @Test
    fun nonMediaDocumentsAreNotGalleryItems() {
        val message =
            documentMessage(9L, "r9").apply {
                content =
                    TdApi.MessageDocument(TdApi.Document("notes.pdf", "application/pdf", null, null, file()), null)
            }
        assertNull(StashItem.fromMessage(message))
        assertNull(messageToEntity(message))
    }

    private fun documentMessage(
        messageId: Long,
        remoteId: String?,
    ): TdApi.Message {
        val document = TdApi.Document("photo.jpg", "image/jpeg", null, null, file(remoteId))
        return TdApi.Message().apply {
            id = messageId
            chatId = 7L
            content = TdApi.MessageDocument(document, null)
        }
    }

    private fun file(remoteId: String? = "r") =
        TdApi.File(
            9,
            1234L,
            1200L,
            TdApi.LocalFile("", false, false, false, false, 0L, 0L, 0L),
            remoteId?.let { TdApi.RemoteFile("remote-file-$it", it, false, true, 1234L) },
        )
}
