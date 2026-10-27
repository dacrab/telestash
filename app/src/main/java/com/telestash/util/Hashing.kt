package com.telestash.util

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** SHA-256 over files and streams; the exact-content identity used for dedupe. */
object Hashing {
    private const val BUFFER_SIZE = 64 * 1024
    private const val BYTE_MASK = 0xFF
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F
    private const val HEX_CHARS_PER_BYTE = 2
    private val HEX = "0123456789abcdef".toCharArray()

    /** Hex digest of an already-hashed byte array. */
    fun hex(digest: ByteArray): String {
        val out = CharArray(digest.size * HEX_CHARS_PER_BYTE)
        for (i in digest.indices) {
            val byte = digest[i].toInt() and BYTE_MASK
            out[i * HEX_CHARS_PER_BYTE] = HEX[byte ushr NIBBLE_BITS]
            out[i * HEX_CHARS_PER_BYTE + 1] = HEX[byte and NIBBLE_MASK]
        }
        return String(out)
    }

    /** SHA-256 hex digest of a file. */
    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    /** SHA-256 hex digest of a stream. */
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        copy(input, null, digest)
        return hex(digest.digest())
    }

    /**
     * Streams [input] into [output] while hashing it, so a staged copy needs
     * only one pass over the file. [output] may be null to hash without copying.
     */
    fun copy(
        input: InputStream,
        output: OutputStream?,
        digest: MessageDigest,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output?.write(buffer, 0, read)
            digest.update(buffer, 0, read)
        }
    }
}
