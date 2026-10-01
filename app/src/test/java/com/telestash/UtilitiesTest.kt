package com.telestash

import com.telestash.util.FileTypes
import com.telestash.util.Hashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

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

class HashingTest {
    /** Well-known SHA-256 digests, so a change in the hex encoding is caught. */
    @Test
    fun emptyAndKnownPayloadsHashToTheirPublishedDigests() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hashing.sha256(ByteArrayInputStream(ByteArray(0))),
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Hashing.sha256(ByteArrayInputStream("hello".toByteArray())),
        )
    }

    @Test
    fun hexIsLowercaseAndZeroPadded() {
        // 0x00 and 0x0f would pad or drop digits under a naive formatter.
        assertEquals("000fff", Hashing.hex(byteArrayOf(0, 0x0f, 0xff.toByte())))
    }

    @Test
    fun differentContentProducesDifferentHashes() {
        val a = Hashing.sha256(ByteArrayInputStream("photo-a".toByteArray()))
        val b = Hashing.sha256(ByteArrayInputStream("photo-b".toByteArray()))
        assertNotEquals(a, b)
    }
}

class FormatSizeTest {
    @Test
    fun sizesScaleToReadableUnits() {
        assertEquals(
            "512 B",
            com.telestash.util.Format
                .size(512),
        )
        assertTrue(
            com.telestash.util.Format
                .size(2_048)
                .endsWith("KB"),
        )
        assertTrue(
            com.telestash.util.Format
                .size(5 * 1024 * 1024)
                .endsWith("MB"),
        )
        assertTrue(
            com.telestash.util.Format
                .size(3L * 1024 * 1024 * 1024)
                .endsWith("GB"),
        )
    }
}
