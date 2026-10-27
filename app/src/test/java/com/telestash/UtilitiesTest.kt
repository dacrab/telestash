package com.telestash

import com.telestash.util.FileTypes
import com.telestash.util.Format
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

    @Test
    fun sanitizeNameStripsPathsAndKeepsTheSuffix() {
        assertEquals("IMG_0001.jpg", FileTypes.sanitizeName("DCIM/Camera/IMG_0001.jpg", ".jpg"))
        // The suffix must survive the unsafe-character pass even if it holds one.
        assertEquals("my_video.mp4", FileTypes.sanitizeName("my video.mp4", ".mp4"))
    }

    @Test
    fun sanitizeNameTruncatesTheBaseButKeepsTheSuffix() {
        val long = "a".repeat(FileTypes.MAX_NAME_LENGTH * 2) + ".jpg"
        val safe = FileTypes.sanitizeName(long, ".jpg")
        assertEquals(FileTypes.MAX_NAME_LENGTH, safe.length)
        assertTrue(safe.endsWith(".jpg"))
    }

    @Test
    fun sanitizeNameFallsBackWhenNothingSurvives() {
        assertEquals("media", FileTypes.sanitizeName(""))
        assertEquals("media", FileTypes.sanitizeName("///"))
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
        assertEquals("512 B", Format.size(512))
        assertTrue(Format.size(2_048).endsWith("KB"))
        assertTrue(Format.size(5 * 1024 * 1024).endsWith("MB"))
        assertTrue(Format.size(3L * 1024 * 1024 * 1024).endsWith("GB"))
    }
}

class UploadProgressTest {
    /** A provider that over-reports must not push the bar past full or the label past 100%. */
    @Test
    fun progressClampsOutOfRangeCounters() {
        val over = UploadProgress(done = 15, total = 10)
        assertEquals(10, over.safeDone)
        assertEquals("100%", over.percentLabel)
        assertEquals(1f, over.fraction)

        val under = UploadProgress(done = -3, total = 10)
        assertEquals(0, under.safeDone)
        assertEquals("0%", under.percentLabel)
    }

    /** Nothing to divide by reads as an empty label, not a crash or "NaN%". */
    @Test
    fun emptyTotalDegradesGracefully() {
        val empty = UploadProgress(done = 0, total = 0)
        assertEquals("", empty.percentLabel)
        assertEquals(0f, empty.fraction)
    }
}
